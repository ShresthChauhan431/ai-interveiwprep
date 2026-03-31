package com.interview.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.platform.dto.QuestionAnswerPair;
import com.interview.platform.model.Interview;
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
import java.util.stream.Collectors;

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
        return generateQuestionsWithResilience(resume, jobRole, numQuestions, null);
    }

    /**
     * Generate interview questions based on the candidate's resume, job role, and difficulty.
     * Uses Resilience4j retry and circuit breaker.
     *
     * @param resume       the parsed candidate resume
     * @param jobRole      the target job role
     * @param numQuestions number of questions to generate
     * @param difficulty   optional difficulty level (EASY, MEDIUM, HARD, or null for mixed)
     * @return a list of generated Question entities
     */
    public List<Question> generateQuestionsWithResilience(Resume resume, JobRole jobRole, int numQuestions, String difficulty) {
        try { // FIX: Wrap with try-catch to fall back to generic questions if Ollama
              // completely fails
            return Decorators.ofSupplier(() -> generateQuestions(resume, jobRole, numQuestions, difficulty))
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

    /**
     * Generate the next adaptive interview question based on the conversation history.
     *
     * <p>This method is used in the DYNAMIC ZONE of hybrid interviews. It takes
     * into account:</p>
     * <ul>
     *   <li>The job role and its required skills</li>
     *   <li>The candidate's resume</li>
     *   <li>All previous question-answer pairs from the interview</li>
     *   <li>The current question number and total questions remaining</li>
     * </ul>
     *
     * <p>The generated question adapts to the candidate's demonstrated knowledge
     * based on their previous answers — probing deeper on weak areas or advancing
     * to more challenging topics if they're doing well.</p>
     *
     * @param interview          the current interview (contains jobRole and resume)
     * @param previousQAPairs    list of previous Q&A pairs in chronological order
     * @param nextQuestionNumber the 1-based number of the question to generate
     * @param totalQuestions     total number of questions in the interview
     * @return a Question entity with the generated question (not yet persisted)
     */
    public Question generateNextAdaptiveQuestion(
            Interview interview,
            List<QuestionAnswerPair> previousQAPairs,
            int nextQuestionNumber,
            int totalQuestions) {

        try {
            return Decorators.ofSupplier(() -> doGenerateAdaptiveQuestion(
                            interview, previousQAPairs, nextQuestionNumber, totalQuestions))
                    .withRetry(ollamaRetry)
                    .withCircuitBreaker(ollamaCircuitBreaker)
                    .decorate()
                    .get();
        } catch (Exception e) {
            log.error("Adaptive question generation failed after retries, using generic fallback: {}", e.getMessage());
            return generateGenericFallbackQuestion(
                    interview.getJobRole() != null ? interview.getJobRole().getTitle() : "General",
                    nextQuestionNumber,
                    totalQuestions);
        }
    }

    /**
     * Internal method that performs the actual adaptive question generation.
     */
    private Question doGenerateAdaptiveQuestion(
            Interview interview,
            List<QuestionAnswerPair> previousQAPairs,
            int nextQuestionNumber,
            int totalQuestions) {

        String prompt = buildAdaptiveQuestionPrompt(interview, previousQAPairs, nextQuestionNumber, totalQuestions);

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

        log.debug("Calling Ollama API for adaptive question generation (question {} of {})",
                nextQuestionNumber, totalQuestions);

        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, requestBody, String.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Empty response from Ollama API for adaptive question");
        }

        String content = extractContentFromResponse(response.getBody());
        return parseAdaptiveQuestionResponse(content, nextQuestionNumber);
    }

    /**
     * Build the prompt for adaptive question generation.
     */
    private String buildAdaptiveQuestionPrompt(
            Interview interview,
            List<QuestionAnswerPair> previousQAPairs,
            int nextQuestionNumber,
            int totalQuestions) {

        String jobTitle = interview.getJobRole() != null ? interview.getJobRole().getTitle() : "General";
        String jobDescription = interview.getJobRole() != null && interview.getJobRole().getDescription() != null
                ? interview.getJobRole().getDescription() : "";

        String resumeSummary = "";
        if (interview.getResume() != null && interview.getResume().getExtractedText() != null) {
            String rawText = interview.getResume().getExtractedText();
            resumeSummary = sanitizeResumeText(rawText);
            // Truncate for adaptive prompts — we have conversation history taking space
            if (resumeSummary.length() > 2000) {
                resumeSummary = resumeSummary.substring(0, 2000) + "...";
            }
        }

        // Build conversation history string
        String conversationHistory = previousQAPairs.stream()
                .map(qa -> String.format("Q%d: %s\nA%d: %s",
                        qa.getQuestionNumber(), qa.getQuestion(),
                        qa.getQuestionNumber(), qa.getAnswer() != null ? qa.getAnswer() : "(no answer)"))
                .collect(Collectors.joining("\n\n"));

        int questionsRemaining = totalQuestions - nextQuestionNumber + 1;

        // Get the difficulty setting from the interview
        String difficultyLevel = interview.getDifficulty();
        String difficultyInstruction;
        String enforcedDifficulty;
        
        if (difficultyLevel == null || difficultyLevel.isBlank() || difficultyLevel.equalsIgnoreCase("AUTO")) {
            difficultyInstruction = "Adapt the difficulty based on how well the candidate answered previous questions.";
            enforcedDifficulty = "MEDIUM";
        } else {
            enforcedDifficulty = difficultyLevel.toUpperCase();
            difficultyInstruction = String.format(
                "CRITICAL: This question MUST be %s difficulty. The user selected %s mode for this interview.",
                enforcedDifficulty, enforcedDifficulty
            );
        }

        return String.format(
                """
                You are a skilled technical interviewer conducting an interview for a %s position.
                
                JOB DESCRIPTION:
                %s
                
                CANDIDATE RESUME SUMMARY:
                %s
                
                INTERVIEW PROGRESS: Question %d of %d (%d questions remaining including this one)
                
                CONVERSATION SO FAR:
                %s
                
                === DIFFICULTY REQUIREMENT ===
                %s
                
                Based on the candidate's previous answers, generate the NEXT interview question.
                
                ADAPTIVE GUIDELINES:
                - If the candidate struggled with a topic, probe it differently or offer a simpler angle
                - If the candidate showed strength, advance to more challenging or deeper questions
                - Mix technical and behavioral questions appropriately
                - For the final questions, consider wrap-up or reflection questions
                - Keep questions clear, specific, and answerable in 1-2 minutes
                - ALWAYS maintain the %s difficulty level as specified above
                
                Return ONLY a valid JSON object in this exact format, no other text:
                {
                  "questionText": "Your next interview question here",
                  "category": "TECHNICAL",
                  "difficulty": "%s",
                  "rationale": "Brief explanation of why this question follows logically"
                }
                
                Categories: TECHNICAL, BEHAVIORAL, GENERAL
                Difficulty MUST be: %s
                """,
                jobTitle,
                jobDescription.isEmpty() ? "(No detailed description provided)" : jobDescription,
                resumeSummary.isEmpty() ? "(No resume provided)" : resumeSummary,
                nextQuestionNumber,
                totalQuestions,
                questionsRemaining,
                conversationHistory.isEmpty() ? "(This is the first dynamically generated question)" : conversationHistory,
                difficultyInstruction,
                enforcedDifficulty,
                enforcedDifficulty,
                enforcedDifficulty
        );
    }

    /**
     * Parse the response from Ollama for a single adaptive question.
     */
    private Question parseAdaptiveQuestionResponse(String content, int questionNumber) {
        try {
            // Try to extract JSON object
            String jsonContent = content.trim();
            
            int startIndex = jsonContent.indexOf('{');
            int endIndex = jsonContent.lastIndexOf('}');
            if (startIndex >= 0 && startIndex < endIndex) {
                jsonContent = jsonContent.substring(startIndex, endIndex + 1);
            }

            JsonNode node = objectMapper.readTree(jsonContent);

            String questionText = null;
            String category = "TECHNICAL";
            String difficulty = "MEDIUM";

            if (node.has("questionText")) {
                questionText = node.get("questionText").asText();
            } else if (node.has("question")) {
                questionText = node.get("question").asText();
            } else if (node.has("text")) {
                questionText = node.get("text").asText();
            }

            if (node.has("category")) {
                category = node.get("category").asText();
            }
            if (node.has("difficulty")) {
                difficulty = node.get("difficulty").asText();
            }

            // Log rationale if provided (for debugging/analytics)
            if (node.has("rationale")) {
                log.debug("Adaptive question rationale: {}", node.get("rationale").asText());
            }

            if (questionText == null || questionText.isBlank()) {
                log.warn("Ollama returned empty question text, using fallback");
                return generateGenericFallbackQuestion("General", questionNumber, 5);
            }

            Question question = new Question();
            question.setQuestionText(questionText);
            question.setQuestionNumber(questionNumber);
            question.setCategory(category);
            question.setDifficulty(difficulty);

            log.info("Generated adaptive question {}: {} [{}:{}]",
                    questionNumber, 
                    questionText.length() > 60 ? questionText.substring(0, 60) + "..." : questionText,
                    category, difficulty);

            return question;

        } catch (Exception e) {
            log.error("Failed to parse adaptive question response: {}", e.getMessage());
            return generateGenericFallbackQuestion("General", questionNumber, 5);
        }
    }

    /**
     * Generate a single generic fallback question when adaptive generation fails.
     */
    private Question generateGenericFallbackQuestion(String jobRole, int questionNumber, int totalQuestions) {
        log.info("Generating generic fallback question {} for role: {}", questionNumber, jobRole);

        List<String> fallbackQuestions = List.of(
                "Can you elaborate more on your experience with the technologies mentioned in your resume?",
                "Tell me about a recent project where you had to overcome a significant technical challenge.",
                "How do you stay current with industry trends and new technologies?",
                "Describe a situation where you had to work with a difficult team member. How did you handle it?",
                "What aspects of the " + jobRole + " role are you most excited about?",
                "Can you walk me through your approach to debugging a complex issue?",
                "How do you prioritize tasks when facing multiple deadlines?",
                "What's something you've learned recently that you found particularly valuable?",
                "How do you ensure code quality in your work?",
                "Do you have any questions about the role or the team?"
        );

        // Select based on question number, cycling through the list
        int index = (questionNumber - 1) % fallbackQuestions.size();
        
        Question question = new Question();
        question.setQuestionText(fallbackQuestions.get(index));
        question.setQuestionNumber(questionNumber);
        question.setCategory(index % 2 == 0 ? "TECHNICAL" : "BEHAVIORAL");
        question.setDifficulty("MEDIUM");

        return question;
    }

    private List<Question> generateQuestions(Resume resume, JobRole jobRole, int numQuestions, String difficulty) {
        String prompt = buildQuestionGenerationPrompt(resume, jobRole, numQuestions, difficulty);

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

    private String buildQuestionGenerationPrompt(Resume resume, JobRole jobRole, int numQuestions, String difficulty) {
        // P1-4: Sanitize resume text to reduce prompt injection risk
        String rawText = resume.getExtractedText() != null ? resume.getExtractedText() : "No resume content available";
        String safeText = sanitizeResumeText(rawText);

        // Normalize difficulty
        String normalizedDifficulty = (difficulty == null || difficulty.isBlank() || difficulty.equalsIgnoreCase("AUTO")) 
            ? null 
            : difficulty.toUpperCase();

        // Build difficulty instruction based on selected level
        String difficultyInstruction;
        String difficultyExamples;
        String enforcedDifficulty;
        
        if (normalizedDifficulty == null) {
            difficultyInstruction = "Mix difficulties appropriately (EASY, MEDIUM, HARD) based on the role requirements.";
            enforcedDifficulty = "MEDIUM"; // Default for JSON template
            difficultyExamples = "";
        } else {
            difficultyInstruction = String.format(
                "CRITICAL: ALL %d questions MUST be %s difficulty. Do NOT include any %s questions.",
                numQuestions, 
                normalizedDifficulty,
                normalizedDifficulty.equals("EASY") ? "MEDIUM or HARD" : 
                    (normalizedDifficulty.equals("HARD") ? "EASY or MEDIUM" : "EASY or HARD")
            );
            enforcedDifficulty = normalizedDifficulty;
            
            // Add difficulty-specific examples to guide the model
            difficultyExamples = switch (normalizedDifficulty) {
                case "EASY" -> """
                    
                    EASY questions should:
                    - Ask about basic concepts and definitions
                    - Require straightforward explanations
                    - Focus on common scenarios and simple use cases
                    - Be answerable by entry-level candidates
                    Example: "Can you explain what a REST API is and give a simple example?"
                    """;
                case "HARD" -> """
                    
                    HARD questions should:
                    - Require deep technical expertise and system design thinking
                    - Present complex scenarios with trade-offs
                    - Challenge candidates to think critically about edge cases
                    - Be suitable for senior/staff-level candidates
                    Example: "Design a distributed rate limiter that handles 1M requests/second across multiple data centers. What consistency guarantees would you choose and why?"
                    """;
                default -> """
                    
                    MEDIUM questions should:
                    - Test practical application of concepts
                    - Require some problem-solving but not expert-level knowledge
                    - Balance between theory and hands-on experience
                    - Be appropriate for mid-level candidates
                    Example: "How would you optimize a slow database query that joins multiple tables?"
                    """;
            };
        }

        // FIX: Improved prompt that strongly enforces difficulty level
        return String.format(
                """
                        You are a technical interviewer. Generate exactly %d interview questions
                        for a %s position based on this resume:

                        %s

                        === DIFFICULTY REQUIREMENTS ===
                        %s
                        %s
                        
                        === OUTPUT FORMAT ===
                        Return ONLY a valid JSON array in this exact format, no other text:
                        [
                          {
                            "questionText": "question here",
                            "category": "TECHNICAL",
                            "difficulty": "%s"
                          }
                        ]

                        RULES:
                        1. Categories must be one of: TECHNICAL, BEHAVIORAL, GENERAL
                        2. Difficulty MUST be exactly: %s (this is mandatory)
                        3. Generate exactly %d questions
                        4. Mix categories appropriately for a well-rounded interview
                        5. Each question must match the specified difficulty level
                        """,
                numQuestions,
                jobRole.getTitle(),
                safeText.substring(0, Math.min(safeText.length(), MAX_PROMPT_RESUME_LENGTH)),
                difficultyInstruction,
                difficultyExamples,
                enforcedDifficulty,
                enforcedDifficulty,
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

    /**
     * Analyze a resume and provide feedback.
     *
     * @param resumeText the extracted text from the resume
     * @return analysis result as a JSON string
     */
    public String analyzeResume(String resumeText) {
        String prompt = buildResumeAnalysisPrompt(resumeText);

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

        log.debug("Calling Ollama API for resume analysis");
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, requestBody, String.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Empty response from Ollama API");
        }

        return extractContentFromResponse(response.getBody());
    }

    private String buildResumeAnalysisPrompt(String resumeText) {
        String safeText = sanitizeResumeText(resumeText);
        
        return String.format("""
                You are a career coach and resume expert. Analyze this resume and provide detailed feedback.
                
                Resume Content:
                %s
                
                Return ONLY a valid JSON object with this exact structure, no other text:
                {
                  "score": 75,
                  "strengths": ["strength 1", "strength 2"],
                  "weaknesses": ["weakness 1", "weakness 2"],
                  "suggestions": ["suggestion 1", "suggestion 2", "suggestion 3"],
                  "overallFeedback": "2-3 sentence summary"
                }
                
                Score criteria:
                - 90-100: Exceptional, recruiter-ready
                - 70-89: Good, minor improvements needed
                - 50-69: Average, significant improvements needed
                - Below 50: Needs major revisions
                """,
                safeText.substring(0, Math.min(safeText.length(), MAX_PROMPT_RESUME_LENGTH)));
    }
}
