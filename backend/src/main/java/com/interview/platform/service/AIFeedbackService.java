package com.interview.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.platform.dto.FeedbackDTO;
import com.interview.platform.model.Feedback;
import com.interview.platform.model.Interview;
import com.interview.platform.model.InterviewStatus;
import com.interview.platform.model.Response;
import com.interview.platform.repository.FeedbackRepository;
import com.interview.platform.repository.InterviewRepository;
import com.interview.platform.repository.ResponseRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Service for generating AI-powered interview feedback using a local Ollama
 * Llama 3 API.
 *
 * <h3>Purpose:</h3>
 * <p>
 * After a user completes an interview and all responses have been transcribed,
 * this service builds a prompt from the question-answer pairs and sends it to
 * Ollama for evaluation. The AI returns a structured feedback JSON containing
 * an overall score, strengths, weaknesses, recommendations, and a detailed
 * analysis. This feedback is persisted to the database and displayed on the
 * user's dashboard.
 * </p>
 *
 * <h3>P1 Changes:</h3>
 * <ul>
 * <li><strong>Resilience4j:</strong> The manual retry loop with exponential
 * backoff for the Ollama API call has been replaced with programmatic
 * Resilience4j {@link Retry} and {@link CircuitBreaker} decorators.
 * This provides configurable retry behavior, circuit breaker protection
 * against sustained OpenAI outages, and observable metrics via the
 * Spring Boot Actuator.</li>
 * <li><strong>SLF4J logging:</strong> Migrated from {@code java.util.logging}
 * to SLF4J for structured, parameterized logging consistent with the
 * rest of the application.</li>
 * </ul>
 *
 * <h3>Circuit Breaker Behavior:</h3>
 * <p>
 * This service shares the "ollama" circuit breaker instance with
 * {@link OllamaService}. If Ollama is experiencing issues, both question
 * generation and feedback generation will fail fast, preventing unnecessary
 * API calls and reducing latency for the user. The
 * {@link com.interview.platform.task.InterviewRecoveryTask} will detect
 * interviews stuck in {@code PROCESSING} state and transition them to
 * {@code FAILED} after a configurable timeout.
 * </p>
 *
 * @see OllamaService
 * @see com.interview.platform.config.ResilienceConfig
 * @see com.interview.platform.task.InterviewRecoveryTask
 */
