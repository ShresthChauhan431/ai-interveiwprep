package com.interview.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.platform.model.JobRole;
import com.interview.platform.model.Question;
import com.interview.platform.model.Resume;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import jakarta.annotation.PostConstruct; // FIX: Import for startup Ollama health check
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher; // FIX: Import for regex JSON extraction fallback
import java.util.regex.Pattern; // FIX: Import for regex JSON extraction fallback

/**
 * Service for interacting with the local Ollama LLM API to generate questions.
 */
@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    // Issue 16: Constants for text length limits (standardized across the application)
    // Resume storage limit (in ResumeService)
    private static final int MAX_RESUME_STORAGE_LENGTH = 15_000;
    // LLM prompt limit (used in this service)
    private static final int MAX_PROMPT_RESUME_LENGTH = 4_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Retry ollamaRetry;
    private final CircuitBreaker ollamaCircuitBreaker;

    @Value("${ollama.api.url:http://localhost:11434/api/chat}")
    private String apiUrl;

    @Value("${ollama.model:llama3}")
    private String model;

    // FIX: Derived base URL for health check — extracted from chat API URL at
    // startup
    private String ollamaBaseUrl;

    public OllamaService(RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Qualifier("ollamaRetry") Retry ollamaRetry,
            @Qualifier("ollamaCircuitBreaker") CircuitBreaker ollamaCircuitBreaker) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.ollamaRetry = ollamaRetry;
        this.ollamaCircuitBreaker = ollamaCircuitBreaker;
    }

    /**
     * FIX: Startup health check — verify Ollama is reachable before any user
     * request.
     *
     * <p>
     * Without this check, a stopped/misconfigured Ollama instance is only
     * discovered when a user clicks "Start Interview" and waits 30–120 seconds
     * for a cryptic timeout error. This {@code @PostConstruct} method logs a
     * clear warning at application startup so operators can fix it immediately.
     * </p>
     */
    @PostConstruct // FIX: Run once at startup to detect unreachable Ollama early
    public void checkOllamaConnection() {
        // FIX: Derive the base URL from the configured chat endpoint (strip /api/chat
        // suffix)
        if (apiUrl != null && apiUrl.contains("/api/")) {
            ollamaBaseUrl = apiUrl.substring(0, apiUrl.indexOf("/api/"));
        } else {
            ollamaBaseUrl = "http://localhost:11434"; // FIX: Fallback if apiUrl has unexpected format
        }

        try {
            // FIX: GET /api/tags is Ollama's lightweight model listing endpoint — fast and
            // read-only
            ResponseEntity<String> response = restTemplate.getForEntity(
                    ollamaBaseUrl + "/api/tags", String.class);
            log.info("Ollama is running and reachable at {} (status={})",
                    ollamaBaseUrl, response.getStatusCode()); // FIX: Log success with status code
        } catch (Exception e) {
            // FIX: Warn loudly but don't crash — Ollama may start later, or be on a
            // different host
            log.warn("Ollama is NOT reachable at {}. Question generation will fail. " +
                    "Run 'ollama serve' to start it. Error: {}",
                    ollamaBaseUrl, e.getMessage()); // FIX: Actionable message with the actual error
        }
    }

    /**
     * Generate interview questions based on the candidate's resume and job role.
     * Uses Resilience4j retry and circuit breaker.
     * FIX: Falls back to generic questions if Ollama fails after all retries.
     *
     * @param resume       the parsed candidate resume
     * @param jobRole      the target job role
     * @param numQuestions number of questions to generate
     * @return a list of generated Question entities
     */
    public List<Question> generateQuestionsWithResilience(Resume resume, JobRole jobRole, int numQuestions) {
        try { // FIX: Wrap with try-catch to fall back to generic questions if Ollama
              // completely fails
            return Decorators.ofSupplier(() -> generateQuestions(resume, jobRole, numQuestions))
                    .withRetry(ollamaRetry)
                    .withCircuitBreaker(ollamaCircuitBreaker)
                    .decorate()
                    .get();
        } catch (Exception e) {
            // FIX: If Ollama fails after all retries, generate generic questions so the
            // interview can still proceed
            log.error("Ollama question generation failed after retries, using generic fallback: {}", e.getMessage());
            return generateGenericQuestions(jobRole.getTitle(), numQuestions);
        }
    }

    private List<Question> generateQuestions(Resume resume, JobRole jobRole, int numQuestions) {
        String prompt = buildQuestionGenerationPrompt(resume, jobRole, numQuestions);

        // Required Ollama format constraint
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("format", "json");
        requestBody.put("stream", false);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        requestBody.put("messages", messages);

        log.debug("Calling Ollama API for question generation");
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, requestBody, String.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Empty response from Ollama API");
        }

        String content = extractContentFromResponse(response.getBody());
        return parseQuestionsResponse(content, numQuestions);
    }

    private String buildQuestionGenerationPrompt(Resume resume, JobRole jobRole, int numQuestions) {
        // P1-4: Sanitize resume text to reduce prompt injection risk
        String rawText = resume.getExtractedText() != null ? resume.getExtractedText() : "No resume content available";
        String safeText = sanitizeResumeText(rawText);

        // FIX: Improved prompt that instructs Ollama to return ONLY a JSON array,
        // nothing else
        return String.format(
                """
                        You are a technical interviewer. Generate exactly %d interview questions
                        for a %s position based on this resume:

                        %s

                        Return ONLY a valid JSON array in this exact format, no other text:
                        [
                          {
                            "questionText": "question here",
                            "category": "TECHNICAL",
                            "difficulty": "MEDIUM"
                          }
                        ]

                        Categories must be one of: TECHNICAL, BEHAVIORAL, GENERAL
                        Difficulties must be one of: EASY, MEDIUM, HARD
                        Generate exactly %d questions. Mix categories and difficulties.
                        """,
                numQuestions,
                jobRole.getTitle(),
                safeText.substring(0, Math.min(safeText.length(), MAX_PROMPT_RESUME_LENGTH)), // Issue 16: Use standardized constant
                numQuestions);
    }

    /**
     * Sanitize resume text to mitigate prompt injection attacks (P1-4).
     *
     * <ul>
     * <li>Truncates to 4,000 characters to prevent context window exhaustion</li>
     * <li>Strips delimiter-like sequences that could escape the resume block</li>
     * <li>Removes common prompt injection patterns (case-insensitive)</li>
     * </ul>
     *
     * AUDIT-FIX: Strengthened sanitization — added explicit prompt injection
     * pattern
     * stripping ("ignore previous instructions", "system:", "###", "<|im_start|>",
     * etc.)
     * and reduced max length from 10,000 to 4,000 characters per audit requirement.
     *
     * @param text the raw extracted resume text
     * @return sanitized text safe for prompt interpolation
     */
    private String sanitizeResumeText(String text) {
        if (text == null || text.isBlank()) {
            return "No resume content available";
        }

        // Issue 16: Use standardized constant for truncation
        String sanitized = text.length() > MAX_PROMPT_RESUME_LENGTH 
            ? text.substring(0, MAX_PROMPT_RESUME_LENGTH) 
            : text;

        // Strip delimiter escape sequences that could break out of the resume block
        sanitized = sanitized.replace("<RESUME_BEGIN>", "")
                .replace("<RESUME_END>", "")
                .replace("```", "")
                .replace("<|", "")
                .replace("|>", "");

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
                .replaceAll("(?i)new\\s+instructions?\\s*:", "");

        return sanitized;
    }

    private String extractContentFromResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("message");

            if (message.isMissingNode() || !message.has("content")) {
                throw new RuntimeException("Unexpected JSON structure returned from Ollama API");
            }

            return message.get("content").asText().trim();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Ollama response JSON", e);
            throw new RuntimeException("Failed to parse Ollama API response", e);
        }
    }

    private List<Question> parseQuestionsResponse(String content, int expectedNumQuestions) {
        try {
            String jsonContent = content;

            // FIX: Try to extract JSON array from response using regex if direct parse
            // fails
            int startIndex = jsonContent.indexOf('[');
            int endIndex = jsonContent.lastIndexOf(']');
            if (startIndex >= 0 && startIndex <= endIndex) {
                jsonContent = jsonContent.substring(startIndex, endIndex + 1);
            } else {
                // FIX: Try regex extraction as fallback for wrapped responses
                log.warn("Response does not contain a JSON array delimiter, trying regex extraction");
                Matcher matcher = Pattern.compile("\\[.*\\]", Pattern.DOTALL).matcher(content);
                if (matcher.find()) {
                    jsonContent = matcher.group();
                    log.info("Regex extracted JSON array of length {}", jsonContent.length());
                } else {
                    // FIX: No JSON array found at all — fall back to generic questions
                    log.warn("No JSON array found in Ollama response, falling back to generic questions. Response: {}",
                            content.length() > 500 ? content.substring(0, 500) + "..." : content);
                    return generateGenericQuestions("General", expectedNumQuestions);
                }
            }

            jsonContent = jsonContent.trim();

            JsonNode node;
            try {
                node = objectMapper.readTree(jsonContent);
            } catch (JsonProcessingException parseEx) {
                // FIX: JSON parse failed — fall back to generic questions instead of crashing
                log.warn("Failed to parse extracted JSON, falling back to generic questions: {}", parseEx.getMessage());
                return generateGenericQuestions("General", expectedNumQuestions);
            }

            List<Question> questions = new ArrayList<>();
            int sequenceCounter = 1;

            // Ollama sometimes returns {"questions": [...]} instead of a bare [...]
            if (node.isObject()) {
                log.info("Ollama returned a JSON object instead of a bare array, extracting array field");
                JsonNode arrayField = null;
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (entry.getValue().isArray()) {
                        arrayField = entry.getValue();
                        log.info("Found array in field '{}' with {} elements", entry.getKey(), arrayField.size());
                        break;
                    }
                }
                if (arrayField != null) {
                    node = arrayField;
                } else {
                    log.info("No array field found, extracting object keys as questions");
                    var keyFields = node.fields();
                    while (keyFields.hasNext()) {
                        var entry = keyFields.next();
                        String key = entry.getKey().trim();
                        if (!key.isEmpty()) {
                            Question q = new Question();
                            q.setQuestionText(key);
                            q.setQuestionNumber(sequenceCounter++);
                            questions.add(q);
                        }
                    }
                    if (!questions.isEmpty()) {
                        if (questions.size() == 1 && expectedNumQuestions > 1
                                && questions.get(0).getQuestionText().contains("?")) {
                            log.info("Single key contains multiple questions, splitting by '?'");
                            String combined = questions.get(0).getQuestionText();
                            String[] parts = combined.split("\\?");
                            questions.clear();
                            sequenceCounter = 1;
                            for (String part : parts) {
                                String trimmed = part.trim();
                                if (!trimmed.isEmpty()) {
                                    Question q = new Question();
                                    q.setQuestionText(trimmed + "?");
                                    q.setQuestionNumber(sequenceCounter++);
                                    questions.add(q);
                                }
                            }
                        }
                        log.info("Extracted {} questions from object keys", questions.size());
                        if (questions.size() != expectedNumQuestions) {
                            log.warn("Expected {} questions, but Ollama generated {}.", expectedNumQuestions,
                                    questions.size());
                            if (questions.size() > expectedNumQuestions) {
                                questions = questions.subList(0, expectedNumQuestions);
                            }
                        }
                        return questions;
                    }
                    // FIX: Fall back to generic questions instead of throwing
                    log.warn("Ollama returned unusable JSON object, falling back to generic questions");
                    return generateGenericQuestions("General", expectedNumQuestions);
                }
            }

            if (node.isArray()) {
                for (JsonNode qNode : node) {
                    // FIX: Handle string elements, object elements with questionText/question/text
                    // fields
                    String questionText;
                    String category = null; // FIX: Extract category from structured response
                    String difficulty = null; // FIX: Extract difficulty from structured response

                    if (qNode.isTextual()) {
                        questionText = qNode.asText();
                    } else if (qNode.isObject()) {
                        // FIX: Try multiple field names for the question text
                        questionText = qNode.has("questionText") ? qNode.get("questionText").asText()
                                : qNode.has("question") ? qNode.get("question").asText()
                                        : qNode.has("text") ? qNode.get("text").asText()
                                                : qNode.toString();
                        // FIX: Extract category and difficulty if present in structured response
                        if (qNode.has("category")) {
                            category = qNode.get("category").asText();
                        }
                        if (qNode.has("difficulty")) {
                            difficulty = qNode.get("difficulty").asText();
                        }
                    } else {
                        questionText = qNode.toString();
                    }

                    Question q = new Question();
                    q.setQuestionText(questionText);
                    q.setQuestionNumber(sequenceCounter++);
                    if (category != null)
                        q.setCategory(category); // FIX: Set category from Ollama response
                    if (difficulty != null)
                        q.setDifficulty(difficulty); // FIX: Set difficulty from Ollama response
                    questions.add(q);
                }
            } else {
                // FIX: Fall back to generic questions instead of throwing
                log.warn("Ollama response was neither array nor object, falling back to generic questions");
                return generateGenericQuestions("General", expectedNumQuestions);
            }

            if (questions.isEmpty()) {
                // FIX: Empty array from Ollama — use generic fallback
                log.warn("Ollama returned empty array, falling back to generic questions");
                return generateGenericQuestions("General", expectedNumQuestions);
            }

            if (questions.size() != expectedNumQuestions) {
                log.warn("Expected {} questions, but Ollama generated {}.", expectedNumQuestions, questions.size());
                if (questions.size() > expectedNumQuestions) {
                    questions = questions.subList(0, expectedNumQuestions);
                }
            }

            return questions;

        } catch (Exception e) {
            // FIX: Any unexpected error — fall back to generic questions instead of
            // crashing the interview
            log.error("Failed to parse questions from Ollama response, using generic fallback: {}", e.getMessage());
            return generateGenericQuestions("General", expectedNumQuestions);
        }
    }

    /**
     * FIX: Generate generic interview questions when Ollama fails or returns
     * invalid output.
     * Ensures the interview always starts even if the AI model is unavailable.
     *
     * @param jobRole      the job role title for context
     * @param numQuestions number of questions to generate
     * @return a list of hardcoded but reasonable Question entities
     */
    private List<Question> generateGenericQuestions(String jobRole, int numQuestions) {
        log.info("Generating {} generic fallback questions for role: {}", numQuestions, jobRole); // FIX: Log fallback
                                                                                                  // usage

        List<String> genericQuestions = List.of(
                "Tell me about yourself and your experience relevant to the " + jobRole + " role.", // FIX: Generic
                                                                                                    // intro question
                "What are your greatest technical strengths and how have you applied them in past projects?", // FIX:
                                                                                                              // Technical
                                                                                                              // strengths
                "Describe a challenging project you worked on. What was your role and what was the outcome?", // FIX:
                                                                                                              // Behavioral
                                                                                                              // -
                                                                                                              // challenge
                "How do you approach debugging a complex issue in a production system?", // FIX: Technical
                                                                                         // problem-solving
                "Tell me about a time you had to learn a new technology quickly. How did you approach it?", // FIX:
                                                                                                            // Adaptability
                "How do you prioritize tasks when working on multiple projects simultaneously?", // FIX: Time management
                "Describe your experience working in a team environment. How do you handle disagreements?", // FIX:
                                                                                                            // Teamwork
                "What is your approach to writing clean, maintainable code?", // FIX: Code quality
                "Where do you see yourself professionally in the next 3-5 years?", // FIX: Career goals
                "Do you have any questions about the " + jobRole + " position or the company?" // FIX: Closing question
        );

        List<Question> questions = new ArrayList<>();
        String[] categories = { "GENERAL", "TECHNICAL", "BEHAVIORAL", "TECHNICAL", "BEHAVIORAL",
                "GENERAL", "BEHAVIORAL", "TECHNICAL", "GENERAL", "GENERAL" }; // FIX: Varied categories
        String[] difficulties = { "EASY", "MEDIUM", "MEDIUM", "HARD", "MEDIUM",
                "MEDIUM", "MEDIUM", "HARD", "EASY", "EASY" }; // FIX: Varied difficulties

        int count = Math.min(numQuestions, genericQuestions.size());
        for (int i = 0; i < count; i++) {
            Question q = new Question();
            q.setQuestionText(genericQuestions.get(i)); // FIX: Set hardcoded question text
            q.setQuestionNumber(i + 1);
            q.setCategory(categories[i % categories.length]); // FIX: Assign category from rotation
            q.setDifficulty(difficulties[i % difficulties.length]); // FIX: Assign difficulty from rotation
            questions.add(q);
        }

        return questions;
    }
}
