package com.interview.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.platform.dto.TranscriptionResult;
import com.interview.platform.model.Response;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Service for transcribing video/audio files using the AssemblyAI API.
 *
 * <h3>Purpose:</h3>
 * <p>
 * Converts user video responses into text transcriptions using AssemblyAI's
 * speech-to-text API. Transcriptions are used by the AI feedback service to
 * evaluate interview responses. The service handles the full async lifecycle:
 * submitting a transcription job, polling for completion, and persisting the
 * result to the database.
 * </p>
 *
 * <h3>P1 Changes:</h3>
 * <ul>
 * <li><strong>Resilience4j:</strong> The manual retry loop for the
 * transcription
 * job submission ({@code POST /v2/transcript}) has been replaced with
 * programmatic Resilience4j {@link Retry} and {@link CircuitBreaker}
 * decorators. This provides configurable exponential backoff, circuit
 * breaker protection against sustained AssemblyAI outages, and observable
 * metrics via the actuator.</li>
 * <li><strong>Polling loops unchanged:</strong> The
 * {@code pollForTranscription}
 * method is NOT wrapped with Resilience4j because it is checking async
 * job status ("queued" / "processing" → "completed"), not retrying a
 * failed operation. Treating "not ready yet" as a failure would exhaust
 * retry attempts incorrectly.</li>
 * <li><strong>S3 presigned URL generation:</strong> When called with an S3 key
 * (P1 storage model), the service generates a presigned GET URL with
 * sufficient validity (60 minutes) for AssemblyAI to download and
 * process the audio. This replaces the previous approach of passing
 * pre-generated presigned URLs that could expire during long-running
 * transcription jobs.</li>
 * <li><strong>SLF4J logging:</strong> Migrated from {@code java.util.logging}
 * to SLF4J for structured, parameterized logging consistent with the
 * rest of the application.</li>
 * </ul>
 *
 * <h3>AssemblyAI API Flow:</h3>
 * <ol>
 * <li>Submit a transcription job via {@code POST /v2/transcript} with the
 * audio URL. AssemblyAI returns a transcript ID.</li>
 * <li>Poll {@code GET /v2/transcript/{id}} at regular intervals until the
 * status transitions from "queued"/"processing" to "completed" or "error".</li>
 * <li>Extract the transcription text and confidence score from the completed
 * response.</li>
 * </ol>
 *
 * <h3>Thread Model:</h3>
 * <p>
 * The {@link #transcribeVideoAsync} method runs on a virtual thread via
 * {@code @Async("avatarTaskExecutor")}. The blocking {@code Thread.sleep()}
 * calls in the polling loop yield the virtual thread's carrier thread back
 * to the pool, making this efficient for concurrent transcription operations.
 * </p>
 *
 * @see VideoStorageService
 * @see com.interview.platform.config.ResilienceConfig
 */
@Service
public class SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(SpeechToTextService.class);

    /**
     * Maximum number of poll attempts for transcription completion.
     * At 3-second intervals, 60 attempts = 3 minutes maximum wait.
     */
    private static final int MAX_POLL_RETRIES = 60;

    /**
     * Interval between transcription status poll requests in milliseconds.
     */
    private static final long POLL_INTERVAL_MS = 3000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ResponseRepository responseRepository;
    private final VideoStorageService videoStorageService;

    // Resilience4j instances for AssemblyAI API calls
    private final Retry assemblyaiRetry;
    private final CircuitBreaker assemblyaiCircuitBreaker;

    @Value("${assemblyai.api.key}")
    private String apiKey;

    @Value("${assemblyai.api.url:https://api.assemblyai.com/v2}")
    private String apiBaseUrl;

    @Value("${app.base-url:http://localhost:8081}")
    private String appBaseUrl;

    public SpeechToTextService(RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ResponseRepository responseRepository,
            VideoStorageService videoStorageService,
            @Qualifier("assemblyaiRetry") Retry assemblyaiRetry,
            @Qualifier("assemblyaiCircuitBreaker") CircuitBreaker assemblyaiCircuitBreaker) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.responseRepository = responseRepository;
        this.videoStorageService = videoStorageService;
        this.assemblyaiRetry = assemblyaiRetry;
        this.assemblyaiCircuitBreaker = assemblyaiCircuitBreaker;
    }

    // ════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Transcribe a video/audio file from an S3 key or URL (synchronous).
     *
     * <p>
     * If the provided {@code videoUrlOrKey} looks like an S3 key (does not
     * start with "http"), a presigned GET URL is generated with 60-minute
     * validity so AssemblyAI can download the file. If it already starts
     * with "http", it is used as-is (backward compatibility with legacy
     * presigned URLs still stored in the database).
     * </p>
     *
     * @param videoUrlOrKey the S3 key or publicly accessible URL of the media file
     * @return TranscriptionResult with text and confidence
     * @throws RuntimeException if the transcription fails or times out
     */
    public TranscriptionResult transcribeVideo(String videoUrlOrKey) {
        log.info("Starting transcription for: {}", videoUrlOrKey);

        // FIX: Detect missing/invalid API keys early
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("your-api-key-here")
                || apiKey.equals("${ASSEMBLYAI_API_KEY}")) {
            log.warn("AssemblyAI API key is missing or placeholder — returning empty transcription. "
                    + "Set ASSEMBLYAI_API_KEY in .env to enable cloud transcription.");
            return new TranscriptionResult("[Transcription unavailable — API key not configured]", 0.0);
        }

        // FIX: Detect localhost mode — AssemblyAI (cloud) cannot access localhost URLs
        if (appBaseUrl != null && (appBaseUrl.contains("localhost") || appBaseUrl.contains("127.0.0.1"))) {
            if (videoUrlOrKey != null && (videoUrlOrKey.contains("localhost") || videoUrlOrKey.contains("127.0.0.1")
                    || !videoUrlOrKey.startsWith("http"))) {
                log.warn("Local development mode detected — AssemblyAI cannot access local files. "
                        + "Returning placeholder transcription for: {}", videoUrlOrKey);
                return new TranscriptionResult(
                        "[Transcription unavailable in local mode — use browser speech recognition]", 0.5);
            }
        }

        // Resolve S3 key to a presigned URL if needed
        String accessibleUrl = resolveToAccessibleUrl(videoUrlOrKey);

        // Step 1: Submit transcription job (with Resilience4j)
        String transcriptId = submitTranscriptionJobWithResilience(accessibleUrl);
        log.info("Transcription job submitted: transcriptId={}", transcriptId);

        // Step 2: Poll for completion (manual polling, NOT Resilience4j)
        TranscriptionResult result = pollForTranscription(transcriptId);
        log.info("Transcription completed: transcriptId={}, confidence={}",
                transcriptId, result.getConfidence());

        return result;
    }

    /**
     * Async transcription that also persists the result to the Response entity.
     *
     * <p>
     * Runs on a virtual thread via {@code @Async("avatarTaskExecutor")}.
     * After transcription completes, the text and confidence score are
     * saved to the {@link Response} entity associated with the given
     * question ID.
     * </p>
     *
     * <p>
     * If the Response entity is not found (e.g., it was deleted between
     * upload and transcription), the transcription result is logged but
     * not persisted. This is a non-fatal condition.
     * </p>
     *
     * @param videoUrlOrKey the S3 key or URL of the video to transcribe
     * @param questionId    the question ID to look up the Response entity
     * @return CompletableFuture containing the transcription result
     */
    @Async("avatarTaskExecutor")
    @Transactional
    public CompletableFuture<TranscriptionResult> transcribeVideoAsync(String videoUrlOrKey, Long questionId) {
        try {
            TranscriptionResult result = transcribeVideo(videoUrlOrKey);

            // Persist transcription to Response entity
            Optional<Response> responseOpt = responseRepository.findByQuestionId(questionId);
            if (responseOpt.isPresent()) {
                Response response = responseOpt.get();
                response.setTranscription(result.getText());
                response.setTranscriptionConfidence(result.getConfidence());
                responseRepository.save(response);
                log.info("Transcription saved to Response for question {}", questionId);
            } else {
                log.warn("No Response entity found for question {} — transcription not persisted",
                        questionId);
            }

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Async transcription failed for question {}: {}", questionId, e.getMessage(), e);
            CompletableFuture<TranscriptionResult> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    /**
     * Attempts to retrieve a completed transcription synchronously.
     * 
     * <p>This method is called during interview completion as a best-effort
     * attempt to fill in any transcriptions that may have been completed by
     * AssemblyAI but not yet persisted to the Response entity. It does NOT
     * initiate new transcription jobs or poll for in-progress jobs.</p>
     * 
     * <p>If the transcription is available (already completed and persisted
     * by transcribeVideoAsync), returns it. Otherwise, returns null.</p>
     * 
     * @param videoUrlOrKey the S3 key or URL of the video
     * @return the transcription text if available, or null
     */
    public String getTranscriptionResult(String videoUrlOrKey) {
        // videoUrlOrKey is the S3 key stored in Response.videoUrl
        // Find the Response by its video URL (S3 key)
        // and return its transcription if available
        try {
            Optional<Response> responseOpt =
                    responseRepository.findByVideoUrl(videoUrlOrKey);
            if (responseOpt.isPresent()) {
                String transcription = responseOpt.get().getTranscription();
                return (transcription != null && !transcription.isBlank())
                        ? transcription : null;
            }
        } catch (Exception e) {
            log.warn("getTranscriptionResult failed for videoUrl {}: {}",
                    videoUrlOrKey, e.getMessage());
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // URL Resolution
    // ════════════════════════════════════════════════════════════════

    /**
     * Resolve an S3 key or URL to an accessible URL for AssemblyAI.
     *
     * <p>
     * If the input looks like an S3 key (does not start with "http"),
     * generates a presigned GET URL with 60-minute validity. Otherwise,
     * returns the input as-is (it's already a URL).
     * </p>
     *
     * <p>
     * 60 minutes provides sufficient time for AssemblyAI to download
     * and process the audio file, even for longer recordings or during
     * periods of high API load.
     * </p>
     *
     * @param videoUrlOrKey the S3 key or URL
     * @return an accessible URL suitable for the AssemblyAI audio_url parameter
     */
    private String resolveToAccessibleUrl(String videoUrlOrKey) {
        if (videoUrlOrKey == null || videoUrlOrKey.isBlank()) {
            throw new IllegalArgumentException("Video URL or S3 key must not be null or blank");
        }

        // If it looks like a URL, validate it's not a localhost URL (AssemblyAI can't access localhost)
        if (videoUrlOrKey.startsWith("http://") || videoUrlOrKey.startsWith("https://")) {
            // Reject localhost URLs - AssemblyAI cannot access them
            if (videoUrlOrKey.contains("localhost") || videoUrlOrKey.contains("127.0.0.1")) {
                log.warn("Localhost URL detected ({}). Attempting to extract S3 key and generate presigned URL.", videoUrlOrKey);
                // Try to extract S3 key from the URL path
                // Format: /api/files/interviews/{userId}/{interviewId}/{filename}
                String s3Key = extractS3KeyFromLocalUrl(videoUrlOrKey);
                if (s3Key != null) {
                    return videoStorageService.generatePresignedGetUrl(s3Key, 60);
                }
                throw new IllegalArgumentException("Cannot transcribe localhost URL. Video must be in S3.");
            }
            // For other URLs (e.g., already a presigned S3 URL), use as-is
            log.debug("Using provided URL directly for transcription: {}", videoUrlOrKey);
            return videoUrlOrKey;
        }

        // It's an S3 key — generate a presigned GET URL
        log.debug("Generating presigned URL from S3 key for transcription: {}", videoUrlOrKey);
        return videoStorageService.generatePresignedGetUrl(videoUrlOrKey, 60);
    }

    /**
     * Extract S3 key from a local server URL.
     * Expected format: /api/files/interviews/{userId}/{interviewId}/{filename}
     */
    private String extractS3KeyFromLocalUrl(String url) {
        try {
            // URL format: http://localhost:8081/api/files/interviews/11/88/response_226_xxx.webm
            // or: http://127.0.0.1:8081/api/files/interviews/11/88/response_226_xxx.webm
            String path = url;
            if (url.contains("/api/files/")) {
                path = url.substring(url.indexOf("/api/files/") + "/api/files/".length());
            }
            // path is now: interviews/11/88/response_226_xxx.webm
            if (path.startsWith("interviews/")) {
                return path;
            }
            return "interviews/" + path;
        } catch (Exception e) {
            log.error("Failed to extract S3 key from URL: {}", url, e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Submit Transcription Job (with Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * Submit a transcription job to AssemblyAI with Resilience4j retry
     * and circuit breaker.
     *
     * <p>
     * Replaces the previous manual retry loop. The retry configuration
     * (max attempts, backoff, retryable exceptions) is defined in
     * {@link com.interview.platform.config.ResilienceConfig} and can be
     * tuned via application.properties.
     * </p>
     *
     * <p>
     * The circuit breaker tracks the failure rate of AssemblyAI job
     * submission calls. If the rate exceeds the configured threshold,
     * subsequent calls fail fast without making HTTP requests.
     * </p>
     *
     * @param audioUrl the publicly accessible URL of the audio/video file
     * @return the AssemblyAI transcript ID
     * @throws RuntimeException if all retries are exhausted or the circuit is open
     */
    private String submitTranscriptionJobWithResilience(String audioUrl) {
        // --- MOCK API FALLBACK ---
        if (apiKey != null && apiKey.startsWith("mock")) {
            log.info("Mock AssemblyAI API key detected. Returning dummy transcript ID.");
            return "mock-transcript-id-" + System.currentTimeMillis();
        }
        // -------------------------

        return Decorators.ofSupplier(() -> callSubmitTranscription(audioUrl))
                .withRetry(assemblyaiRetry)
                .withCircuitBreaker(assemblyaiCircuitBreaker)
                .decorate()
                .get();
    }

    /**
     * Make the actual HTTP POST to AssemblyAI {@code /v2/transcript} endpoint.
     *
     * <p>
     * This is the "raw" API call without any retry or circuit breaker
     * logic. It is wrapped by {@link #submitTranscriptionJobWithResilience}
     * which handles resilience concerns.
     * </p>
     *
     * @param audioUrl the publicly accessible URL of the audio/video file
     * @return the AssemblyAI transcript ID
     * @throws RuntimeException on API errors or parse failures
     */
    private String callSubmitTranscription(String audioUrl) {
        String url = apiBaseUrl + "/transcript";

        HttpHeaders headers = buildHeaders();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("audio_url", audioUrl);
        // FIX: AssemblyAI requires speech_models list.
        requestBody.put("speech_models", List.of("universal-2"));
        requestBody.put("language_code", "en");

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AssemblyAI request body", e);
        }

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        log.debug("Calling AssemblyAI POST /transcript: audioUrl={}", audioUrl);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String transcriptId = root.path("id").asText();
            if (transcriptId == null || transcriptId.isBlank()) {
                throw new RuntimeException("No transcript ID returned from AssemblyAI");
            }
            return transcriptId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse AssemblyAI submit response", e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Poll for Transcription Completion (manual polling — NOT Resilience4j)
    // ════════════════════════════════════════════════════════════════

    /**
     * Poll AssemblyAI {@code /v2/transcript/{id}} until the transcription
     * is complete.
     *
     * <p>
     * <strong>This is NOT wrapped with Resilience4j retry.</strong>
     * Polling for async job completion is fundamentally different from
     * retrying a failed API call. A "queued" or "processing" status is
     * not a failure — it means the job is still in progress. Wrapping
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
     * poll request fails with a transient error, the loop continues to the
     * next iteration (with a sleep interval), providing implicit retry.
     * </p>
     *
     * @param transcriptId the AssemblyAI transcript ID
     * @return TranscriptionResult with text and confidence
     * @throws RuntimeException if the transcription fails or times out
     */
    private TranscriptionResult pollForTranscription(String transcriptId) {
        // --- MOCK API FALLBACK ---
        if (apiKey != null && apiKey.startsWith("mock") && transcriptId.startsWith("mock-transcript-id")) {
            log.info("Mock AssemblyAI API key detected. Simulating immediate transcription completion.");
            return new TranscriptionResult("This is a mock transcription of the user's answer.", 0.99);
        }
        // -------------------------

        String url = apiBaseUrl + "/transcript/" + transcriptId;
        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (int attempt = 0; attempt < MAX_POLL_RETRIES; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.GET, entity, String.class);

                JsonNode root = objectMapper.readTree(response.getBody());
                String status = root.path("status").asText();

                switch (status) {
                    case "completed":
                        String text = root.path("text").asText("");
                        double confidence = root.path("confidence").asDouble(0.0);

                        // FIX: Warn if confidence is low - transcription may be inaccurate
                        if (confidence < 0.7) {
                            log.warn("Low transcription confidence for {}: {} (may affect feedback quality)", 
                                    transcriptId, confidence);
                        }

                        log.info("Transcription {} completed after {} polls (confidence: {})",
                                transcriptId, attempt + 1, confidence);

                        return new TranscriptionResult(text, confidence);

                    case "error":
                        String errorMsg = root.path("error").asText("Unknown transcription error");
                        throw new RuntimeException("AssemblyAI transcription failed: " + errorMsg);

                    case "queued":
                    case "processing":
                        log.debug("Transcription {} status: {} (poll {}/{})",
                                transcriptId, status, attempt + 1, MAX_POLL_RETRIES);
                        break;

                    default:
                        log.warn("Transcription {} unexpected status: {}", transcriptId, status);
                        break;
                }

            } catch (HttpClientErrorException e) {
                log.error("AssemblyAI poll error for transcript {}: {} {}",
                        transcriptId, e.getStatusCode(), e.getMessage());
                throw new RuntimeException("Failed to poll AssemblyAI transcript status: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                // Re-throw our own error cases (AssemblyAI failures)
                if (e.getMessage() != null && e.getMessage().startsWith("AssemblyAI transcription")) {
                    throw e;
                }
                // Transient errors during polling — log and continue
                log.warn("Error polling transcript {}: {}", transcriptId, e.getMessage());
            } catch (Exception e) {
                log.warn("Error parsing AssemblyAI poll response for {}: {}", transcriptId, e.getMessage());
            }

            sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException("AssemblyAI transcription timed out after "
                + (MAX_POLL_RETRIES * POLL_INTERVAL_MS / 1000) + " seconds for transcript: " + transcriptId);
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Build HTTP headers for AssemblyAI API requests.
     *
     * <p>
     * AssemblyAI uses a simple API key passed in the {@code Authorization}
     * header (not Bearer token format — just the raw key).
     * </p>
     *
     * @return configured HttpHeaders
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("Authorization", apiKey);
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