@Service
public class AIFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AIFeedbackService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResponseRepository responseRepository;
    private final InterviewRepository interviewRepository;
    private final FeedbackRepository feedbackRepository;

    // Resilience4j instances — shares the "ollama" named instances with
    // OllamaService
    private final Retry ollamaRetry;
    private final CircuitBreaker ollamaCircuitBreaker;

    @Value("${ollama.api.url:http://localhost:11434/api/chat}")
    private String apiUrl;

    @Value("${ollama.model:llama3}")
    private String model;

    public AIFeedbackService(RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ResponseRepository responseRepository,
            InterviewRepository interviewRepository,
            FeedbackRepository feedbackRepository,
            @Qualifier("ollamaRetry") Retry ollamaRetry,
            @Qualifier("ollamaCircuitBreaker") CircuitBreaker ollamaCircuitBreaker) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.responseRepository = responseRepository;
        this.interviewRepository = interviewRepository;
        this.feedbackRepository = feedbackRepository;
        this.ollamaRetry = ollamaRetry;
        this.ollamaCircuitBreaker = ollamaCircuitBreaker;
    }

    // ════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate AI feedback for a completed interview (synchronous).
     *
     * @param interviewId the interview to generate feedback for
     * @return the persisted Feedback entity
     */
    @Transactional
    public Feedback generateFeedback(Long interviewId) {
        log.info("Generating AI feedback for interview ID: {}", interviewId);

        // Step 1: Fetch interview
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("Interview not found: " + interviewId));

        // Step 2: Check if feedback already exists
        Optional<Feedback> existingFeedback = feedbackRepository.findByInterviewId(interviewId);
        if (existingFeedback.isPresent()) {
            log.info("Feedback already exists for interview ID: {}", interviewId);
            return existingFeedback.get();
        }

        // Step 3: Fetch all responses with transcriptions
        List<Response> responses = responseRepository.findByInterviewIdOrderByQuestionId(interviewId);
        if (responses.isEmpty()) {
            throw new RuntimeException("No responses found for interview: " + interviewId);
        }

        // Step 4: Build prompt from Q&A pairs
        String prompt = buildFeedbackPrompt(responses);

        // Step 5: Call Ollama (with Resilience4j retry + circuit breaker)
        String aiResponse = callOllamaWithResilience(prompt);

        // AUDIT-FIX: Wrap parse step in try-catch — malformed Ollama response logs warning and sets interview to FAILED
        FeedbackDTO feedbackDTO;
        try {
            // Step 6: Parse feedback
            feedbackDTO = parseFeedbackResponse(aiResponse);
        } catch (Exception e) {
            log.warn("AUDIT-FIX: Malformed Ollama feedback response for interview ID: {}. "
                    + "Setting interview to FAILED. Raw response: {}", interviewId,
                    aiResponse != null && aiResponse.length() > 500 ? aiResponse.substring(0, 500) + "..." : aiResponse, e);
            interview.transitionTo(InterviewStatus.FAILED);
            interviewRepository.save(interview);
            throw new RuntimeException("Failed to parse AI feedback response for interview " + interviewId, e);
        }

        // Step 7: Create and save Feedback entity
        Feedback feedback = createFeedbackEntity(interview, feedbackDTO);

        // Step 8: Update interview with overall score
        interview.setOverallScore(feedbackDTO.getScore());
        interviewRepository.save(interview);

        log.info("AI feedback generated for interview ID: {} — score: {}", interviewId, feedbackDTO.getScore());

        return feedback;
    }

    /**
     * Async version for background processing.
     */
    @Async("avatarTaskExecutor")
    public CompletableFuture<Feedback> generateFeedbackAsync(Long interviewId) {
        try {
            Feedback feedback = generateFeedback(interviewId);
            return CompletableFuture.completedFuture(feedback);
        } catch (Exception e) {
            log.error("Async feedback generation failed for interview ID: {}", interviewId, e);
            CompletableFuture<Feedback> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Prompt Building
    // ════════════════════════════════════════════════════════════════

    private String buildFeedbackPrompt(List<Response> responses) {
        StringBuilder qaPairs = new StringBuilder();
        int questionNumber = 1;

        for (Response response : responses) {
            String questionText = response.getQuestion() != null
                    ? response.getQuestion().getQuestionText()
                    : "Question " + questionNumber;

            String transcription = response.getTranscription();
            if (transcription == null || transcription.isBlank()) {
                transcription = "[No response transcription available]";
            }

            // AUDIT-FIX: Sanitize transcription text to mitigate prompt injection via user speech
            transcription = sanitizePromptInput(transcription);
            questionText = sanitizePromptInput(questionText);

            qaPairs.append(String.format("[Q%d: %s, A%d: %s]\n",
                    questionNumber, questionText, questionNumber, transcription));
            questionNumber++;
        }

        return String.format(
                """
                        You are an expert interview evaluator and integrity monitor.
                        Analyze the following interview Q&A pairs carefully:
                        %s

                        INTEGRITY CHECK (IMPORTANT):
                        - Examine each answer transcript carefully for signs of cheating or misconduct.
                        - If an answer transcript contains labels such as "Speaker A:", "Speaker B:", or multiple distinct voices, this is evidence of CHEATING (another person assisting).
                        - If answers seem copied from an external source (very formal, perfectly structured, unlikely for verbal speech) flag it as suspicious.
                        - If cheating is detected, set the score to 20 or below and include a clear "⚠️ INTEGRITY ALERT: Cheating detected — multiple voices or unauthorized assistance found in responses." message at the START of the detailedAnalysis.

                        Provide:
                        1. Overall score (0-100), applying integrity penalty if cheating detected
                        2. Top 3-5 strengths
                        3. Top 3-5 weaknesses
                        4. Specific actionable recommendations

                        Return as JSON only, no markdown:
                        {
                          "score": <number 0-100>,
                          "strengths": ["strength1", "strength2", ...],
                          "weaknesses": ["weakness1", "weakness2", ...],
                          "recommendations": ["recommendation1", "recommendation2", ...],
                          "detailedAnalysis": "A paragraph summarizing performance and any integrity concerns"
                        }
                        """,
                qaPairs.toString());
    }

    // ════════════════════════════════════════════════════════════════
    // Ollama API Call (with Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * AUDIT-FIX: Sanitize text before interpolation into LLM prompts.
     * Strips prompt injection patterns (case-insensitive) and truncates to 4000 chars.
     *
     * @param text the raw text to sanitize
     * @return sanitized text safe for prompt interpolation
     */
    private String sanitizePromptInput(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // AUDIT-FIX: Truncate to 4000 characters max
        String sanitized = text.length() > 4_000 ? text.substring(0, 4_000) : text;

        // AUDIT-FIX: Strip common prompt injection patterns (case-insensitive)
        sanitized = sanitized.replaceAll("(?i)ignore\\s+(all\\s+)?previous\\s+instructions", "")
                .replaceAll("(?i)system\\s*:", "")
                .replaceAll("(?i)<\\|im_start\\|>", "")
                .replaceAll("(?i)<\\|im_end\\|>", "")
                .replaceAll("(?i)<\\|im_sep\\|>", "")
                .replaceAll("###\\s*", "")
                .replaceAll("(?i)\\[INST\\]", "")
                .replaceAll("(?i)\\[/INST\\]", "")
                .replaceAll("(?i)<<SYS>>", "")
                .replaceAll("(?i)<</SYS>>", "")
                .replaceAll("(?i)you\\s+are\\s+now\\s+", "")
                .replaceAll("(?i)disregard\\s+(all\\s+)?prior\\s+", "")
                .replaceAll("(?i)forget\\s+(all\\s+)?previous\\s+", "")
                .replaceAll("(?i)new\\s+instructions?\\s*:", "")
                .replace("```", "")
                .replace("<|", "")
                .replace("|>", "");

        return sanitized;
    }

    /**
     * Call Ollama endpoint with Resilience4j retry and circuit breaker.
     */
    private String callOllamaWithResilience(String prompt) {
        return Decorators.ofSupplier(() -> callOllama(prompt))
                .withRetry(ollamaRetry)
                .withCircuitBreaker(ollamaCircuitBreaker)
                .decorate()
                .get();
    }

    /**
     * Make the actual HTTP POST to the Ollama endpoint.
     */
    private String callOllama(String prompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("format", "json");
        requestBody.put("stream", false);

        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content",
                "You are an expert interview coach and career advisor. "
                        + "Analyze interview responses and provide constructive, actionable feedback. "
                        + "Always respond with valid JSON only.");
        messages.add(systemMessage);

        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        requestBody.put("messages", messages);

        log.debug("Calling Ollama API for feedback: model={}", model);
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl, requestBody, String.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Empty response from Ollama API");
        }

        return extractContentFromResponse(response.getBody());
    }

    private String extractContentFromResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("message");

            if (message.isMissingNode() || !message.has("content")) {
                throw new RuntimeException("Unexpected JSON structure returned from Ollama API");
            }

            String content = message.get("content").asText();

            if (content == null || content.isBlank()) {
                throw new RuntimeException("Empty content in Ollama API response");
            }

            return content.trim();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Ollama response JSON", e);
            throw new RuntimeException("Failed to parse Ollama API response", e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Response Parsing
    // ════════════════════════════════════════════════════════════════

    private FeedbackDTO parseFeedbackResponse(String content) {
        try {
            String jsonContent = content;

            // Extract only the JSON object part
            int startIndex = jsonContent.indexOf('{');
            int endIndex = jsonContent.lastIndexOf('}');
            if (startIndex >= 0 && startIndex <= endIndex) {
                jsonContent = jsonContent.substring(startIndex, endIndex + 1);
            } else {
                log.warn("Response does not contain a JSON object delimiter: {}", content);
            }

            jsonContent = jsonContent.trim();

            FeedbackDTO dto = objectMapper.readValue(jsonContent, FeedbackDTO.class);

            // Validate score range
            if (dto.getScore() < 0) {
                dto.setScore(0);
            } else if (dto.getScore() > 100) {
                dto.setScore(100);
            }

            // Ensure lists are not null
            if (dto.getStrengths() == null) {
                dto.setStrengths(Collections.emptyList());
            }
            if (dto.getWeaknesses() == null) {
                dto.setWeaknesses(Collections.emptyList());
            }
            if (dto.getRecommendations() == null) {
                dto.setRecommendations(Collections.emptyList());
            }
            if (dto.getDetailedAnalysis() == null) {
                dto.setDetailedAnalysis("");
            }

            return dto;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse feedback JSON: {}", content, e);
            throw new RuntimeException(
                    "Failed to parse AI feedback. The response was not in the expected JSON format.", e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Entity Creation
    // ════════════════════════════════════════════════════════════════

    private Feedback createFeedbackEntity(Interview interview, FeedbackDTO dto) {
        Feedback feedback = new Feedback();
        feedback.setInterview(interview);
        feedback.setUser(interview.getUser());
        feedback.setOverallScore(dto.getScore());

        // Store lists as JSON strings
        try {
            feedback.setStrengths(objectMapper.writeValueAsString(dto.getStrengths()));
            feedback.setWeaknesses(objectMapper.writeValueAsString(dto.getWeaknesses()));
            feedback.setRecommendations(objectMapper.writeValueAsString(dto.getRecommendations()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize feedback lists to JSON, using toString()", e);
            feedback.setStrengths(dto.getStrengths().toString());
            feedback.setWeaknesses(dto.getWeaknesses().toString());
            feedback.setRecommendations(dto.getRecommendations().toString());
        }

        feedback.setDetailedAnalysis(dto.getDetailedAnalysis());

        return feedbackRepository.save(feedback);
    }

}
