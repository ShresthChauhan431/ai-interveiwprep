package com.interview.platform.event;

import com.interview.platform.model.Interview;
import com.interview.platform.model.InterviewStatus;
import com.interview.platform.model.Question;
import com.interview.platform.repository.InterviewRepository;
import com.interview.platform.repository.QuestionRepository;
// FIX: Removed unused CachedAvatarService import — D-ID avatar pipeline fully replaced by ElevenLabs TTS
import com.interview.platform.service.SseEmitterService;
import com.interview.platform.service.TextToSpeechService; // FIX: Import TTS service for ElevenLabs audio generation (replaces D-ID video)
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async event listener that orchestrates avatar video generation for
 * newly created interview questions.
 *
 * <h3>Event-Driven Architecture (P1):</h3>
 * <p>
 * This listener receives {@link QuestionsCreatedEvent} after the
 * transaction that created the interview and its questions has committed.
 * It then generates avatar videos for each question using the
 * {@link CachedAvatarService} (which checks for cached versions before
 * invoking the expensive TTS → D-ID pipeline).
 * </p>
 *
 * <h3>Thread Model:</h3>
 * <p>
 * The listener runs on a virtual thread via
 * {@code @Async("avatarTaskExecutor")}.
 * Each question's avatar generation is processed sequentially within the
 * listener to avoid overwhelming the D-ID API rate limits. Since virtual
 * threads yield their carrier thread during {@code Thread.sleep()} in
 * polling loops, this does not block platform threads.
 * </p>
 *
 * <h3>Failure Handling:</h3>
 * <p>
 * Avatar generation failures for individual questions are caught and
 * logged but do not abort the entire pipeline. Questions that fail
 * avatar generation will have a {@code null} avatar video key, and the
 * frontend falls back to text-only display for those questions.
 * </p>
 *
 * <p>
 * If the listener itself fails catastrophically (e.g., server restart
 * kills the virtual thread), the interview remains in
 * {@code GENERATING_VIDEOS} status. The
 * {@link com.interview.platform.task.InterviewRecoveryTask}
 * scheduled task detects interviews stuck in this state for more than
 * 15 minutes and transitions them to {@code IN_PROGRESS} with text-only
 * fallback, ensuring the user is never permanently blocked.
 * </p>
 *
 * <h3>State Machine:</h3>
 *
 * <pre>
 *   GENERATING_VIDEOS ──► IN_PROGRESS  (all videos ready or best-effort)
 *   GENERATING_VIDEOS ──► FAILED       (only on catastrophic error like interview not found)
 * </pre>
 *
 * <h3>Why {@code @TransactionalEventListener(phase = AFTER_COMMIT)}?</h3>
 * <p>
 * Ensures the listener only fires after the questions are committed
 * to the database. Without this, the listener could execute before the
 * transaction commits, leading to "entity not found" errors when
 * querying for the questions by ID.
 * </p>
 *
 * <h3>Why a separate {@code @Transactional(propagation = REQUIRES_NEW)}?</h3>
 * <p>
 * The original transaction that published the event has already
 * committed by the time this listener runs. Each database update
 * (setting avatar video keys, changing interview status) needs its
 * own transaction. Using {@code REQUIRES_NEW} ensures each update
 * is independently committed, so partial progress is saved even if
 * a later question's generation fails.
 * </p>
 *
 * @see QuestionsCreatedEvent
 * @see CachedAvatarService
 * @see com.interview.platform.task.InterviewRecoveryTask
 */
@Component
public class AvatarPipelineListener {

    private static final Logger log = LoggerFactory.getLogger(AvatarPipelineListener.class);

    private final QuestionRepository questionRepository;
    private final InterviewRepository interviewRepository;
    private final SseEmitterService sseEmitterService;
    private final TextToSpeechService textToSpeechService; // FIX: TTS service replaces D-ID CachedAvatarService entirely

    // FIX: Removed CachedAvatarService from constructor — D-ID avatar pipeline fully replaced by ElevenLabs TTS
    public AvatarPipelineListener(QuestionRepository questionRepository,
            InterviewRepository interviewRepository,
            SseEmitterService sseEmitterService,
            TextToSpeechService textToSpeechService) {
        this.questionRepository = questionRepository;
        this.interviewRepository = interviewRepository;
        this.sseEmitterService = sseEmitterService;
        this.textToSpeechService = textToSpeechService;
    }

