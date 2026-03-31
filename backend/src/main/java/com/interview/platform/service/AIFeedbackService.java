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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        FeedbackDTO feedbackDTO;
        if (responses.isEmpty()) {
            log.warn("No responses found for interview: {}. Using fallback feedback.", interviewId);
            feedbackDTO = buildFallbackFeedback(responses);
        } else {
            // Step 4: Build prompt from Q&A pairs
            String prompt = buildFeedbackPrompt(responses);

            // FIX: Wrap both the API call and parse step in try-catch — malformed responses
            // OR connection
            // timeouts to Ollama will fall back to basic feedback instead of crashing the
            // interview
            try {
                // Step 5: Call Ollama (with Resilience4j retry + circuit breaker)
                String aiResponse = callOllamaWithResilience(prompt);

                // Step 6: Parse feedback
                feedbackDTO = parseFeedbackResponse(aiResponse);
            } catch (Exception e) {
                log.warn("FIX: Ollama feedback generation failed for interview ID: {}. "
                        + "Using fallback feedback. Error: {}", interviewId, e.getMessage(), e);
                // FIX: Return a basic fallback feedback object instead of crashing
                feedbackDTO = buildFallbackFeedback(responses);
            }
        }

        // Step 7: Create and save Feedback entity
        Feedback feedback = createFeedbackEntity(interview, feedbackDTO);

        // Step 8: Update interview with overall score
        // FIX: Re-fetch the interview to get the LATEST version, avoiding
        // OptimisticLockingFailureException when the completeInterview callback
        // has already bumped the version number.
        saveInterviewScoreWithRetry(interviewId, feedbackDTO.getScore());

        log.info("AI feedback generated for interview ID: {} — score: {}", interviewId, feedbackDTO.getScore());

        return feedback;
    }

    /**
     * Save the overall score on the Interview entity with optimistic lock retry.
     *
     * <p>This method re-fetches the interview from the database to get the latest
     * version before saving. If a concurrent update causes an optimistic locking
     * failure, it retries up to 3 times with a fresh fetch each time.</p>
     *
     * @param interviewId the interview to update
     * @param score       the overall score to set
     */
    private void saveInterviewScoreWithRetry(Long interviewId, int score) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Always re-fetch to get the latest version
                Interview freshInterview = interviewRepository.findById(interviewId)
                        .orElseThrow(() -> new RuntimeException("Interview not found: " + interviewId));
                freshInterview.setOverallScore(score);
                interviewRepository.save(freshInterview);
                log.debug("Saved overall score {} for interview {} (attempt {})", score, interviewId, attempt);
                return; // Success — exit the retry loop
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic locking conflict saving score for interview {} (attempt {}/{})",
                        interviewId, attempt, maxRetries);
                if (attempt == maxRetries) {
                    log.error("Failed to save interview score after {} retries — score may not be persisted",
                            maxRetries, e);
                    // Don't crash — feedback entity is already saved, just score won't be on interview
                }
                // Brief sleep before retry to let the competing transaction finish
                try { Thread.sleep(100L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
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

    /**
     * FIX: Build a basic fallback feedback when Ollama returns invalid JSON.
     * Never let a JSON parse error crash the feedback generation — return
     * a usable feedback object with appropriate score based on transcription availability.
     *
     * @param responses the interview responses (for building a basic summary)
     * @return a FeedbackDTO with default values
     */
    private FeedbackDTO buildFallbackFeedback(List<Response> responses) {
        log.info("FIX: Building fallback feedback for {} responses", responses.size());
        FeedbackDTO dto = new FeedbackDTO();

        if (responses.isEmpty()) {
            dto.setScore(0);
            dto.setStrengths(List.of("Started the interview session"));
            dto.setWeaknesses(List.of("No responses were recorded during the interview"));
            dto.setRecommendations(List.of(
                    "Ensure your microphone and camera are working properly",
                    "Try to complete all questions in your next interview attempt"
            ));
            dto.setDetailedAnalysis("We could not generate detailed feedback because no responses were recorded for this interview session. "
                    + "Please make sure you record and submit your answers before finishing the interview.");
            return dto;
        }

        // Count responses with valid transcriptions (not empty, not placeholder messages)
        long validTranscriptions = responses.stream()
                .filter(r -> {
                    String t = r.getTranscription();
                    return t != null && !t.isBlank() 
                        && !t.contains("[Transcription unavailable")
                        && !t.equals("No response recorded");
                })
                .count();

        if (validTranscriptions == 0) {
            // No real transcriptions available - score of 0 is more accurate than 50
            dto.setScore(0);
            dto.setStrengths(List.of("Completed the interview session", "Submitted video responses"));
            dto.setWeaknesses(List.of(
                    "Transcriptions were unavailable for your responses",
                    "AI feedback could not be generated without transcriptions"
            ));
            dto.setRecommendations(List.of(
                    "Ensure your microphone is working and speech is clearly audible",
                    "In local development mode, transcription requires browser speech recognition",
                    "Re-attempt the interview with a stable internet connection for cloud transcription"
            ));
            dto.setDetailedAnalysis("We could not generate detailed feedback because no transcriptions were captured for your responses. "
                    + "You submitted " + responses.size() + " video responses, but the audio could not be transcribed. "
                    + "This may happen in local development mode where cloud transcription is unavailable. "
                    + "Please ensure your microphone is working and try again.");
        } else if (validTranscriptions < responses.size()) {
            // Partial transcriptions - give proportional score
            int proportionalScore = (int) ((validTranscriptions * 50) / responses.size());
            dto.setScore(Math.max(25, proportionalScore)); // At least 25 if some responses were captured
            dto.setStrengths(List.of(
                    "Completed the interview",
                    validTranscriptions + " out of " + responses.size() + " responses were transcribed"
            ));
            dto.setWeaknesses(List.of(
                    "Some responses could not be transcribed",
                    "Automatic analysis was only partial"
            ));
            dto.setRecommendations(List.of(
                    "Ensure consistent microphone quality throughout the interview",
                    "Speak clearly and at a moderate pace for better transcription accuracy"
            ));
            dto.setDetailedAnalysis("Partial feedback available. " + validTranscriptions + " out of " + responses.size() 
                    + " responses were successfully transcribed. Review your video recordings to self-assess the missing responses.");
        } else {
            // All transcriptions available but Ollama parsing failed
            dto.setScore(50); // Neutral score when Ollama analysis fails
            dto.setStrengths(List.of("Completed the interview", "All responses were transcribed"));
            dto.setWeaknesses(List.of("Automatic AI analysis was unavailable for detailed feedback"));
            dto.setRecommendations(List.of(
                    "Review your responses and self-evaluate against the job requirements",
                    "Practice answering similar questions with a peer for more detailed feedback"
            ));
            dto.setDetailedAnalysis("Automatic AI analysis unavailable but all " + responses.size() 
                    + " responses were captured. Please review your answers to self-assess your performance.");
        }
        return dto;
    }

    private String buildFeedbackPrompt(List<Response> responses) {
        StringBuilder qaPairs = new StringBuilder();
        int questionNumber = 1;

        for (Response response : responses) {
            String questionText = response.getQuestion() != null
                    ? response.getQuestion().getQuestionText()
                    : "Question " + questionNumber;

            String transcription = response.getTranscription();
            // FIX: Use "No response recorded" when transcription is null/empty instead of
            // bracket notation
            if (transcription == null || transcription.isBlank()) {
                transcription = "No response recorded";
            }

            // AUDIT-FIX: Sanitize transcription text to mitigate prompt injection via user
            // speech
            transcription = sanitizePromptInput(transcription);
            questionText = sanitizePromptInput(questionText);

            qaPairs.append(String.format("[Q%d: %s, A%d: %s]\n",
                    questionNumber, questionText, questionNumber, transcription));
            questionNumber++;
        }

        // FIX: Improved feedback prompt that instructs Ollama to return JSON only with
        // clear format
        return String.format(
                """
                        You are an expert interview coach. Analyse these interview responses and
                        provide detailed feedback.

                        Questions and Answers:
                        %s

                        Return ONLY valid JSON in this exact format, no other text:
                        {
                          "score": 75,
                          "strengths": ["strength 1", "strength 2", "strength 3"],
                          "weaknesses": ["improvement 1", "improvement 2"],
                          "recommendations": ["resource 1", "resource 2"],
                          "detailedAnalysis": "Overall performance summary here with specific observations about each answer"
                        }

                        The score must be 0-100. Include at least 2 items in each list.
                        Be specific and actionable in your feedback.
                        """,
                qaPairs.toString());
    }

    // ════════════════════════════════════════════════════════════════
    // Ollama API Call (with Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * AUDIT-FIX: Sanitize text before interpolation into LLM prompts.
     * Strips prompt injection patterns (case-insensitive) and truncates to 4000
     * chars.
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

            // FIX: Extract JSON object from response using indexOf first
            int startIndex = jsonContent.indexOf('{');
            int endIndex = jsonContent.lastIndexOf('}');
            if (startIndex >= 0 && startIndex <= endIndex) {
                jsonContent = jsonContent.substring(startIndex, endIndex + 1);
            } else {
                // FIX: Try regex extraction of JSON object as fallback
                log.warn("Response does not contain JSON object delimiters, trying regex extraction");
                Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(content);
                if (matcher.find()) {
                    jsonContent = matcher.group(); // FIX: Extract first JSON object match
                    log.info("Regex extracted JSON object of length {}", jsonContent.length());
                } else {
                    // FIX: No JSON found at all — throw so caller uses fallback feedback
                    throw new RuntimeException("No JSON object found in Ollama feedback response");
                }
            }

            jsonContent = jsonContent.trim();

            FeedbackDTO dto = objectMapper.readValue(jsonContent, FeedbackDTO.class);

            // FIX: Validate and clamp score range to 0-100
            if (dto.getScore() < 0) {
                dto.setScore(0);
            } else if (dto.getScore() > 100) {
                dto.setScore(100);
            }

            // FIX: Ensure lists are never null to prevent NPE in frontend rendering
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
            // FIX: Log the error and re-throw so the caller can use the fallback feedback
            // builder
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
