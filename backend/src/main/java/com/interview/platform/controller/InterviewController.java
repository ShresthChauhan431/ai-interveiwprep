package com.interview.platform.controller;

import com.interview.platform.dto.*;
import com.interview.platform.model.Feedback;
import com.interview.platform.model.InterviewStatus;
import com.interview.platform.repository.FeedbackRepository;
import com.interview.platform.repository.InterviewRepository;
import com.interview.platform.service.InterviewService;
import com.interview.platform.service.SseEmitterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * REST controller for interview lifecycle operations.
 *
 * <h3>P1 Changes:</h3>
 * <ul>
 * <li><strong>Presigned URL upload endpoints:</strong> Added
 * {@code GET /{interviewId}/upload-url} and
 * {@code POST /{interviewId}/confirm-upload} for direct-to-S3
 * video uploads. The frontend uploads video files directly to S3
 * using presigned PUT URLs, bypassing the backend server entirely.</li>
 * <li><strong>Legacy response endpoint retained:</strong> The
 * {@code POST /{interviewId}/response} endpoint (multipart upload)
 * is retained for backward compatibility but the presigned flow
 * is preferred.</li>
 * <li><strong>SLF4J logging:</strong> Migrated from {@code java.util.logging}
 * to SLF4J for structured, parameterized logging.</li>
 * </ul>
 *
 * @see InterviewService
 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);

    private final InterviewService interviewService;
    private final FeedbackRepository feedbackRepository;
    private final InterviewRepository interviewRepository;
    private final SseEmitterService sseEmitterService;

    public InterviewController(InterviewService interviewService,
            FeedbackRepository feedbackRepository,
            InterviewRepository interviewRepository,
            SseEmitterService sseEmitterService) {
        this.interviewService = interviewService;
        this.feedbackRepository = feedbackRepository;
        this.interviewRepository = interviewRepository;
        this.sseEmitterService = sseEmitterService;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. POST /api/interviews/start
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/start")
    public ResponseEntity<InterviewDTO> startInterview(
            @Valid @RequestBody StartInterviewRequest request,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        log.info("Starting interview — user: {}, resume: {}, jobRole: {}, numQuestions: {}",
                userId, request.getResumeId(), request.getJobRoleId(), request.getNumQuestions());

        InterviewDTO interview = interviewService.startInterview(
                userId, request.getResumeId(), request.getJobRoleId(), request.getNumQuestions());

        return new ResponseEntity<>(interview, HttpStatus.CREATED);
    }

    // ════════════════════════════════════════════════════════════════
    // 2. GET /api/interviews/{interviewId}/upload-url (P1 — presigned PUT)
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate a presigned PUT URL for direct-to-S3 video upload.
     *
     * <p>
     * The frontend uses the returned URL to upload the video response
     * directly to S3 via HTTP PUT, then calls {@code /confirm-upload}
     * with the S3 key to create the Response entity.
     * </p>
     *
     * <h3>Frontend Usage:</h3>
     * 
     * <pre>{@code
     * // 1. Get presigned URL
     * const res = await fetch(`/api/interviews/${interviewId}/upload-url?questionId=${qId}`);
     * const { uploadUrl, s3Key } = await res.json();
     *
     * // 2. Upload directly to S3
     * await fetch(uploadUrl, { method: 'PUT', body: videoBlob,
     *   headers: { 'Content-Type': 'video/webm' }
     * });
     *
     * // 3. Confirm upload
     * await fetch(`/api/interviews/${interviewId}/confirm-upload`, {
     *   method: 'POST',
     *   headers: { 'Content-Type': 'application/json' },
     *   body: JSON.stringify({ questionId: qId, s3Key })
     * });
     * }</pre>
     *
     * @param interviewId the interview ID
     * @param questionId  the question being answered
     * @param contentType optional MIME type (default: video/webm)
     * @param httpRequest the HTTP request (for user ID extraction)
     * @return presigned PUT URL, S3 key, and expiry duration
     */
    @GetMapping("/{interviewId}/upload-url")
    public ResponseEntity<PresignedUrlResponse> getUploadUrl(
            @PathVariable Long interviewId,
            @RequestParam("questionId") Long questionId,
            @RequestParam(value = "contentType", defaultValue = "video/webm") String contentType,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        log.info("Generating upload URL — user: {}, interview: {}, question: {}",
                userId, interviewId, questionId);

        PresignedUrlResponse response = interviewService.generateUploadUrl(
                userId, interviewId, questionId, contentType);

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. POST /api/interviews/{interviewId}/confirm-upload (P1)
    // ════════════════════════════════════════════════════════════════

    /**
     * Confirm a direct-to-S3 video upload and create the Response entity.
     *
     * <p>
     * Called by the frontend after successfully uploading a video to S3
     * using the presigned PUT URL from {@code /upload-url}. Verifies the
     * S3 object exists, creates the Response entity, and triggers async
     * transcription via AssemblyAI.
     * </p>
     *
     * @param interviewId the interview ID
     * @param request     the confirm-upload request body
     * @param httpRequest the HTTP request (for user ID extraction)
     * @return confirmation message
     */
    @PostMapping("/{interviewId}/confirm-upload")
    public ResponseEntity<Map<String, Object>> confirmUpload(
            @PathVariable Long interviewId,
            @Valid @RequestBody ConfirmUploadRequest request,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        log.info("Confirming upload — user: {}, interview: {}, question: {}, s3Key: {}",
                userId, interviewId, request.getQuestionId(), request.getS3Key());

        String result = interviewService.confirmUpload(userId, interviewId, request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", result);
        response.put("interviewId", interviewId);
        response.put("questionId", request.getQuestionId());
        response.put("s3Key", request.getS3Key());

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════
    // 4. POST /api/interviews/{interviewId}/response (legacy — multipart)
    // ════════════════════════════════════════════════════════════════

    /**
     * Submit a video response via multipart upload (legacy endpoint).
     *
     * <p>
     * <strong>Deprecated:</strong> Use the presigned URL flow instead:
     * {@code GET /upload-url} → S3 PUT → {@code POST /confirm-upload}.
     * This endpoint proxies the entire video file through the backend server,
     * consuming bandwidth and memory.
     * </p>
     *
     * @deprecated Use {@code GET /{interviewId}/upload-url} and
     *             {@code POST /{interviewId}/confirm-upload} instead.
     */
    @PostMapping("/{interviewId}/response")
    public ResponseEntity<Map<String, Object>> submitResponse(
            @PathVariable Long interviewId,
            @RequestParam("questionId") Long questionId,
            @RequestParam("video") MultipartFile videoFile,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        log.info("Submitting response (legacy multipart) — user: {}, interview: {}, question: {}",
                userId, interviewId, questionId);

        if (videoFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Video file is required",
                    "status", "BAD_REQUEST"));
        }

        String result = interviewService.submitResponse(userId, interviewId, questionId, videoFile);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", result);
        response.put("interviewId", interviewId);
        response.put("questionId", questionId);

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════
    // 5. GET /api/interviews/{interviewId}
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/{interviewId}")
    public ResponseEntity<InterviewDTO> getInterview(
            @PathVariable Long interviewId,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        InterviewDTO interview = interviewService.getInterview(interviewId, userId);

        return ResponseEntity.ok(interview);
    }

    // ════════════════════════════════════════════════════════════════
    // 6. POST /api/interviews/{interviewId}/complete
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/{interviewId}/complete")
    public ResponseEntity<Map<String, String>> completeInterview(
            @PathVariable Long interviewId,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        log.info("Completing interview — user: {}, interview: {}", userId, interviewId);

        String result = interviewService.completeInterview(interviewId, userId);

        return ResponseEntity.ok(Map.of(
                "message", result,
                "interviewId", String.valueOf(interviewId),
                "status", "PROCESSING"));
    }

    // ════════════════════════════════════════════════════════════════
    // 7. GET /api/interviews/history
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/history")
    public ResponseEntity<List<InterviewDTO>> getInterviewHistory(HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        List<InterviewDTO> history = interviewService.getInterviewHistory(userId);

        return ResponseEntity.ok(history);
    }

    // ════════════════════════════════════════════════════════════════
    // 8. DELETE /api/interviews/{interviewId}
    // ════════════════════════════════════════════════════════════════

    @DeleteMapping("/{interviewId}")
    public ResponseEntity<Map<String, String>> deleteInterview(
            @PathVariable Long interviewId,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);
        log.info("Deleting interview — user: {}, interview: {}", userId, interviewId);

        interviewService.deleteInterview(interviewId, userId);

        return ResponseEntity.ok(Map.of(
                "message", "Interview deleted successfully",
                "interviewId", String.valueOf(interviewId)));
    }

    // ════════════════════════════════════════════════════════════════
    // 9. GET /api/interviews/{interviewId}/feedback
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/{interviewId}/feedback")
    public ResponseEntity<Object> getInterviewFeedback(
            @PathVariable Long interviewId,
            HttpServletRequest httpRequest) {

        Long userId = getUserIdFromRequest(httpRequest);

        // Validate ownership
        interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        // Check feedback availability
        Optional<Feedback> feedbackOpt = feedbackRepository.findByInterviewId(interviewId);

        if (feedbackOpt.isPresent()) {
            Feedback feedback = feedbackOpt.get();
            Map<String, Object> feedbackResponse = new LinkedHashMap<>();
            feedbackResponse.put("status", "COMPLETED");
            feedbackResponse.put("overallScore", feedback.getOverallScore());
            feedbackResponse.put("strengths", parseFeedbackList(feedback.getStrengths()));
            feedbackResponse.put("weaknesses", parseFeedbackList(feedback.getWeaknesses()));
            feedbackResponse.put("recommendations", parseFeedbackList(feedback.getRecommendations()));
            feedbackResponse.put("detailedAnalysis", feedback.getDetailedAnalysis());
            feedbackResponse.put("generatedAt", feedback.getGeneratedAt());

            return ResponseEntity.ok(feedbackResponse);
        }

        // Check if interview is still processing
        var interview = interviewRepository.findById(interviewId);
        if (interview.isPresent() && interview.get().getStatus() == InterviewStatus.PROCESSING) {
            Map<String, String> processingResponse = new LinkedHashMap<>();
            processingResponse.put("status", "PROCESSING");
            processingResponse.put("message", "Feedback is being generated. Please check back shortly.");

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(processingResponse);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "status", "NOT_FOUND",
                        "message", "No feedback available. Complete the interview first."));
    }

    // ════════════════════════════════════════════════════════════════
    // 9. GET /api/interviews/{interviewId}/events (SSE)
    // ════════════════════════════════════════════════════════════════

    /**
     * Subscribe to real-time status updates for an interview via
     * Server-Sent Events (SSE).
     *
     * <p>
     * The frontend connects to this endpoint while waiting for avatar
     * videos to be generated. Events are pushed as each video becomes
     * ready, allowing the UI to update a progress bar in real time
     * instead of polling every few seconds.
     * </p>
     *
     * <p>
     * The connection remains open for up to 10 minutes and is
     * automatically closed when the interview transitions to
     * {@code IN_PROGRESS} or on timeout.
     * </p>
     *
     * @param interviewId the interview to subscribe to
     * @return an SSE stream of avatar generation events
     */
    @GetMapping(value = "/{interviewId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable Long interviewId,
            HttpServletRequest httpRequest) {
        Long userId = getUserIdFromRequest(httpRequest);

        // Validate ownership
        interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        log.info("SSE connection opened: interviewId={}, userId={}", interviewId, userId);
        return sseEmitterService.register(interviewId);
    }

    // ════════════════════════════════════════════════════════════════
    // Helper
    // ════════════════════════════════════════════════════════════════

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr == null) {
            throw new RuntimeException("User not authenticated");
        }
        return (Long) userIdAttr;
    }

    /**
     * Parse stored feedback field (JSON array or plain text) to list for API.
     * Frontend expects string[] for strengths, weaknesses, recommendations.
     */
    private static List<String> parseFeedbackList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                return new ObjectMapper().readValue(trimmed, new TypeReference<List<String>>() {
                });
            } catch (Exception e) {
                log.warn("Failed to parse feedback list as JSON, using as single item: {}", e.getMessage());
            }
        }
        return Collections.singletonList(value);
    }
}