    /**
     * Handle the {@link QuestionsCreatedEvent} by generating avatar videos
     * for each question.
     *
     * <p>
     * This method runs asynchronously on a virtual thread after the
     * originating transaction has committed. It processes each question
     * sequentially, updating the question entity with the avatar video
     * S3 key as each video is generated or retrieved from cache.
     * </p>
     *
     * <p>
     * After all questions have been processed (successfully or with
     * fallback), the interview status is transitioned from
     * {@code GENERATING_VIDEOS} to {@code IN_PROGRESS}.
     * </p>
     *
     * @param event the event containing the interview ID and question IDs
     */
    @Async("avatarTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQuestionsCreated(QuestionsCreatedEvent event) {
        Long interviewId = event.getInterviewId();
        List<Long> questionIds = event.getQuestionIds();

        log.info("Avatar pipeline started: interviewId={}, questionCount={}",
                interviewId, questionIds.size());

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // ── Process each question ────────────────────────────────
        for (Long questionId : questionIds) {
            try {
                processQuestion(questionId);
                successCount.incrementAndGet();

                // Push SSE event: avatar video ready
                // FIX: Send audioUrl in SSE event instead of videoUrl for TTS audio readiness
                sseEmitterService.send(interviewId, "avatar-ready", Map.of(
                        "questionId", questionId,
                        "audiosReady", successCount.get(), // FIX: Renamed from videosReady to audiosReady
                        "totalQuestions", questionIds.size()));
            } catch (Exception e) {
                failCount.incrementAndGet();
                log.error("Avatar generation failed for question {}: {}",
                        questionId, e.getMessage(), e);

                // Push SSE event: avatar generation failed for this question
                sseEmitterService.send(interviewId, "avatar-failed", Map.of(
                        "questionId", questionId,
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                // Continue to next question — don't abort the pipeline
            }
        }

        // ── Transition interview to IN_PROGRESS ──────────────────
        try {
            transitionToInProgress(interviewId);
        } catch (Exception e) {
            log.error("Failed to transition interview {} to IN_PROGRESS: {}",
                    interviewId, e.getMessage(), e);
            // The recovery task will handle this
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Avatar pipeline completed: interviewId={}, success={}, failed={}, elapsed={}ms",
                interviewId, successCount.get(), failCount.get(), elapsed);
    }

    /**
     * Process a single question: generate (or retrieve cached) avatar video
     * and update the question entity with the S3 key.
     *
     * <p>
     * Each question is processed in its own transaction so that progress
     * is saved incrementally. If avatar generation fails for one question,
     * previously successful updates are not rolled back.
     * </p>
     *
     * @param questionId the ID of the question to process
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processQuestion(Long questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException(
                        "Question not found during avatar pipeline: " + questionId));

        // FIX: Skip if audio already exists (idempotency guard — changed from avatarVideoUrl to audioUrl)
        if (question.getAudioUrl() != null && !question.getAudioUrl().isBlank()) {
            log.debug("Question {} already has TTS audio, skipping: {}", // FIX: Updated log message for TTS audio
                    questionId, question.getAudioUrl());
            return;
        }

        String questionText = question.getQuestionText();
        log.debug("Generating TTS audio for question {}: text='{}'", // FIX: Updated log message from "avatar" to "TTS audio"
                questionId, truncateForLog(questionText, 80));

        // FIX: Replaced D-ID avatar video generation with ElevenLabs TTS audio
        // String avatarS3Key = cachedAvatarService.getOrGenerateAvatar(questionText, questionId);
        // question.setAvatarVideoUrl(avatarS3Key);
        String audioUrl = textToSpeechService.generateAndSaveAudio( // FIX: Call TTS service instead of CachedAvatarService
                question.getQuestionText(), question.getId());
        question.setAudioUrl(audioUrl); // FIX: Set audioUrl instead of avatarVideoUrl

        questionRepository.save(question);

        log.info("TTS audio set for question {}: audioUrl={}", questionId, audioUrl); // FIX: Updated log message for TTS audio
    }

    /**
     * Transition the interview from {@code GENERATING_VIDEOS} to
     * {@code IN_PROGRESS}.
     *
     * <p>
     * This is called after all questions have been processed (with or without
     * failures). The interview is ready for the user to start answering questions.
     * </p>
     *
     * <p>
     * If the interview is no longer in {@code GENERATING_VIDEOS} status
     * (e.g., the recovery task already transitioned it, or it was manually
     * cancelled), this method is a no-op to prevent conflicting state transitions.
     * </p>
     *
     * @param interviewId the interview to transition
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionToInProgress(Long interviewId) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException(
                        "Interview not found during avatar pipeline completion: " + interviewId));

        // Only transition if still in GENERATING_VIDEOS
        // (recovery task or concurrent process might have already transitioned it)
        if (interview.getStatus() != InterviewStatus.GENERATING_VIDEOS) {
            log.warn("Interview {} is not in GENERATING_VIDEOS (current={}), "
                    + "skipping transition to IN_PROGRESS",
                    interviewId, interview.getStatus());
            return;
        }

        interview.setStatus(InterviewStatus.IN_PROGRESS);
        interviewRepository.save(interview);

        log.info("Interview {} transitioned to IN_PROGRESS", interviewId);

        // Push SSE event: interview is ready, then close all connections
        sseEmitterService.send(interviewId, "interview-ready", Map.of(
                "interviewId", interviewId,
                "status", "IN_PROGRESS"));
        sseEmitterService.completeAll(interviewId);
    }

    /**
     * Truncate a string for log output, appending "..." if truncated.
     *
     * @param text   the text to truncate
     * @param maxLen maximum length before truncation
     * @return the truncated text
     */
    private String truncateForLog(String text, int maxLen) {
        if (text == null) {
            return "<null>";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
