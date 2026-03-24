package com.interview.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.platform.model.TtsAudioCache;
import com.interview.platform.repository.TtsAudioCacheRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files; // FIX: Added for local audio file persistence in generateAndSaveAudio
import java.nio.file.Path; // FIX: Added for local audio file persistence in generateAndSaveAudio
import java.nio.file.Paths; // FIX: Added for local audio file persistence in generateAndSaveAudio
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Service for converting text to speech using the ElevenLabs API.
 *
 * <h3>Purpose:</h3>
 * <p>
 * Converts interview question text into natural-sounding speech audio
 * (MP3 format) using ElevenLabs' text-to-speech API. The generated audio
 * is uploaded to S3 and its S3 object key is returned. The audio is then
 * used as input to the D-ID avatar video generation pipeline.
 * </p>
 *
 * <h3>P1 Changes:</h3>
 * <ul>
 * <li><strong>Resilience4j:</strong> The manual retry loop with exponential
 * backoff has been replaced with programmatic Resilience4j {@link Retry}
 * and {@link CircuitBreaker} decorators. This provides:
 * <ul>
 * <li>Configurable retry with exponential backoff (via
 * application.properties)</li>
 * <li>Circuit breaker protection against sustained ElevenLabs outages</li>
 * <li>Observable metrics via Spring Boot Actuator</li>
 * <li>Consistent retry predicate (5xx, 429, network errors are retryable;
 * other 4xx errors are not)</li>
 * </ul>
 * </li>
 * <li><strong>S3 key return:</strong> {@link #generateSpeech} now returns
 * the S3 object key (e.g., {@code tts/question_123_1700000000.mp3})
 * instead of a presigned GET URL. Presigned URLs are generated on-demand
 * when needed (e.g., when providing the audio URL to D-ID for avatar
 * video generation).</li>
 * <li><strong>SLF4J logging:</strong> Migrated from {@code java.util.logging}
 * to SLF4J for structured, parameterized logging consistent with the
 * rest of the application.</li>
 * </ul>
 *
 * <h3>ElevenLabs API:</h3>
 * <p>
 * Uses the {@code /v1/text-to-speech/{voice_id}} endpoint. The API accepts
 * a JSON body with the text, model ID, and voice settings, and returns raw
 * MP3 audio bytes in the response body. Authentication is via the
 * {@code xi-api-key} header.
 * </p>
 *
 * <h3>Configuration Properties:</h3>
 * <ul>
 * <li>{@code elevenlabs.api.key} — API key (required, no default)</li>
 * <li>{@code elevenlabs.api.url} — base URL (default:
 * {@code https://api.elevenlabs.io/v1})</li>
 * <li>{@code elevenlabs.voice.id} — voice to use (default: Rachel)</li>
 * <li>{@code elevenlabs.model.id} — model (default:
 * {@code eleven_monolingual_v1})</li>
 * <li>{@code elevenlabs.voice.stability} — voice stability (0.0-1.0, default:
 * 0.5)</li>
 * <li>{@code elevenlabs.voice.similarity-boost} — similarity boost (0.0-1.0,
 * default: 0.75)</li>
 * </ul>
 *
 * @see AvatarVideoService
 * @see VideoStorageService
 * @see com.interview.platform.config.ResilienceConfig
 */
@Service
public class TextToSpeechService {

    private static final Logger log = LoggerFactory.getLogger(TextToSpeechService.class);

    private final RestTemplate restTemplate;
    private final VideoStorageService videoStorageService;
    private final ObjectMapper objectMapper;
    private final TtsAudioCacheRepository ttsCacheRepository;

    // Resilience4j instances for ElevenLabs API calls
    private final Retry elevenlabsRetry;
    private final CircuitBreaker elevenlabsCircuitBreaker;

    @Value("${elevenlabs.api.key}")
    private String apiKey;

    @Value("${elevenlabs.api.url:https://api.elevenlabs.io/v1}")
    private String apiBaseUrl;

    @Value("${elevenlabs.voice.id:21m00Tcm4TlvDq8ikWAM}")
    private String voiceId;

    @Value("${elevenlabs.model.id:eleven_monolingual_v1}")
    private String modelId;

    @Value("${elevenlabs.voice.stability:0.5}")
    private double stability;

    @Value("${elevenlabs.voice.similarity-boost:0.75}")
    private double similarityBoost;

    @Value("${storage.local.path:./uploads}") // FIX: Local storage path for saving TTS audio files to disk
    private String storagePath;

    public TextToSpeechService(RestTemplate restTemplate,
            VideoStorageService videoStorageService,
            ObjectMapper objectMapper,
            TtsAudioCacheRepository ttsCacheRepository,
            @Qualifier("elevenlabsRetry") Retry elevenlabsRetry,
            @Qualifier("elevenlabsCircuitBreaker") CircuitBreaker elevenlabsCircuitBreaker) {
        this.restTemplate = restTemplate;
        this.videoStorageService = videoStorageService;
        this.objectMapper = objectMapper;
        this.ttsCacheRepository = ttsCacheRepository;
        this.elevenlabsRetry = elevenlabsRetry;
        this.elevenlabsCircuitBreaker = elevenlabsCircuitBreaker;
    }

    // ════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Convert text to speech using ElevenLabs API, upload the audio to S3,
     * and return the S3 object key.
     *
     * <p>
     * The API call is wrapped with Resilience4j retry and circuit breaker.
     * On success, the raw MP3 bytes are uploaded to S3 and the S3 key is
     * returned. The caller (typically {@link AvatarVideoService}) can then
     * generate a presigned GET URL from the key when it needs to provide
     * the audio URL to D-ID.
     * </p>
     *
     * @param text       the text to convert to speech
     * @param questionId a unique identifier used for the S3 key
     * @return the S3 object key of the generated audio file
     *         (e.g., {@code tts/question_123_1700000000.mp3})
     * @throws RuntimeException if the API call fails after all retries
     *                          or the circuit breaker is open
     */
    @Cacheable(value = "ttsAudio", key = "#root.target.computeCacheKey(#text)")
    public String generateSpeech(String text, Long questionId) {
        String cacheKey = computeCacheKey(text);

        // ── Level 2: Check Database Cache ────────────────────────
        Optional<TtsAudioCache> cachedEntry = ttsCacheRepository.findByCacheKey(cacheKey);
        if (cachedEntry.isPresent()) {
            String s3Key = cachedEntry.get().getS3Key();
            log.info("TTS DB cache HIT for question {}: cacheKey={}", questionId, cacheKey);
            return s3Key;
        }

        // ── Level 3: Generate New Audio ──────────────────────────
        log.info("TTS cache MISS for question {}: textLength={} chars", questionId, text.length());

        // Call ElevenLabs with Resilience4j retry + circuit breaker
        byte[] audioData;

        // --- MOCK API FALLBACK ---
        if (apiKey != null && apiKey.startsWith("mock")) {
            log.info("Mock ElevenLabs API key detected. Returning empty byte array.");
            audioData = new byte[] { 0 }; // tiny dummy payload
        } else {
            audioData = callElevenLabsWithResilience(text);
        }
        // -------------------------

        // Upload to S3 — returns S3 key (not a presigned URL)
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String s3Key = String.format("tts/question_%d_%s.mp3", questionId, timestamp);

        String storedKey = videoStorageService.uploadBytes(audioData, s3Key, "audio/mpeg");
        log.info("Speech audio uploaded to S3: s3Key={}, question={}", storedKey, questionId);

        // Save to DB cache
        try {
            TtsAudioCache entry = new TtsAudioCache(cacheKey, storedKey);
            ttsCacheRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to save TTS cache entry: {}", e.getMessage());
        }

        return storedKey;
    }

    public String computeCacheKey(String text) {
        // SHA-256 of text + voice config
        String normalized = text != null ? text.trim().toLowerCase() : "";
        String input = String.join("|", normalized, voiceId, modelId,
                String.valueOf(stability), String.valueOf(similarityBoost));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Generate TTS audio and save it to the local filesystem, returning
     * a URL path the frontend can fetch via FileController.
     *
     * <p>
     * This method is the replacement for the D-ID avatar video pipeline.
     * Instead of generating a lip-sync video, it generates an MP3 audio
     * file via ElevenLabs TTS and saves it under the local storage
     * {@code audio/} subdirectory. The returned path (e.g.,
     * {@code audio/question_42.mp3}) is stored on the Question entity's
     * {@code audioUrl} field and served by the generic FileController
     * GET /api/files/** endpoint.
     * </p>
     *
     * <p>
     * If any exception occurs (API failure, disk I/O error, etc.), the
     * method returns {@code null} and logs a warning. This ensures the
     * interview proceeds even if audio generation fails — the frontend
     * falls back to text-only display.
     * </p>
     *
     * @param text       the question text to convert to speech
     * @param questionId the question entity ID (used in the filename)
     * @return the storage key (e.g., {@code audio/question_42.mp3}) or null on failure
     */
    public String generateAndSaveAudio(String text, Long questionId) { // FIX: New method — saves TTS audio to local disk for direct serving (replaces D-ID video pipeline)
        try {
            // Reuse the existing S3-based generation which handles caching, resilience, etc.
            // Then read the bytes back from local storage and write to the audio/ directory
            // OR call ElevenLabs directly for a simpler path:
            byte[] audioBytes; // FIX: Get raw audio bytes from ElevenLabs TTS API

            // --- MOCK API FALLBACK ---
            if (apiKey != null && apiKey.startsWith("mock")) { // FIX: Support mock API key for development/testing
                log.info("Mock ElevenLabs API key detected in generateAndSaveAudio. Using dummy audio.");
                audioBytes = new byte[] { 0 }; // tiny dummy payload
            } else {
                audioBytes = callElevenLabsWithResilience(text); // FIX: Call ElevenLabs with retry + circuit breaker
            }

            String filename = "question_" + questionId + ".mp3"; // FIX: Deterministic filename per question
            Path audioDir = Paths.get(storagePath, "audio"); // FIX: Store under {storagePath}/audio/
            Files.createDirectories(audioDir); // FIX: Auto-create audio/ subdirectory if it doesn't exist
            Path filePath = audioDir.resolve(filename); // FIX: Resolve full path for the audio file
            Files.write(filePath, audioBytes); // FIX: Write audio bytes to local filesystem

            log.info("TTS audio saved for question {}: path={}, size={} bytes", // FIX: Log successful audio save
                    questionId, filePath, audioBytes.length);

            return "audio/" + filename; // FIX: Return storage key — served by FileController GET /api/files/audio/{filename}
        } catch (Exception e) {
            log.warn("TTS audio generation failed for question {}: {}", questionId, e.getMessage()); // FIX: Graceful fallback — never propagate exception; question still works without audio
            return null; // FIX: Return null so interview proceeds with text-only fallback
        }
    }

    /**
     * Overloaded method when no specific question ID is available.
     *
     * <p>
     * Generates a synthetic ID from the text hash. This is used in edge
     * cases where TTS is called outside the normal interview pipeline.
     * </p>
     *
     * @param text the text to convert to speech
     * @return the S3 object key of the generated audio file
     */
    public String generateSpeech(String text) {
        long syntheticId = Math.abs(text.hashCode());
        return generateSpeech(text, syntheticId);
    }

    // ════════════════════════════════════════════════════════════════
    // ElevenLabs API Call (with Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * Call ElevenLabs TTS API with Resilience4j retry and circuit breaker.
     *
     * <p>
     * Replaces the previous manual retry loop. The retry configuration
     * (max attempts, backoff, retryable exceptions) is defined in
     * {@link com.interview.platform.config.ResilienceConfig} and can be
     * tuned via application.properties.
     * </p>
     *
     * <p>
     * The circuit breaker tracks the failure rate of ElevenLabs calls.
     * If the rate exceeds the configured threshold, subsequent calls fail
     * fast without making HTTP requests, reducing latency and load on
     * a degraded ElevenLabs service.
     * </p>
     *
     * @param text the text to convert to speech
     * @return the raw MP3 audio bytes
     * @throws RuntimeException if all retries are exhausted, the circuit
     *                          is open, or a non-retryable error occurs
     */
    private byte[] callElevenLabsWithResilience(String text) {
        return Decorators.ofSupplier(() -> callElevenLabs(text))
                .withRetry(elevenlabsRetry)
                .withCircuitBreaker(elevenlabsCircuitBreaker)
                .decorate()
                .get();
    }

    /**
     * Make the actual HTTP POST to the ElevenLabs text-to-speech endpoint.
     *
     * <p>
     * This is the "raw" API call without any retry or circuit breaker
     * logic. It is wrapped by {@link #callElevenLabsWithResilience} which
     * handles resilience concerns.
     * </p>
     *
     * <p>
     * The response body contains raw MP3 audio bytes (not JSON).
     * The {@code Accept: application/octet-stream} header ensures the
     * API returns binary audio data.
     * </p>
     *
     * @param text the text to convert to speech
     * @return the raw MP3 audio bytes
     * @throws RuntimeException on API errors or empty responses
     */
    private byte[] callElevenLabs(String text) {
        String url = apiBaseUrl + "/text-to-speech/" + voiceId;

        // Build request headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("xi-api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));

        // Build request body
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("text", text);
        requestBody.put("model_id", modelId);

        Map<String, Object> voiceSettings = new LinkedHashMap<>();
        voiceSettings.put("stability", stability);
        voiceSettings.put("similarity_boost", similarityBoost);
        requestBody.put("voice_settings", voiceSettings);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize TTS request body", e);
        }

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        log.debug("Calling ElevenLabs TTS: voiceId={}, modelId={}", voiceId, modelId);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, byte[].class);

        byte[] audioData = response.getBody();
        if (audioData == null || audioData.length == 0) {
            throw new RuntimeException("Empty audio response from ElevenLabs API");
        }

        log.info("Received audio data from ElevenLabs: {} bytes", audioData.length);
        return audioData;
    }
}
