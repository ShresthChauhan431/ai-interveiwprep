package com.interview.platform.service;

import com.interview.platform.config.InterviewConfig;
import com.interview.platform.dto.*;
import com.interview.platform.event.QuestionsCreatedEvent;
import com.interview.platform.model.*;
import com.interview.platform.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.web.client.ResourceAccessException; // FIX: Import for catching Ollama connection errors

/**
 * Core service for managing interview lifecycle operations.
 *
 * <h3>P1 Changes from P0:</h3>
 * <ul>
 * <li><strong>Event-driven avatar pipeline:</strong> Previously,
 * {@code startInterview()} directly called
 * {@code AvatarVideoService.generateAvatarVideoAsync()} for each question
 * inside the same transaction, with fire-and-forget {@code CompletableFuture}
 * callbacks that could silently fail. Now, questions are saved in the
 * transaction, and a {@link QuestionsCreatedEvent} is published after
 * the transaction commits. The {@code AvatarPipelineListener} handles
 * avatar generation asynchronously on virtual threads, with deterministic
 * caching via {@code CachedAvatarService}.</li>
 * <li><strong>State machine:</strong> The interview now follows the full
 * state machine: {@code CREATED → GENERATING_VIDEOS → IN_PROGRESS →
 *       PROCESSING → COMPLETED}. Previously, interviews were created directly
 * in {@code IN_PROGRESS} state. The new states allow the frontend to
 * show loading indicators while avatar videos are being generated.</li>
 * <li><strong>Presigned URL upload flow:</strong> Added
 * {@link #generateUploadUrl} and {@link #confirmUpload} methods for
 * direct-to-S3 video uploads. The previous flow proxied video files
 * through the backend server ({@code submitResponse} with
 * {@code MultipartFile}),
 * which was a bandwidth and memory bottleneck. The legacy
 * {@code submitResponse} method is retained for backward compatibility
 * but the presigned flow is preferred.</li>
 * <li><strong>S3 key storage:</strong> All entity fields that previously
 * stored presigned GET URLs ({@code avatarVideoUrl}, {@code videoUrl})
 * now store S3 object keys. Presigned GET URLs are generated on-demand
 * when building DTOs for the frontend via
 * {@link VideoStorageService#generatePresignedGetUrl}. This eliminates
 * the URL expiry problem where stored URLs became invalid after 7 days.</li>
 * <li><strong>SLF4J logging:</strong> Migrated from {@code java.util.logging}
 * to SLF4J for structured, parameterized logging consistent with the
 * rest of the application.</li>
 * </ul>
 *
 * <h3>State Machine:</h3>
 *
 * <pre>
 *   CREATED ──► GENERATING_VIDEOS ──► IN_PROGRESS ──► PROCESSING ──► COMPLETED
 *                      │                    │               │
 *                      └────────────────────┴───────────────┴──────► FAILED
 * </pre>
 *
 * <h3>Interview Lifecycle:</h3>
 * <ol>
 * <li>{@code POST /api/interviews/start} → creates interview
 * (GENERATING_VIDEOS),
 * generates questions via Ollama, publishes {@code QuestionsCreatedEvent}</li>
 * <li>Frontend polls {@code GET /api/interviews/{id}} until status =
 * IN_PROGRESS</li>
 * <li>Frontend requests presigned PUT URLs, uploads videos directly to S3</li>
 * <li>Frontend confirms uploads via
 * {@code POST /api/interviews/{id}/confirm-upload}</li>
 * <li>{@code POST /api/interviews/{id}/complete} → transitions to PROCESSING,
 * triggers async AI feedback generation</li>
 * <li>Frontend polls feedback endpoint until status = COMPLETED</li>
 * </ol>
 *
 * @see com.interview.platform.event.QuestionsCreatedEvent
 * @see com.interview.platform.event.AvatarPipelineListener
 * @see com.interview.platform.service.OllamaService
 * @see com.interview.platform.task.InterviewRecoveryTask
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private final InterviewRepository interviewRepository;
    private final ResumeRepository resumeRepository;
    private final JobRoleRepository jobRoleRepository;
    private final QuestionRepository questionRepository;
    private final ResponseRepository responseRepository;
    private final UserRepository userRepository;
    private final OllamaService ollamaService;
    private final VideoStorageService videoStorageService;
    private final SpeechToTextService speechToTextService;
    private final AIFeedbackService aiFeedbackService;
    private final TextToSpeechService textToSpeechService; // FIX: Added TTS service for generating audio per question
    private final ApplicationEventPublisher eventPublisher;
    private final InterviewConfig interviewConfig; // Hybrid interview configuration

    public InterviewService(InterviewRepository interviewRepository,
            ResumeRepository resumeRepository,
            JobRoleRepository jobRoleRepository,
            QuestionRepository questionRepository,
            ResponseRepository responseRepository,
            UserRepository userRepository,
            OllamaService ollamaService,
            VideoStorageService videoStorageService,
            SpeechToTextService speechToTextService,
            AIFeedbackService aiFeedbackService,
            TextToSpeechService textToSpeechService, // FIX: Inject TTS service for per-question audio generation
            ApplicationEventPublisher eventPublisher,
            InterviewConfig interviewConfig) { // Inject hybrid interview config
        this.interviewRepository = interviewRepository;
        this.resumeRepository = resumeRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.questionRepository = questionRepository;
        this.responseRepository = responseRepository;
        this.userRepository = userRepository;
        this.ollamaService = ollamaService;
        this.videoStorageService = videoStorageService;
        this.speechToTextService = speechToTextService;
        this.aiFeedbackService = aiFeedbackService;
        this.textToSpeechService = textToSpeechService; // FIX: Store TTS service reference
        this.eventPublisher = eventPublisher;
        this.interviewConfig = interviewConfig; // Store hybrid config
    }

    // ════════════════════════════════════════════════════════════════
    // 1. Start Interview (event-driven avatar pipeline)
    // ════════════════════════════════════════════════════════════════

    /**
     * Start a new interview session.
     *
     * <p>
     * Creates the Interview entity with status {@code GENERATING_VIDEOS},
     * generates AI questions via OpenAI, saves all Question entities, and
     * publishes a {@link QuestionsCreatedEvent} that will be dispatched
     * after the transaction commits.
     * </p>
     *
     * <h3>P1 State Machine Change:</h3>
     * <p>
     * Previously set status to {@code IN_PROGRESS} immediately and
     * fired async avatar generation via {@code CompletableFuture}. Now uses
     * {@code GENERATING_VIDEOS} to indicate that avatar videos are being
     * generated asynchronously. The {@code AvatarPipelineListener} transitions
     * the interview to {@code IN_PROGRESS} when all avatar videos are ready
     * (or after timeout via {@code InterviewRecoveryTask}).
     * </p>
     *
     * <h3>Event Publishing:</h3>
     * <p>
     * The {@code QuestionsCreatedEvent} is published inside the transaction
     * but dispatched via {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
     * only after the transaction commits successfully. This ensures that
     * the listener can always find the questions in the database.
     * </p>
     *
     * @param userId    the authenticated user's ID
     * @param resumeId  the resume to base questions on
     * @param jobRoleId the target job role
     * @return the created interview DTO with questions (status = GENERATING_VIDEOS)
     * @throws RuntimeException if user, resume, or job role not found, or
     *                          if resume doesn't belong to user or has no text
     */
    @Transactional
    public InterviewDTO startInterview(Long userId, Long resumeId, Long jobRoleId, Integer numQuestions) {
        log.info("Starting interview for user: {}, resume: {}, jobRole: {}, numQuestions: {}", userId, resumeId,
                jobRoleId, numQuestions);

        // ── Validate user ────────────────────────────────────────
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Issue 4: Check daily interview limit
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long interviewsToday = interviewRepository.countByUserIdAndStartedAtAfter(userId, todayStart);
        int maxInterviewsPerDay = 10; // Configurable limit
        if (interviewsToday >= maxInterviewsPerDay) {
            throw new RuntimeException("Daily interview limit reached. You can create up to " + maxInterviewsPerDay + " interviews per day. Please try again tomorrow.");
        }

        // ── Validate and fetch resume ────────────────────────────
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new RuntimeException("Resume not found: " + resumeId));

        if (!resume.getUser().getId().equals(userId)) {
            throw new RuntimeException("Resume does not belong to user: " + userId);
        }

        String resumeText = resume.getExtractedText();
        if (resumeText == null || resumeText.isBlank()) {
            // FIX: Don't crash — use fallback text so generic interview can proceed
            resumeText = "Resume text could not be extracted. Generic interview will proceed.";
            log.warn("Resume {} has no extracted text, using fallback for user {}", resumeId, userId);
        }

        // ── Validate and fetch job role ──────────────────────────
        JobRole jobRole = jobRoleRepository.findById(jobRoleId)
                .orElseThrow(() -> new RuntimeException("Job role not found: " + jobRoleId));

        // Issue 8: Validate job role is active
        if (!jobRole.isActive()) {
            throw new RuntimeException("Job role is not available: " + jobRole.getTitle());
        }

        // ── Create Interview entity ──────────────────────────────
        // FIX: Start in IN_PROGRESS directly — skip GENERATING_VIDEOS since we removed D-ID and audio generation is fast
        Interview interview = new Interview();
        interview.setUser(user);
        interview.setResume(resume);
        interview.setJobRole(jobRole);
        interview.setStatus(InterviewStatus.CREATED); // FIX: Start as CREATED, will transition to IN_PROGRESS after questions are saved
        interview.setType(InterviewType.VIDEO);
        interview = interviewRepository.save(interview);

        log.info("Interview created with ID: {} (status=CREATED)", interview.getId());

        // P1-9: Reduced max from 20 to 10, default from 10 to 5 for API cost control.
        int finalNumQuestions = (numQuestions != null && numQuestions > 0 && numQuestions <= 10) ? numQuestions : 5;

        // Hybrid mode: determine how many questions to pre-generate
        int pregenCount = Math.min(interviewConfig.getPregenCount(), finalNumQuestions);
        boolean hybridMode = interviewConfig.isHybridModeEnabled(finalNumQuestions);

        log.info("Interview {} using {} mode: pregenCount={}, totalQuestions={}",
                interview.getId(), hybridMode ? "HYBRID" : "FULL_PREGEN", pregenCount, finalNumQuestions);

        // Store total question count on interview for later use in dynamic generation
        interview.setTotalQuestions(finalNumQuestions);
        interviewRepository.save(interview);

        // FIX: Wrap entire question generation + TTS in try-catch so errors are actionable
        List<Question> questions;
        try {
            // Step 1: Generate only the pre-generated zone questions via Ollama
            // (In hybrid mode, remaining questions will be generated dynamically after each answer)
            questions = ollamaService.generateQuestionsWithResilience(resume, jobRole, pregenCount);
        } catch (ResourceAccessException e) {
            // FIX: Ollama connection refused — give clear instructions to the user
            log.error("Ollama is not reachable. Cannot generate questions for interview {}", interview.getId(), e);
            interview.setStatus(InterviewStatus.FAILED); // FIX: Mark as failed so it doesn't stay stuck
            interviewRepository.save(interview);
            throw new RuntimeException("Question generation failed. Please ensure Ollama is running: run 'ollama serve' in terminal.");
        } catch (Exception e) {
            // FIX: Any other Ollama failure — give actionable error message
            log.error("Question generation failed for interview {}: {}", interview.getId(), e.getMessage(), e);
            interview.setStatus(InterviewStatus.FAILED); // FIX: Mark as failed
            interviewRepository.save(interview);
            throw new RuntimeException("Question generation failed. Please ensure Ollama is running: run 'ollama serve' in terminal.");
        }

        // Step 2: Save questions and generate TTS audio for each
        List<Long> questionIds = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            question.setQuestionNumber(i + 1);
            question.setInterview(interview);
            question.setGenerationMode("PRE_GENERATED"); // Mark as pre-generated (not dynamic)
            // FIX: Preserve category/difficulty from Ollama if set, otherwise default
            if (question.getCategory() == null || question.getCategory().isBlank()) {
                question.setCategory("GENERAL");
            }
            if (question.getDifficulty() == null || question.getDifficulty().isBlank()) {
                question.setDifficulty("MEDIUM");
            }
            question = questionRepository.save(question); // Save to get ID

            // FIX: Generate TTS audio for each question — if TTS fails, log warning and continue (never fail the whole interview)
            try {
                String audioS3Key = textToSpeechService.generateSpeech(question.getQuestionText(), question.getId());
                question.setAudioUrl(audioS3Key); // FIX: Store the audio S3 key on the question
                questionRepository.save(question); // FIX: Persist the audio URL
                log.info("TTS audio generated for question {} (interview {})", question.getId(), interview.getId());
            } catch (Exception ttsEx) {
                // FIX: TTS failure is non-fatal — question will display as text-only
                log.warn("TTS generation failed for question {} — interview will continue without audio: {}",
                        question.getId(), ttsEx.getMessage());
                question.setAudioUrl(null); // FIX: Explicitly null so frontend knows to use text-only mode
            }

            questionIds.add(question.getId());
        }

        // Add questions to interview for immediate DTO mapping
        interview.getQuestions().addAll(questions);

        log.info("Saved {} questions for interview ID: {}", questions.size(), interview.getId());

        // FIX: Transition directly to IN_PROGRESS — audio generation is fast, no need for GENERATING_VIDEOS state
        interview.transitionTo(InterviewStatus.IN_PROGRESS); // FIX: Use transitionTo() for state machine enforcement (CREATED → IN_PROGRESS allowed after D-ID removal)
        interviewRepository.save(interview);
        log.info("Interview {} transitioned to IN_PROGRESS", interview.getId());

        // ── Publish event (dispatched after transaction commit) ──
        eventPublisher.publishEvent(new QuestionsCreatedEvent(this, interview.getId(), questionIds));

        log.info("QuestionsCreatedEvent published for interview ID: {} with {} question IDs",
                interview.getId(), questionIds.size());

        return mapToInterviewDTO(interview, questions);
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Presigned URL Upload Flow (P1 — direct-to-S3)
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate a presigned PUT URL for direct-to-S3 video upload.
     *
     * <p>
     * The frontend uses this URL to upload the video response file
     * directly to S3 via HTTP PUT, bypassing the backend server entirely.
     * This eliminates the bandwidth and memory bottleneck of proxying
     * large video files through the application server.
     * </p>
     *
     * <h3>Flow:</h3>
     * <ol>
     * <li>Frontend calls this endpoint to get a presigned PUT URL</li>
     * <li>Frontend uploads the video directly to S3 using the URL</li>
     * <li>Frontend calls {@link #confirmUpload} with the S3 key</li>
     * </ol>
     *
     * @param userId      the authenticated user's ID
     * @param interviewId the interview this response belongs to
     * @param questionId  the question being answered
     * @param contentType the MIME type of the upload (e.g., "video/webm")
     * @return a DTO containing the presigned PUT URL, S3 key, and expiry
     * @throws RuntimeException if interview not found, not owned by user,
     *                          or not in a state that accepts responses
     */
    @Transactional(readOnly = true)
    public PresignedUrlResponse generateUploadUrl(Long userId, Long interviewId,
            Long questionId, String contentType) {
        log.info("Generating presigned upload URL: user={}, interview={}, question={}",
                userId, interviewId, questionId);

        // Validate interview ownership and state
        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException("Interview is not in progress. Current status: " + interview.getStatus());
        }

        // Validate question belongs to interview
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + questionId));

        if (!question.getInterview().getId().equals(interviewId)) {
            throw new RuntimeException("Question does not belong to this interview");
        }

        // Check for existing response (prevent duplicate uploads)
        Optional<Response> existingResponse = responseRepository.findByQuestionId(questionId);
        if (existingResponse.isPresent()) {
            throw new RuntimeException("Response already submitted for question: " + questionId);
        }

        // Generate S3 key and presigned PUT URL
        String effectiveContentType = (contentType != null && !contentType.isBlank())
                ? contentType
                : "video/webm";

        String s3Key = videoStorageService.buildVideoResponseKey(userId, interviewId, questionId);
        String uploadUrl = videoStorageService.generatePresignedPutUrl(s3Key, effectiveContentType);

        log.info("Presigned PUT URL generated: s3Key={}, interview={}, question={}",
                s3Key, interviewId, questionId);

        // Default presigned PUT duration is 15 minutes (900 seconds)
        return new PresignedUrlResponse(uploadUrl, s3Key, 900);
    }

    /**
     * Confirm a direct-to-S3 video upload and create the Response entity.
     *
     * <p>
     * Called by the frontend after it has successfully uploaded a video
     * file to S3 using a presigned PUT URL. This method:
     * </p>
     * <ol>
     * <li>Validates the interview ownership and state</li>
     * <li>Validates the question belongs to the interview</li>
     * <li>Checks for duplicate responses (idempotency)</li>
     * <li>Verifies the S3 object exists (HEAD request)</li>
     * <li>Creates and persists the Response entity with the S3 key</li>
     * <li>Triggers async transcription via AssemblyAI</li>
     * </ol>
     *
     * <h3>Idempotency:</h3>
     * <p>
     * If a response already exists for the given question, this method
     * throws an exception. The frontend should check the question's
     * {@code answered} flag before calling this endpoint.
     * </p>
     *
     * @param userId      the authenticated user's ID
     * @param interviewId the interview this response belongs to
     * @param request     the confirm-upload request with question ID, S3 key, etc.
     * @return a success message
     * @throws RuntimeException if validation fails, S3 object not found, etc.
     */
    @Transactional
    public String confirmUpload(Long userId, Long interviewId, ConfirmUploadRequest request) {
        log.info("Confirming upload: user={}, interview={}, question={}, s3Key={}",
                userId, interviewId, request.getQuestionId(), request.getS3Key());

        // Validate interview ownership and state
        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException("Interview is not in progress. Current status: " + interview.getStatus());
        }

        // Validate question belongs to interview
        Long questionId = request.getQuestionId();
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + questionId));

        if (!question.getInterview().getId().equals(interviewId)) {
            throw new RuntimeException("Question does not belong to this interview");
        }

        // Check for duplicate response
        Optional<Response> existingResponse = responseRepository.findByQuestionId(questionId);
        if (existingResponse.isPresent()) {
            throw new RuntimeException("Response already submitted for question: " + questionId);
        }

        // Verify the S3 object actually exists
        String s3Key = request.getS3Key();
        if (!videoStorageService.fileExists(s3Key)) {
            throw new RuntimeException("Upload not found in S3. Please re-upload the video. S3 key: " + s3Key);
        }

        // Issue 18: Validate video duration
        Integer videoDuration = request.getVideoDuration();
        int minDuration = 5;  // Minimum 5 seconds
        int maxDuration = 300; // Maximum 5 minutes
        if (videoDuration != null && (videoDuration < minDuration || videoDuration > maxDuration)) {
            throw new RuntimeException("Video duration must be between " + minDuration + " and " + maxDuration + " seconds.");
        }

        // Create Response entity with S3 key (not a presigned URL)
        Response response = new Response();
        response.setQuestion(question);
        response.setInterview(interview);
        response.setUser(interview.getUser());
        response.setVideoUrl(s3Key); // P1: stores S3 key, not presigned URL
        response.setVideoDuration(videoDuration);
        responseRepository.save(response);

        log.info("Response saved for question {}: s3Key={}", questionId, s3Key);

        // Trigger async transcription
        // The S3 key will be resolved to a presigned GET URL inside SpeechToTextService
        try {
            speechToTextService.transcribeVideoAsync(s3Key, questionId);
            log.info("Async transcription triggered for question {}", questionId);
        } catch (Exception e) {
            log.warn("Failed to initiate transcription for question {} — can be retried later: {}",
                    questionId, e.getMessage());
        }

        return "Response uploaded and confirmed successfully";
    }

    // ════════════════════════════════════════════════════════════════
    // 3. Legacy Video Response Submission (backward compatibility)
    // ════════════════════════════════════════════════════════════════

    /**
     * Submit a video response to a question (legacy server-proxied upload).
     *
     * <p>
     * <strong>Deprecated:</strong> This method proxies the video file through
     * the backend server, consuming bandwidth and memory. Use the presigned URL
     * flow ({@link #generateUploadUrl} + {@link #confirmUpload}) instead.
     * </p>
     *
     * <p>
     * Retained for backward compatibility with frontends that haven't been
     * updated to use the presigned URL flow.
     * </p>
     *
     * <h3>P1 Change:</h3>
     * <p>
     * Now stores the S3 object key in the Response entity instead of a
     * presigned GET URL. The S3 key is resolved to a presigned URL on-demand
     * when building DTOs.
     * </p>
     *
     * @param userId      the authenticated user's ID
     * @param interviewId the interview this response belongs to
     * @param questionId  the question being answered
     * @param videoFile   the video file uploaded via multipart form
     * @return a success message
     */
    @Transactional
    public String submitResponse(Long userId, Long interviewId, Long questionId, MultipartFile videoFile) {
        log.info("Submitting response (legacy): user={}, interview={}, question={}",
                userId, interviewId, questionId);

        // Validate interview belongs to user
        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException("Interview is not in progress. Current status: " + interview.getStatus());
        }

        // Validate question belongs to interview
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + questionId));

        if (!question.getInterview().getId().equals(interviewId)) {
            throw new RuntimeException("Question does not belong to this interview");
        }

        // Check for duplicate response
        Optional<Response> existingResponse = responseRepository.findByQuestionId(questionId);
        if (existingResponse.isPresent()) {
            throw new RuntimeException("Response already submitted for question: " + questionId);
        }

        // Upload video to S3 — P1: returns S3 key (not presigned URL)
        String s3Key = videoStorageService.uploadVideo(videoFile, userId, interviewId, questionId);

        // Save Response entity with S3 key
        Response response = new Response();
        response.setQuestion(question);
        response.setInterview(interview);
        response.setUser(interview.getUser());
        response.setVideoUrl(s3Key); // P1: stores S3 key, not presigned URL
        responseRepository.save(response);

        log.info("Response saved for question {}: s3Key={}", questionId, s3Key);

        // Trigger async transcription
        // SpeechToTextService handles S3 key → presigned URL resolution internally
        try {
            speechToTextService.transcribeVideoAsync(s3Key, questionId);
            log.info("Async transcription triggered for question {}", questionId);
        } catch (Exception e) {
            log.warn("Failed to initiate transcription for question {} — can be retried later: {}",
                    questionId, e.getMessage());
        }

        return "Response submitted successfully";
    }

    // ════════════════════════════════════════════════════════════════
    // 4. Get Interview Details
    // ════════════════════════════════════════════════════════════════

    /**
     * Fetch interview with all questions and response status.
     *
     * <p>
     * P1: Avatar video URLs and response video URLs in the returned DTO
     * are now generated on-demand as presigned GET URLs from stored S3 keys.
     * This ensures URLs are always fresh and valid.
     * </p>
     *
     * @param interviewId the interview to fetch
     * @param userId      the authenticated user's ID (for ownership validation)
     * @return the interview DTO with questions, avatar URLs, and answer status
     */
    @Transactional(readOnly = true)
    public InterviewDTO getInterview(Long interviewId, Long userId) {
        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        List<Question> questions = questionRepository.findByInterviewIdOrderByQuestionNumber(interviewId);

        return mapToInterviewDTO(interview, questions);
    }

    // ════════════════════════════════════════════════════════════════
    // 5. Complete Interview
    // ════════════════════════════════════════════════════════════════

    /**
     * Mark an interview as complete and trigger async feedback generation.
     *
     * <p>
     * Transitions the interview from {@code IN_PROGRESS} to {@code PROCESSING}.
     * The {@link AIFeedbackService} generates feedback asynchronously, and upon
     * completion updates the interview status to {@code COMPLETED}.
     * </p>
     *
     * <p>
     * If feedback generation fails, the
     * {@link com.interview.platform.task.InterviewRecoveryTask} will detect
     * the interview stuck in {@code PROCESSING} and transition it to
     * {@code FAILED} after the configured timeout (default: 30 minutes).
     * </p>
     *
     * @param interviewId the interview to complete
     * @param userId      the authenticated user's ID
     * @return a message indicating the interview is being processed
     */
    @Transactional
    public String completeInterview(Long interviewId, Long userId) {
        log.info("Completing interview ID: {} for user: {}", interviewId, userId);

        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException("Interview is not in progress. Current status: " + interview.getStatus());
        }

        // Validate that at least one response has been submitted
        List<Response> responses = responseRepository.findByInterviewId(interviewId);
        if (responses.isEmpty()) {
            throw new RuntimeException("Cannot complete interview without any responses");
        }

        // AUDIT-FIX: Use transitionTo() for state machine enforcement instead of raw setStatus()
        interview.transitionTo(InterviewStatus.PROCESSING);
        interview.setCompletedAt(LocalDateTime.now());
        interviewRepository.save(interview);

        log.info("Interview ID: {} status set to PROCESSING", interviewId);

        // ── Consolidate transcriptions before feedback ────────────────
        // For any Response that still has null transcription (because
        // AssemblyAI hasn't finished yet), check if a pending
        // transcription result is available and apply it.
        // This prevents feedback from seeing "No response recorded".
        List<Response> missingTranscript = responses.stream()
                .filter(r -> r.getTranscription() == null
                        || r.getTranscription().isBlank())
                .collect(Collectors.toList());

        if (!missingTranscript.isEmpty()) {
            log.warn("Interview {}: {} response(s) still have null " +
                    "transcription at completion time — attempting " +
                    "synchronous fallback fetch",
                    interviewId, missingTranscript.size());

            for (Response r : missingTranscript) {
                try {
                    // Attempt to fetch completed transcription from
                    // SpeechToTextService if available
                    String transcript =
                            speechToTextService.getTranscriptionResult(
                                    r.getVideoUrl());
                    if (transcript != null && !transcript.isBlank()) {
                        r.setTranscription(transcript);
                        responseRepository.save(r);
                        log.info("Late transcription applied for question {}",
                                r.getQuestion().getId());
                    }
                } catch (Exception e) {
                    log.warn("Could not retrieve late transcription for " +
                            "question {} — will use available text",
                            r.getQuestion().getId(), e);
                }
            }
        }
        // ── End consolidation ─────────────────────────────────────────

        // NOW trigger async feedback — transcriptions are as complete
        // as they can be at this point
        try {
            aiFeedbackService.generateFeedbackAsync(interviewId)
                    .thenAccept(feedback -> {
                        // FIX: Transition to COMPLETED with retry to handle optimistic locking.
                        // AIFeedbackService.generateFeedback() already saved the interview
                        // (bumping @Version), so we must re-fetch to get the latest version.
                        transitionInterviewWithRetry(interviewId, InterviewStatus.COMPLETED);
                        log.info("Interview ID: {} completed with score: {}",
                                interviewId, feedback.getOverallScore());

                        // Clean up video files after feedback is generated to save storage
                        // Keep transcriptions in database for history
                        cleanupVideoFiles(interviewId);
                    })
                    .exceptionally(ex -> {
                        log.error("Async feedback generation failed. Transitioning interview ID: {} to FAILED",
                                interviewId, ex);
                        // FIX: Transition to FAILED with retry to handle optimistic locking
                        transitionInterviewWithRetry(interviewId, InterviewStatus.FAILED);
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to initiate feedback generation for interview ID: {}",
                    interviewId, e);
        }

        return "Interview submitted for processing. Feedback will be generated shortly.";
    }

    // ════════════════════════════════════════════════════════════════
    // 5b. Terminate Interview (Proctoring Disqualification)
    // ════════════════════════════════════════════════════════════════

    /**
     * Terminate an interview due to proctoring violations.
     *
     * <p>Transitions the interview from {@code IN_PROGRESS} to
     * {@code DISQUALIFIED}. Called when the frontend proctoring system
     * detects that the candidate has exceeded the maximum number of
     * allowed violations (tab switches, window blur, face not detected).</p>
     *
     * @param interviewId the interview to terminate
     * @param userId      the authenticated user's ID (for ownership validation)
     * @param reason      human-readable reason for termination
     * @return a confirmation message
     * @throws RuntimeException if interview not found, not owned by user,
     *                          or not in IN_PROGRESS state
     */
    @Transactional
    public String terminateInterview(Long interviewId, Long userId, String reason) { // FIX: New method for proctoring disqualification (Issue 3)
        log.info("Terminating interview (proctoring) ID: {} for user: {}, reason: {}", interviewId, userId, reason);

        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId) // FIX: Verify authenticated user owns this interview
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) { // FIX: Only IN_PROGRESS interviews can be terminated by proctoring
            throw new RuntimeException("Interview is not in progress. Current status: " + interview.getStatus());
        }

        interview.transitionTo(InterviewStatus.DISQUALIFIED); // FIX: Use state machine enforcement for DISQUALIFIED transition
        interview.setCompletedAt(LocalDateTime.now()); // FIX: Record termination timestamp
        interviewRepository.save(interview);

        log.info("Interview ID: {} terminated by proctoring system. Reason: {}", interviewId, reason); // FIX: Log proctoring termination for audit trail

        return "Interview terminated by proctoring system: " + reason;
    }

    // ════════════════════════════════════════════════════════════════
    // 6. Interview History
    // ════════════════════════════════════════════════════════════════

    /**
     * Fetch all interviews for a user (lightweight — no question details).
     *
     * @param userId the authenticated user's ID
     * @return list of interview DTOs without question details
     */
    @Transactional(readOnly = true)
    public List<InterviewDTO> getInterviewHistory(Long userId) {
        return getInterviewHistory(userId, 0, 20);
    }

    /**
     * Get paginated interview history for a user (Issue 3: Pagination).
     *
     * @param userId   the authenticated user's ID
     * @param page     page number (0-indexed)
     * @param size     page size
     * @return paginated list of interview DTOs
     */
    @Transactional(readOnly = true)
    public List<InterviewDTO> getInterviewHistory(Long userId, int page, int size) {
        Page<Interview> interviews = interviewRepository.findByUserIdOrderByStartedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(page, size));

        List<InterviewDTO> history = new ArrayList<>();
        for (Interview interview : interviews) {
            InterviewDTO dto = new InterviewDTO();
            dto.setInterviewId(interview.getId());
            dto.setStatus(interview.getStatus().name());
            dto.setType(interview.getType().name());
            dto.setOverallScore(interview.getOverallScore());
            dto.setStartedAt(interview.getStartedAt());
            dto.setCompletedAt(interview.getCompletedAt());

            if (interview.getJobRole() != null) {
                dto.setJobRoleTitle(interview.getJobRole().getTitle());
            }

            dto.setQuestions(null);

            history.add(dto);
        }

        return history;
    }

    // ════════════════════════════════════════════════════════════════
    // 7. Delete Interview
    // ════════════════════════════════════════════════════════════════

    /**
     * Delete an interview and all its associated data.
     * Issue 12: Also deletes associated video files from storage.
     *
     * @param interviewId the interview to delete
     * @param userId      the authenticated user's ID
     */
    @Transactional
    public void deleteInterview(Long interviewId, Long userId) {
        log.info("Deleting interview ID: {} for user: {}", interviewId, userId);

        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        // Issue 12: Delete video files from storage before deleting database records
        List<Response> responses = responseRepository.findByInterviewId(interviewId);
        for (Response response : responses) {
            if (response.getVideoUrl() != null && !response.getVideoUrl().isBlank()) {
                try {
                    videoStorageService.deleteFile(response.getVideoUrl());
                    log.debug("Deleted video file: {}", response.getVideoUrl());
                } catch (Exception e) {
                    log.warn("Failed to delete video file: {} - {}", response.getVideoUrl(), e.getMessage());
                }
            }
        }

        // Delete responses explicitly first to prevent JPA cascade order constraint
        // violations
        responseRepository.deleteAll(responses);

        // Then let cascading deletes handle questions and feedback
        interviewRepository.delete(interview);

        log.info("Successfully deleted interview ID: {} with {} video files", interviewId, responses.size());
    }

    /**
     * Clean up video files after interview completion to save storage.
     * Transcription data is preserved in the database for history.
     *
     * @param interviewId the interview to clean up
     */
    private void cleanupVideoFiles(Long interviewId) {
        List<Response> responses = responseRepository.findByInterviewId(interviewId);
        int deletedCount = 0;
        for (Response response : responses) {
            if (response.getVideoUrl() != null && !response.getVideoUrl().isBlank()) {
                try {
                    videoStorageService.deleteFile(response.getVideoUrl());
                    response.setVideoUrl(null);
                    responseRepository.save(response);
                    deletedCount++;
                } catch (Exception e) {
                    log.warn("Failed to delete video file during cleanup: {} - {}",
                            response.getVideoUrl(), e.getMessage());
                }
            }
        }
        log.info("Cleaned up {} video files for interview ID: {}", deletedCount, interviewId);
    }

    /**
     * Transition an interview to a new status with optimistic lock retry.
     *
     * <p>Re-fetches the interview from the database to get the latest @Version,
     * then transitions to the target status. Retries up to 3 times on
     * optimistic locking conflicts.</p>
     *
     * @param interviewId the interview to transition
     * @param target      the target status
     */
    private void transitionInterviewWithRetry(Long interviewId, InterviewStatus target) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Interview fresh = interviewRepository.findById(interviewId)
                        .orElseThrow(() -> new RuntimeException("Interview not found: " + interviewId));
                fresh.transitionTo(target);
                if (target == InterviewStatus.COMPLETED) {
                    fresh.setCompletedAt(LocalDateTime.now());
                }
                interviewRepository.save(fresh);
                log.info("Transitioned interview {} to {} (attempt {})", interviewId, target, attempt);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Optimistic lock conflict transitioning interview {} to {} (attempt {}/{})",
                        interviewId, target, attempt, maxRetries);
                if (attempt == maxRetries) {
                    log.error("Failed to transition interview {} to {} after {} retries",
                            interviewId, target, maxRetries, e);
                }
                try { Thread.sleep(100L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (IllegalStateException e) {
                // Invalid state transition — the interview is already in a terminal state
                log.warn("Invalid state transition for interview {} to {}: {}",
                        interviewId, target, e.getMessage());
                return;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 8. Hybrid Interview: Submit Answer and Get Next Question
    // ════════════════════════════════════════════════════════════════

    /**
     * Submit an answer to a question and get the next question (hybrid mode).
     *
     * <p>In hybrid mode, this method handles the transition between questions:
     * <ul>
     *   <li>Records the user's answer for the current question</li>
     *   <li>If more questions remain and we're in the DYNAMIC ZONE, generates
     *       the next question adaptively based on all previous Q&A pairs</li>
     *   <li>Generates TTS audio for the new question</li>
     *   <li>Returns the next question or indicates interview completion</li>
     * </ul>
     *
     * <p>This method is idempotent for the answer submission part — if an answer
     * already exists for the question, it returns the next question without
     * re-recording the answer.</p>
     *
     * @param userId           the authenticated user's ID
     * @param interviewId      the interview ID
     * @param currentQuestionId the question being answered
     * @param submission       the answer submission DTO
     * @return NextQuestionResponseDTO with the next question or completion status
     */
    @Transactional
    public NextQuestionResponseDTO submitAnswerAndGetNext(
            Long userId,
            Long interviewId,
            Long currentQuestionId,
            AnswerSubmissionDTO submission) {

        log.info("submitAnswerAndGetNext: user={}, interview={}, question={}",
                userId, interviewId, currentQuestionId);

        // ── Validate interview ownership and state ───────────────────
        Interview interview = interviewRepository.findByIdAndUserId(interviewId, userId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found or does not belong to user: " + interviewId));

        if (interview.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException("Interview is not in progress. Current status: " + interview.getStatus());
        }

        // ── Validate current question belongs to interview ───────────
        Question currentQuestion = questionRepository.findById(currentQuestionId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + currentQuestionId));

        if (!currentQuestion.getInterview().getId().equals(interviewId)) {
            throw new RuntimeException("Question does not belong to this interview");
        }

        // ── Record answer (if not already recorded) ──────────────────
        Optional<Response> existingResponse = responseRepository.findByQuestionId(currentQuestionId);
        if (existingResponse.isEmpty()) {
            // New response — create full record
            Response response = new Response();
            response.setQuestion(currentQuestion);
            response.setInterview(interview);
            response.setUser(interview.getUser());
            response.setTranscription(submission.getAnswerTranscript());
            response.setVideoUrl(submission.getAnswerVideoUrl());
            response.setVideoDuration(submission.getDurationSeconds());
            responseRepository.save(response);
            log.info("Answer recorded for question {} (interview {})", currentQuestionId, interviewId);
        } else {
            // Response already exists (from confirm-upload path).
            // Update the transcription if the DTO has one — do NOT skip.
            Response existing = existingResponse.get();
            String incomingTranscript = submission.getAnswerTranscript();
            if (incomingTranscript != null && !incomingTranscript.isBlank()) {
                existing.setTranscription(incomingTranscript);
                responseRepository.save(existing);
                log.info("Updated transcription for existing response on question {} (interview {})",
                    currentQuestionId, interviewId);
            } else {
                log.info("Existing response for question {} has no new transcript — keeping current value",
                    currentQuestionId);
            }
        }

        // ── Determine total questions and current position ───────────
        Integer totalQuestions = interview.getTotalQuestions();
        if (totalQuestions == null) {
            // Fallback for interviews created before hybrid mode was added
            totalQuestions = questionRepository.findByInterviewIdOrderByQuestionNumber(interviewId).size();
        }

        int currentQuestionNumber = currentQuestion.getQuestionNumber();
        int nextQuestionNumber = currentQuestionNumber + 1;

        // ── Check if interview is complete ───────────────────────────
        if (nextQuestionNumber > totalQuestions) {
            log.info("Interview {} complete after question {}", interviewId, currentQuestionNumber);
            return NextQuestionResponseDTO.completed(totalQuestions);
        }

        // ── Get or generate the next question ────────────────────────
        Question nextQuestion = findOrGenerateNextQuestion(
                interview, currentQuestion, nextQuestionNumber, totalQuestions);

        // ── Build response ───────────────────────────────────────────
        return new NextQuestionResponseDTO()
                .nextQuestionId(nextQuestion.getId())
                .nextQuestionText(nextQuestion.getQuestionText())
                .nextQuestionNumber(nextQuestion.getQuestionNumber())
                .nextQuestionAudioUrl(resolveToPresignedUrl(nextQuestion.getAudioUrl()))
                .nextQuestionCategory(nextQuestion.getCategory())
                .nextQuestionDifficulty(nextQuestion.getDifficulty())
                .generationMode(nextQuestion.getGenerationMode())
                .totalQuestions(totalQuestions)
                .interviewComplete(false);
    }

    /**
     * Find an existing question or generate a new dynamic question.
     *
     * <p>In hybrid mode, questions in the PRE_GENERATED zone already exist
     * in the database. Questions in the DYNAMIC zone are generated on-demand
     * based on the interview history.</p>
     */
    private Question findOrGenerateNextQuestion(
            Interview interview,
            Question currentQuestion,
            int nextQuestionNumber,
            int totalQuestions) {

        Long interviewId = interview.getId();

        // Check if the question already exists (pre-generated or previously generated)
        Optional<Question> existingQuestion = questionRepository
                .findByInterviewIdAndQuestionNumber(interviewId, nextQuestionNumber);

        if (existingQuestion.isPresent()) {
            log.debug("Question {} already exists for interview {}", nextQuestionNumber, interviewId);
            return existingQuestion.get();
        }

        // Question doesn't exist — we're in the DYNAMIC zone, generate it
        log.info("Generating dynamic question {} for interview {}", nextQuestionNumber, interviewId);

        // Build Q&A history from all previous questions and answers
        List<QuestionAnswerPair> qaHistory = buildQAHistory(interviewId);

        // Generate the next adaptive question via Ollama
        Question newQuestion = ollamaService.generateNextAdaptiveQuestion(
                interview, qaHistory, nextQuestionNumber, totalQuestions);

        // Set interview reference and metadata
        newQuestion.setInterview(interview);
        newQuestion.setQuestionNumber(nextQuestionNumber);
        newQuestion.setGenerationMode("DYNAMIC");
        newQuestion.setGeneratedAfterQuestionId(currentQuestion.getId());

        // Default category/difficulty if not set by Ollama
        if (newQuestion.getCategory() == null || newQuestion.getCategory().isBlank()) {
            newQuestion.setCategory("TECHNICAL");
        }
        if (newQuestion.getDifficulty() == null || newQuestion.getDifficulty().isBlank()) {
            newQuestion.setDifficulty("MEDIUM");
        }

        // Save to get ID before TTS generation
        newQuestion = questionRepository.save(newQuestion);

        // Generate TTS audio for the new question
        try {
            String audioS3Key = textToSpeechService.generateSpeech(
                    newQuestion.getQuestionText(), newQuestion.getId());
            newQuestion.setAudioUrl(audioS3Key);
            newQuestion = questionRepository.save(newQuestion);
            log.info("TTS audio generated for dynamic question {} (interview {})",
                    newQuestion.getId(), interviewId);
        } catch (Exception e) {
            log.warn("TTS generation failed for dynamic question {} — continuing without audio: {}",
                    newQuestion.getId(), e.getMessage());
        }

        return newQuestion;
    }

    /**
     * Build the Q&A history for all answered questions in the interview.
     *
     * <p>Returns a list of QuestionAnswerPair DTOs in chronological order,
     * used as context for generating the next adaptive question.</p>
     */
    private List<QuestionAnswerPair> buildQAHistory(Long interviewId) {
        List<Question> questions = questionRepository.findByInterviewIdOrderByQuestionNumber(interviewId);
        List<QuestionAnswerPair> history = new ArrayList<>();

        for (Question question : questions) {
            Optional<Response> responseOpt = responseRepository.findByQuestionId(question.getId());
            if (responseOpt.isPresent()) {
                Response response = responseOpt.get();
                history.add(new QuestionAnswerPair(
                        question.getQuestionNumber(),
                        question.getQuestionText(),
                        response.getTranscription() != null ? response.getTranscription() : ""
                ));
            }
        }

        log.debug("Built Q&A history with {} pairs for interview {}", history.size(), interviewId);
        return history;
    }

    // ════════════════════════════════════════════════════════════════
    // DTO Mapping (with on-demand presigned URL generation)
    // ════════════════════════════════════════════════════════════════

    /**
     * Map an Interview entity and its questions to an InterviewDTO.
     *
     * <h3>P1 Presigned URL Generation:</h3>
     * <p>
     * S3 keys stored in entity fields ({@code avatarVideoUrl}, {@code videoUrl})
     * are resolved to short-lived presigned GET URLs when building the DTO.
     * This ensures the frontend always receives valid, non-expired URLs.
     * </p>
     *
     * <p>
     * If a field is null, blank, or already a URL (legacy data), it is
     * handled gracefully:
     * </p>
     * <ul>
     * <li>{@code null} or blank → left as null in the DTO (no avatar/video
     * available)</li>
     * <li>Starts with "http" → used as-is (legacy presigned URL, may be
     * expired)</li>
     * <li>Otherwise → treated as S3 key and resolved to a presigned GET URL</li>
     * </ul>
     *
     * @param interview the interview entity
     * @param questions the list of question entities for this interview
     * @return the populated InterviewDTO
     */
    private InterviewDTO mapToInterviewDTO(Interview interview, List<Question> questions) {
        InterviewDTO dto = new InterviewDTO();
        dto.setInterviewId(interview.getId());
        dto.setStatus(interview.getStatus().name());
        dto.setType(interview.getType().name());
        dto.setOverallScore(interview.getOverallScore());
        dto.setStartedAt(interview.getStartedAt());
        dto.setCompletedAt(interview.getCompletedAt());

        if (interview.getJobRole() != null) {
            dto.setJobRoleTitle(interview.getJobRole().getTitle());
        }

        // Map questions with on-demand presigned URL generation
        List<InterviewQuestionDTO> questionDTOs = new ArrayList<>();
        for (Question question : questions) {
            InterviewQuestionDTO qDto = new InterviewQuestionDTO();
            qDto.setQuestionId(question.getId());
            qDto.setQuestionNumber(question.getQuestionNumber());
            qDto.setQuestionText(question.getQuestionText());
            qDto.setCategory(question.getCategory());
            qDto.setDifficulty(question.getDifficulty());

            // P1: Resolve avatar video S3 key to presigned GET URL
            qDto.setAvatarVideoUrl(resolveToPresignedUrl(question.getAvatarVideoUrl()));
            qDto.setAudioUrl(resolveToPresignedUrl(question.getAudioUrl())); // FIX: Map ElevenLabs TTS audio URL alongside avatar video URL

            // Check if response exists for this question
            Optional<Response> responseOpt = responseRepository.findByQuestionId(question.getId());
            qDto.setAnswered(responseOpt.isPresent());

            // P1: Attach response video URL and transcription for review page
            if (responseOpt.isPresent()) {
                Response resp = responseOpt.get();
                qDto.setResponseVideoUrl(resolveToPresignedUrl(resp.getVideoUrl()));
                qDto.setResponseTranscription(resp.getTranscription());
            }

            questionDTOs.add(qDto);
        }

        dto.setQuestions(questionDTOs);
        return dto;
    }

    /**
     * Resolve an S3 key or URL to a presigned GET URL for frontend consumption.
     *
     * <p>
     * Handles three cases:
     * </p>
     * <ol>
     * <li>{@code null} or blank → returns {@code null} (no media available)</li>
     * <li>Starts with "http" → returns as-is (legacy presigned URL or external
     * URL)</li>
     * <li>Otherwise → treated as S3 key and resolved via
     * {@link VideoStorageService#generatePresignedGetUrl}</li>
     * </ol>
     *
     * <p>
     * This method never throws exceptions — if presigned URL generation
     * fails (e.g., S3 is temporarily unavailable), it logs the error and
     * returns {@code null}, allowing the frontend to fall back to text-only
     * display.
     * </p>
     *
     * @param s3KeyOrUrl the S3 key, URL, or null
     * @return a presigned GET URL, the original URL, or null
     */
    private String resolveToPresignedUrl(String s3KeyOrUrl) {
        if (s3KeyOrUrl == null || s3KeyOrUrl.isBlank()) {
            return null;
        }

        // Legacy presigned URLs or external URLs — pass through
        if (s3KeyOrUrl.startsWith("http://") || s3KeyOrUrl.startsWith("https://")) {
            return s3KeyOrUrl;
        }

        // S3 key — generate presigned GET URL
        try {
            return videoStorageService.generatePresignedGetUrl(s3KeyOrUrl);
        } catch (Exception e) {
            log.warn("Failed to generate presigned GET URL for key={}: {}", s3KeyOrUrl, e.getMessage());
            return null;
        }
    }
}
