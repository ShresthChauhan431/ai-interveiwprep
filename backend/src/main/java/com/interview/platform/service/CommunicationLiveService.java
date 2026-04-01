package com.interview.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CommunicationLiveService {

    private static final Logger log = LoggerFactory.getLogger(CommunicationLiveService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public CommunicationLiveService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${ollama.api.url:http://localhost:11434/api/chat}")
    private String ollamaApiUrl;

    @Value("${ollama.model:llama3}")
    private String model;

    public String getInitialMessage() {
        return "Hi there! I'm your AI English conversation partner. Let's practice speaking. To get started, could you tell me a little bit about yourself or what you like to do in your free time?";
    }

    public Map<String, Object> analyzeSentence(String text) {
        log.info("Analyzing sentence for live communication: {}", text);

        if (text == null || text.trim().isEmpty()) {
            return fallbackAnalysis();
        }

        // Sanitize input to prevent prompt injection
        String sanitizedText = text.replace("\"", "\\\"").replace("\n", " ");

        String prompt = String.format("""
                Analyze the following English sentence for grammar, fluency, and clarity.
                Return ONLY valid JSON in this exact format, no other text:
                {
                  "fluencyScore": 85,
                  "grammarIssues": ["issue 1", "issue 2"],
                  "correctedSentence": "The corrected version of the sentence.",
                  "confidence": "High",
                  "suggestions": ["suggestion 1", "suggestion 2"]
                }

                Sentence to analyze: "%s"
                """, sanitizedText);

        try {
            String aiResponse = callOllama(prompt, "You are an expert English teacher. Return JSON only.", true);
            return parseJsonToMap(aiResponse);
        } catch (Exception e) {
            log.error("Failed to analyze sentence with Ollama: {}", e.getMessage());
            return fallbackAnalysis();
        }
    }

    public String getNextMessage(List<Map<String, String>> history) {
        log.info("Generating next conversation message. History size: {}", history.size());

        StringBuilder conversationBuilder = new StringBuilder();
        // Limit context to last 6 messages to keep it fast and avoid context bloat
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            Map<String, String> msg = history.get(i);
            String role = "user".equals(msg.get("role")) ? "User" : "AI";
            String safeContent = msg.getOrDefault("content", "").replace("\"", "\\\"").replace("\n", " ");
            conversationBuilder.append(role).append(": ").append(safeContent).append("\n");
        }

        String prompt = String.format("""
                Here is the recent conversation:
                %s

                Respond to the User's last message to keep the conversation going.
                Rules:
                - MUST respond in English only.
                - Keep it natural, conversational, and friendly (like a human).
                - Keep the response short (1-2 sentences maximum).
                - Ask a follow-up question if appropriate to keep them talking.
                - Do not include any prefix like 'AI:' or quotes. Just the raw response text.
                """, conversationBuilder.toString());

        try {
            return callOllama(prompt, "You are a friendly and natural conversational English practice partner.", false);
        } catch (Exception e) {
            log.error("Failed to get next message from Ollama: {}", e.getMessage());
            return "That's interesting! Can you tell me more about that?";
        }
    }

    public Map<String, Object> analyzeOverall(List<Map<String, String>> history) {
        log.info("Analyzing overall conversation. History size: {}", history.size());

        if (history == null || history.isEmpty()) {
            return fallbackOverallAnalysis();
        }

        StringBuilder conversationBuilder = new StringBuilder();
        for (Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "User" : "AI";
            String safeContent = msg.getOrDefault("content", "").replace("\"", "\\\"").replace("\n", " ");
            conversationBuilder.append(role).append(": ").append(safeContent).append("\n");
        }

        String prompt = String.format("""
                Analyze the following conversation between an AI and a User for the User's English communication skills.
                Provide a collective feedback of the complete conversation.
                Identify areas needing improvement, common weak points, and how to overcome them.

                Return ONLY valid JSON in this exact format, no other text:
                {
                  "overallScore": 80,
                  "overallFeedback": "Your overall communication is good but needs work on past tense verbs.",
                  "weakPoints": ["Mixing past and present tense", "Using filler words like 'um'"],
                  "improvementTips": ["Read more English books", "Practice speaking slowly"]
                }

                Conversation:
                %s
                """, conversationBuilder.toString());

        try {
            String aiResponse = callOllama(prompt, "You are an expert English communication coach. Return JSON only.", true);
            return parseJsonToMap(aiResponse);
        } catch (Exception e) {
            log.error("Failed to analyze overall conversation with Ollama: {}", e.getMessage());
            return fallbackOverallAnalysis();
        }
    }

    private Map<String, Object> fallbackOverallAnalysis() {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("overallScore", 75);
        fallback.put("overallFeedback", "Good effort! Keep practicing to improve your fluency.");
        fallback.put("weakPoints", List.of("Fluency could be better."));
        fallback.put("improvementTips", List.of("Practice speaking every day.", "Listen to native speakers."));
        return fallback;
    }

    private String callOllama(String prompt, String systemInstruction, boolean expectJson) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("stream", false);
        if (expectJson) {
            requestBody.put("format", "json");
        }

        // Latency optimization: limit response length and lower temperature
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_predict", expectJson ? 200 : 100);  // shorter for conversation responses
        options.put("temperature", 0.7);
        requestBody.put("options", options);

        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemInstruction);
        messages.add(systemMessage);

        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        requestBody.put("messages", messages);

        ResponseEntity<String> response = restTemplate.postForEntity(ollamaApiUrl, requestBody, String.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Empty response from Ollama API");
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode messageNode = root.path("message");
            if (messageNode.isMissingNode() || !messageNode.has("content")) {
                throw new RuntimeException("Unexpected JSON structure returned from Ollama API");
            }
            return messageNode.get("content").asText().trim();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse Ollama JSON response", e);
        }
    }

    private Map<String, Object> parseJsonToMap(String content) throws JsonProcessingException {
        int startIndex = content.indexOf('{');
        int endIndex = content.lastIndexOf('}');

        if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            String jsonContent = content.substring(startIndex, endIndex + 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(jsonContent, Map.class);
            return map;
        }
        throw new RuntimeException("No valid JSON boundaries found in content");
    }

    private Map<String, Object> fallbackAnalysis() {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("fluencyScore", 70);
        fallback.put("grammarIssues", List.of("Could not fully analyze grammar automatically."));
        fallback.put("correctedSentence", "N/A");
        fallback.put("confidence", "Medium");
        fallback.put("suggestions", List.of("Try speaking clearly into the microphone.", "Practice makes perfect!"));
        return fallback;
    }
}
