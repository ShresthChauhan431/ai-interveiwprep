package com.interview.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Service for generating AI avatar videos using the D-ID API.
 *
 * <h3>Pipeline:</h3>
 * <ol>
 * <li>Convert question text to speech audio via {@link TextToSpeechService}
 * (ElevenLabs)</li>
 * <li>Submit a "talk" job to D-ID with the audio URL and avatar image</li>
 * <li>Poll D-ID until the video is ready</li>
 * <li>Download the video and upload to S3</li>
 * </ol>
 *
 * <h3>P1 Changes:</h3>
 * <ul>
 * <li><strong>Resilience4j:</strong> The manual retry loops for the D-ID
 * {@code createTalk} API call have been replaced with programmatic
 * Resilience4j {@link Retry} and {@link CircuitBreaker} decorators.
 * This provides exponential backoff, circuit breaker protection against
 * sustained D-ID outages, and observable metrics via the actuator.</li>
 * <li><strong>S3 key storage:</strong> Upload methods now return S3 object
 * keys instead of presigned GET URLs. Presigned URLs are generated
 * on-demand when building DTOs for the frontend.</li>
 * <li><strong>SLF4J logging:</strong> Migrated from {@code java.util.logging}
 * to SLF4J for structured, consistent logging across the application.</li>
 * <li><strong>Polling loops unchanged:</strong> The D-ID poll loop is NOT
 * wrapped with Resilience4j retry because polling is checking async job
 * status, not retrying a failed operation. The poll loop has its own
 * timeout (MAX_POLL_RETRIES * POLL_INTERVAL_MS).</li>
 * </ul>
 *
 * <h3>Thread Model:</h3>
 * <p>
 * The {@link #generateAvatarVideoAsync} method runs on a virtual thread
 * via {@code @Async("avatarTaskExecutor")}. The blocking {@code Thread.sleep()}
 * calls in the polling loop yield the virtual thread's carrier thread back to
 * the pool, so thousands of concurrent polls do not exhaust platform threads.
 * </p>
 *
 * @see TextToSpeechService
 * @see VideoStorageService
 * @see com.interview.platform.config.ResilienceConfig
 */
@Service
@Deprecated // FIX: D-ID avatar generation replaced by ElevenLabs TTS audio
public class AvatarVideoService {

    private static final Logger log = LoggerFactory.getLogger(AvatarVideoService.class);

    /**
     * Maximum number of poll attempts for D-ID video completion.
     * At 3-second intervals, 60 attempts = 3 minutes maximum wait.
     */
    private static final int MAX_POLL_RETRIES = 60;

    /**
     * Interval between D-ID poll requests in milliseconds.
     */
    private static final long POLL_INTERVAL_MS = 3000;

    private final RestTemplate restTemplate;
    private final TextToSpeechService textToSpeechService;
    private final VideoStorageService videoStorageService;
    private final ObjectMapper objectMapper;

    // Resilience4j instances for D-ID API calls
    private final Retry didRetry;
    private final CircuitBreaker didCircuitBreaker;

    @Value("${did.api.key}")
    private String apiKey;

    @Value("${did.api.url:https://api.d-id.com}")
    private String apiBaseUrl;

    @Value("${did.avatar.image.url}")
    private String avatarImageUrl;

    public AvatarVideoService(RestTemplate restTemplate,
            TextToSpeechService textToSpeechService,
            VideoStorageService videoStorageService,
            ObjectMapper objectMapper,
            @Qualifier("didRetry") Retry didRetry,
            @Qualifier("didCircuitBreaker") CircuitBreaker didCircuitBreaker) {
        this.restTemplate = restTemplate;
        this.textToSpeechService = textToSpeechService;
        this.videoStorageService = videoStorageService;
        this.objectMapper = objectMapper;
        this.didRetry = didRetry;
        this.didCircuitBreaker = didCircuitBreaker;
    }

    // ════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate an AI avatar video for a question (synchronous).
     *
     * <p>
     * Full pipeline: TTS → D-ID createTalk → poll → download → S3 upload.
     * </p>
     *
     * @param questionText the interview question text
     * @param questionId   unique identifier for S3 key naming
     * @return the S3 object key of the generated avatar video (NOT a presigned URL)
     */
    public String generateAvatarVideo(String questionText, Long questionId) {
        log.info("Starting avatar video generation for question {}", questionId);

        // Step 1: Generate speech audio via ElevenLabs
        // TextToSpeechService returns an S3 key (P1 migration)
        String audioS3Key = textToSpeechService.generateSpeech(questionText, questionId);
        log.info("Speech audio generated for question {}: s3Key={}", questionId, audioS3Key);

        // Step 2: Generate a presigned GET URL for the audio so D-ID can access it.
        // D-ID needs a publicly accessible URL, not an S3 key.
        // We generate a presigned URL with 30-minute validity (enough for D-ID to
        // process).
        String audioUrl = videoStorageService.generatePresignedGetUrl(audioS3Key, 30);
        log.debug("Generated presigned audio URL for D-ID: question={}", questionId);

        // Step 3: Create D-ID talk (with Resilience4j retry + circuit breaker)
        String talkId = createTalkWithResilience(audioUrl);
        log.info("D-ID talk created: talkId={}, question={}", talkId, questionId);

        // Step 4: Poll for video completion (manual polling, NOT Resilience4j retry)
        String resultUrl = pollForVideoCompletion(talkId);
        log.info("D-ID video ready: talkId={}, question={}", talkId, questionId);

        // Step 5: Download video and upload to S3 (returns S3 key)
        String s3Key = downloadAndUploadToS3(resultUrl, questionId);
        log.info("Avatar video uploaded to S3: s3Key={}, question={}", s3Key, questionId);

        return s3Key;
    }

    /**
     * Async version of {@link #generateAvatarVideo} for non-blocking operation.
     *
     * <p>
     * Runs on a virtual thread via the {@code avatarTaskExecutor}.
     * The returned {@link CompletableFuture} completes with the S3 key
     * on success, or completes exceptionally on failure.
     * </p>
     *
     * @param questionText the interview question text
     * @param questionId   unique identifier for S3 key naming
     * @return a future containing the S3 object key of the avatar video
     */
    @Async("avatarTaskExecutor")
    public CompletableFuture<String> generateAvatarVideoAsync(String questionText, Long questionId) {
        try {
            String s3Key = generateAvatarVideo(questionText, questionId);
            return CompletableFuture.completedFuture(s3Key);
        } catch (Exception e) {
            log.error("Async avatar video generation failed for question {}: {}",
                    questionId, e.getMessage(), e);
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // D-ID API: Create Talk (with Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * Call D-ID /talks endpoint with Resilience4j retry and circuit breaker.
     *
     * <p>
     * Replaces the previous manual retry loop. The retry config is defined
     * in {@link com.interview.platform.config.ResilienceConfig} with:
     * </p>
     * <ul>
     * <li>Exponential backoff (1s → 2s → 4s)</li>
     * <li>Max 3 attempts</li>
     * <li>Retries on 5xx, 429, and network errors only</li>
     * </ul>
     *
     * <p>
     * The circuit breaker tracks the failure rate of D-ID API calls.
     * If the failure rate exceeds the threshold (default 50% over 10 calls),
     * the circuit opens and subsequent calls fail fast with
     * {@code CallNotPermittedException} for 60 seconds. This prevents
     * hammering a degraded D-ID service and allows faster fallback to
     * text-only questions.
     * </p>
     *
     * @param audioUrl the presigned URL of the speech audio file
     * @return the D-ID talk ID
     * @throws RuntimeException if all retries are exhausted or circuit is open
     */
    private String createTalkWithResilience(String audioUrl) {
        // --- MOCK API FALLBACK ---
        if (apiKey != null && apiKey.startsWith("mock")) {
            log.info("Mock D-ID API key detected. Returning dummy talk ID.");
            return "mock-talk-id-" + System.currentTimeMillis();
        }
        // -------------------------

        return Decorators.ofSupplier(() -> callCreateTalk(audioUrl))
                .withRetry(didRetry)
                .withCircuitBreaker(didCircuitBreaker)
                .decorate()
                .get();
    }

    /**
     * Make the actual HTTP POST to D-ID /talks endpoint.
     *
     * <p>
     * This method is the "raw" API call without any retry or circuit breaker
     * logic. It is wrapped by {@link #createTalkWithResilience} which handles
     * resilience concerns.
     * </p>
     *
     * @param audioUrl the URL of the speech audio file (presigned S3 URL)
     * @return the talk ID from D-ID's response
     * @throws RuntimeException on API errors or parse failures
     */
    private String callCreateTalk(String audioUrl) {
        String url = apiBaseUrl + "/talks";

        HttpHeaders headers = buildDIDHeaders();

        // Build request body per D-ID API spec
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("type", "audio");
        script.put("audio_url", audioUrl);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("fluent", true);
        config.put("pad_audio", 0.5);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("script", script);
        requestBody.put("source_url", avatarImageUrl);
        requestBody.put("config", config);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize D-ID request body", e);
        }

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        log.debug("Calling D-ID /talks: avatarImage={}", avatarImageUrl);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String talkId = root.path("id").asText();
            if (talkId == null || talkId.isBlank()) {
                throw new RuntimeException("No talk ID returned from D-ID API");
            }
            return talkId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse D-ID create talk response", e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // D-ID API: Poll Status (manual polling — NOT Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * Poll D-ID /talks/{talkId} endpoint until the video is ready.
     *
     * <p>
     * <strong>This is NOT wrapped with Resilience4j retry.</strong>
     * Polling for async job completion is fundamentally different from
     * retrying a failed API call. A "created" or "started" status is
     * not a failure — it means the job is still processing. Wrapping
     * this with Retry would incorrectly treat "not ready yet" as a
     * failure and exhaust retry attempts.
     * </p>
     *
     * <p>
     * The polling loop has its own timeout:
     * {@code MAX_POLL_RETRIES * POLL_INTERVAL_MS = 60 * 3000ms = 3 minutes}.
     * </p>
     *
     * <p>
     * Individual poll HTTP requests are lightweight GET calls. If a single
     * poll request fails with a transient error, the loop simply continues
     * to the next iteration (with a sleep interval in between), effectively
     * providing implicit retry behavior.
     * </p>
     *
     * @param talkId the D-ID talk ID to check
     * @return the result_url of the completed video
     * @throws RuntimeException if the video generation fails or times out
     */
    private String pollForVideoCompletion(String talkId) {
        // --- MOCK API FALLBACK ---
        if (apiKey != null && apiKey.startsWith("mock") && talkId.startsWith("mock-talk-id")) {
            log.info("Mock D-ID API key detected. Simulating immediate video completion.");
            return "https://mock-did-result-url.com/dummy.mp4";
        }
        // -------------------------

        String url = apiBaseUrl + "/talks/" + talkId;
        HttpHeaders headers = buildDIDHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (int attempt = 0; attempt < MAX_POLL_RETRIES; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, entity, String.class);

                JsonNode root = objectMapper.readTree(response.getBody());
                String status = root.path("status").asText();

                switch (status) {
                    case "done":
                        String resultUrl = root.path("result_url").asText();
                        if (resultUrl == null || resultUrl.isBlank()) {
                            throw new RuntimeException("D-ID video completed but result_url is empty");
                        }
                        log.info("D-ID talk {} completed after {} polls", talkId, attempt + 1);
                        return resultUrl;

                    case "error":
                        String errorMessage = root.path("error").path("description").asText("Unknown error");
                        throw new RuntimeException("D-ID video generation failed: " + errorMessage);

                    case "created":
                    case "started":
                        log.debug("D-ID talk {} status: {} (poll {}/{})",
                                talkId, status, attempt + 1, MAX_POLL_RETRIES);
                        break;

                    default:
                        log.warn("D-ID talk {} unexpected status: {}", talkId, status);
                        break;
                }

            } catch (HttpClientErrorException e) {
                log.error("D-ID poll error for talk {}: {} {}", talkId,
                        e.getStatusCode(), e.getMessage());
                throw new RuntimeException("Failed to poll D-ID talk status: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                // Re-throw our own error cases (D-ID failures)
                if (e.getMessage() != null && e.getMessage().startsWith("D-ID video")) {
                    throw e;
                }
                // Transient errors during polling — log and continue
                log.warn("Error polling D-ID talk {}: {}", talkId, e.getMessage());
            } catch (Exception e) {
                log.warn("Error parsing D-ID poll response for talk {}: {}", talkId, e.getMessage());
            }

            sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException("D-ID video generation timed out after "
                + (MAX_POLL_RETRIES * POLL_INTERVAL_MS / 1000) + " seconds for talk: " + talkId);
    }

    // ════════════════════════════════════════════════════════════════
    // Download + Upload to S3
    // ════════════════════════════════════════════════════════════════

    /**
     * Download the video from D-ID result URL and upload to S3.
     *
     * <p>
     * The D-ID result URL is a temporary URL that expires. We download
     * the video bytes and upload them to our S3 bucket for permanent storage.
     * </p>
     *
     * @param videoUrl   the D-ID result URL (temporary, provided by D-ID)
     * @param questionId the question ID for S3 key naming
     * @return the S3 object key of the uploaded video (NOT a presigned URL)
     */
    private String downloadAndUploadToS3(String videoUrl, Long questionId) {
        try {
            byte[] videoData;
            // --- MOCK API FALLBACK ---
            if (apiKey != null && apiKey.startsWith("mock") && videoUrl.contains("mock-did-result-url")) {
                log.info("Mock D-ID API key detected. Simulating downloaded dummy video payload.");
                // A real minimal valid MP4 (ftyp + moov + mdat) that browsers
                // and ReactPlayer can actually decode and fire onEnded for.
                // Generated from a 1-frame silent 1×1 px H.264 MP4.
                String dummyMp4Base64 =
                    "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAAo9tZGF0AAACrgYF" +
                    "//+q3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE1MiByMjg1NCBlOWE1OTAzIC0g" +
                    "SC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAxNyAtIGh0dHA6" +
                    "Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVm" +
                    "PTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBw" +
                    "c3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9t" +
                    "YV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0" +
                    "X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTMgbG9va2FoZWFkX3Ro" +
                    "cmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2Vk" +
                    "PTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJf" +
                    "cHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Bl" +
                    "bl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTI1IHNjZW5lY3V0" +
                    "PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0x" +
                    "IGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9y" +
                    "YXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAD2WIhAA3//728P4FNjuZQAAAwAAAAwEH/BXu" +
                    "AAAAAAAAABgAAAAMAAAABgAAAAwAAAAgAAAADAAAABgAAAAMAAAABmluZnIAAAA0bWluZgAA" +
                    "ABQAAAARAAAA/////wAAAAF2bWhkAAAAAAAAAAAAAAAAAAAAA+gAAAPoAAEAAAEAAAAAAAAA" +
                    "AAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAA8AAAAEdHJh" +
                    "awAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAABAAAA" +
                    "AAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAMAAAAA2bWRpYQAAACBtZGhkAAAAAAAAAAAAAAAA" +
                    "AAAyAAAAAAAAVcQAAAAAACdoZGxyAAAAAAAAAAB2aWRlAAAAAAAAAAAAAAAAVmlkZW9IYW5k" +
                    "bGVyAAAAAfVtaW5mAAAAFHZtaGQAAAABAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAA" +
                    "AAEAAAAMdXJsIAAAAQAAAFhzdGJsAAAAuHN0c2QAAAAAAAAAAQAAAKhhdmMxAAAAAAAAAAEA" +
                    "AAAAAAAAAAAAAAAAAAAAAQABABIAAABIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAGP//AAAANmF2Y0MBTUAf/+EAGGdNQB+wyA/gIBgAAAMAAQAAAwAy" +
                    "DxYtlgEABWjuUCwmXAAAABhzdHRzAAAAAAAAAAEAAAABAAAAHAAAABRzdHNzAAAAAAAAAAEA" +
                    "AAABAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAARAAAAkAAAAUc3RzegAAAAAAAAAAAAAA" +
                    "AQAAAB0AAAAUc3RjbwAAAAAAAAABAAAAMAAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIA" +
                    "AAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAAAAAA" +
                    "AQAAAAtIYW5kQnJha2U=";
                videoData = java.util.Base64.getDecoder().decode(dummyMp4Base64);
            } else {
                // Download video bytes from D-ID
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        videoUrl, HttpMethod.GET, null, byte[].class);

                videoData = response.getBody();
                if (videoData == null || videoData.length == 0) {
                    throw new RuntimeException("Empty video downloaded from D-ID");
                }
            }
            // -------------------------

            log.info("Downloaded avatar video: {} bytes for question {}", videoData.length, questionId);

            // Upload to S3 — returns S3 key (not a presigned URL)
            String timestamp = String.valueOf(System.currentTimeMillis());
            String s3Key = String.format("avatar-videos/question_%d_%s.mp4", questionId, timestamp);

            return videoStorageService.uploadBytes(videoData, s3Key, "video/mp4");

        } catch (HttpClientErrorException e) {
            log.error("Failed to download D-ID video for question {}: {} {}",
                    questionId, e.getStatusCode(), e.getMessage(), e);
            throw new RuntimeException("Failed to download avatar video: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error downloading D-ID video for question {}: {}",
                    questionId, e.getMessage(), e);
            throw new RuntimeException("Failed to download avatar video: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Build HTTP headers for D-ID API requests.
     *
     * <p>
     * D-ID uses Basic authentication with the API key as the username
     * and an empty password.
     * </p>
     *
     * @return configured HttpHeaders
     */
    private HttpHeaders buildDIDHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBasicAuth(apiKey, "");
        return headers;
    }

    /**
     * Sleep helper that handles InterruptedException correctly.
     *
     * <p>
     * On virtual threads, {@code Thread.sleep()} yields the carrier thread
     * back to the pool, making this efficient for concurrent polling operations.
     * </p>
     *
     * @param millis milliseconds to sleep
     * @throws RuntimeException if the thread is interrupted
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Operation interrupted", e);
        }
    }
}
