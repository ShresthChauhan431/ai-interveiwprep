package com.interview.platform.task;

import com.interview.platform.model.Interview;
import com.interview.platform.model.InterviewStatus;
import com.interview.platform.repository.InterviewRepository;
import com.interview.platform.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled task that detects and recovers interviews stuck in transient states.
 *
 * <h3>Problem:</h3>
 * <p>The event-driven avatar pipeline ({@code AvatarPipelineListener}) runs
 * asynchronously on a virtual thread after the originating transaction commits.
 * If the application restarts, the JVM crashes, or the listener throws an
 * unrecoverable error, the async thread is lost and the interview remains
 * stuck in {@code GENERATING_VIDEOS} indefinitely. The user sees a perpetual
 * "loading" state and cannot proceed with their interview.</p>
 *
 * <p>Similarly, interviews can get stuck in {@code PROCESSING} if the feedback
 * generation async task fails silently (e.g., OpenAI circuit breaker opens
 * and stays open, or the server restarts mid-processing).</p>
 *
 * <h3>Solution:</h3>
 * <p>This scheduled task runs at a fixed interval (default: every 5 minutes)
 * and queries for interviews that have been in a transient state longer than
 * the configured timeout. It transitions them to a recoverable state:</p>
 *
 * <table>
 *   <caption>Recovery Transitions</caption>
 *   <tr><th>Stuck State</th><th>Timeout</th><th>Recovery Action</th></tr>
 *   <tr>
 *     <td>{@code GENERATING_VIDEOS}</td>
 *     <td>15 minutes (default)</td>
 *     <td>Transition to {@code IN_PROGRESS} — the user can proceed with
 *         text-only questions. Avatar videos that were already generated
 *         remain available; questions without avatars show text-only fallback.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code PROCESSING}</td>
 *     <td>30 minutes (default)</td>
 *     <td>Transition to {@code FAILED} — the user is prompted to retry
 *         or contact support. A manual re-trigger of feedback generation
 *         can be added as a future enhancement.</td>
 *   </tr>
 * </table>
 *
 * <h3>Idempotency:</h3>
 * <p>The recovery logic is idempotent — running it multiple times on the
 * same interview produces the same result. The query only selects interviews
 * that are still in the stuck state, so already-recovered interviews are
 * not affected.</p>
 *
 * <h3>Configuration:</h3>
 * <ul>
 *   <li>{@code app.recovery.interval-ms} — how often the task runs (default: 300000 = 5 min)</li>
 *   <li>{@code app.recovery.video-generation-timeout-minutes} — timeout for
 *       {@code GENERATING_VIDEOS} state (default: 15)</li>
 *   <li>{@code app.recovery.processing-timeout-minutes} — timeout for
 *       {@code PROCESSING} state (default: 30)</li>
 * </ul>
 *
 * <h3>Observability:</h3>
 * <p>Each recovery action is logged at INFO level with the interview ID,
 * original status, elapsed time, and the action taken. This provides an
 * audit trail for debugging and monitoring. In a production setup, these
 * logs should trigger alerts (e.g., via structured logging → CloudWatch
 * metric filter → SNS notification).</p>
 *
 * <h3>Thread Safety:</h3>
 * <p>The {@code @Scheduled} method runs on the Spring scheduling thread pool.
 * The {@code @Transactional} annotation ensures each interview update is
 * atomic. Concurrent execution is prevented by Spring's default single-threaded
 * scheduler — only one instance of this task runs at a time. If the task
 * takes longer than the interval, the next execution is delayed (not skipped).</p>
 *
 * @see com.interview.platform.event.AvatarPipelineListener
 * @see com.interview.platform.model.InterviewStatus
 */
@Component
public class InterviewRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(InterviewRecoveryTask.class);

    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;

    /**
     * Maximum time (in minutes) an interview can remain in GENERATING_VIDEOS
     * before the recovery task transitions it to IN_PROGRESS (text-only fallback).
     *
     * <p>The default of 15 minutes accommodates normal avatar generation time
     * (typically 30-120 seconds per question × 10 questions = 5-20 minutes)
     * with a generous buffer. If D-ID is having issues, 15 minutes is enough
     * to be confident that the pipeline has truly stalled.</p>
     */
    @Value("${app.recovery.video-generation-timeout-minutes:15}")
    private int videoGenerationTimeoutMinutes;

    /**
     * Maximum time (in minutes) an interview can remain in PROCESSING
     * before the recovery task transitions it to FAILED.
     *
     * <p>Feedback generation via OpenAI typically completes in under 30 seconds.
     * A 30-minute timeout is extremely generous and accounts for retry backoff,
     * queue delays, and transient API outages.</p>
     */
    @Value("${app.recovery.processing-timeout-minutes:30}")
    private int processingTimeoutMinutes;

    public InterviewRecoveryTask(InterviewRepository interviewRepository,
                                 QuestionRepository questionRepository) {
        this.interviewRepository = interviewRepository;
        this.questionRepository = questionRepository;
    }

    /**
     * Main recovery sweep — runs at a fixed interval.
     *
     * <p>Checks for interviews stuck in transient states and transitions
     * them to recoverable states. Each stuck interview is logged and
     * transitioned individually so that a failure in one recovery does
     * not block recovery of other interviews.</p>
     *
     * <p>The fixed delay (not fixed rate) ensures there is always at least
     * {@code app.recovery.interval-ms} between the end of one sweep and
     * the start of the next, preventing overlap if a sweep takes a long time.</p>
     */
    @Scheduled(fixedDelayString = "${app.recovery.interval-ms:300000}",
               initialDelayString = "${app.recovery.initial-delay-ms:60000}")
    public void recoverStuckInterviews() {
        log.debug("Interview recovery sweep started");

        int videoRecovered = recoverStuckVideoGeneration();
        int processingRecovered = recoverStuckProcessing();

        if (videoRecovered > 0 || processingRecovered > 0) {
            log.info("Recovery sweep complete: {} video-generation recovered, {} processing recovered",
                    videoRecovered, processingRecovered);
        } else {
            log.debug("Recovery sweep complete: no stuck interviews found");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // GENERATING_VIDEOS recovery
    // ════════════════════════════════════════════════════════════════

    /**
     * Find interviews stuck in {@code GENERATING_VIDEOS} and transition
     * them to {@code IN_PROGRESS} with text-only fallback.
     *
     * <p>The user can proceed to answer questions using text-only display.
     * Any avatar videos that were already generated remain attached to
     * their questions and will display normally. Questions without avatar
     * videos will show text-only on the frontend.</p>
     *
     * @return the number of interviews recovered
     */
    private int recoverStuckVideoGeneration() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(videoGenerationTimeoutMinutes);

        List<Interview> stuckInterviews = interviewRepository
                .findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
                        InterviewStatus.GENERATING_VIDEOS, cutoff);

        if (stuckInterviews.isEmpty()) {
            return 0;
        }

        log.warn("Found {} interviews stuck in GENERATING_VIDEOS (timeout={}min, cutoff={})",
                stuckInterviews.size(), videoGenerationTimeoutMinutes, cutoff);

        int recovered = 0;
        for (Interview interview : stuckInterviews) {
            try {
                recoverVideoGenerationInterview(interview);
                recovered++;
            } catch (Exception e) {
                log.error("Failed to recover interview {} from GENERATING_VIDEOS: {}",
                        interview.getId(), e.getMessage(), e);
            }
        }

        return recovered;
    }

    /**
     * Recover a single interview stuck in GENERATING_VIDEOS.
     *
     * <p>Logs diagnostic information about how many questions have avatar
     * videos (for debugging why the pipeline stalled), then transitions
     * the interview to IN_PROGRESS.</p>
     *
     * @param interview the stuck interview entity
     */
    @Transactional
    public void recoverVideoGenerationInterview(Interview interview) {
        Long interviewId = interview.getId();

        // Diagnostic: count how many questions already have avatar videos
        long totalQuestions = questionRepository.countByInterviewId(interviewId);
        long questionsWithAvatars = questionRepository
                .findByInterviewIdOrderByQuestionNumber(interviewId)
                .stream()
                .filter(q -> q.getAvatarVideoUrl() != null && !q.getAvatarVideoUrl().isBlank())
                .count();

        long elapsedMinutes = java.time.Duration.between(
                interview.getStartedAt(), LocalDateTime.now()).toMinutes();

        log.warn("Recovering interview {} from GENERATING_VIDEOS → IN_PROGRESS: "
                        + "elapsed={}min, avatars={}/{} questions, user={}",
                interviewId, elapsedMinutes, questionsWithAvatars, totalQuestions,
                interview.getUser() != null ? interview.getUser().getId() : "unknown");

        // AUDIT-FIX: Use transitionTo() for state machine enforcement instead of raw setStatus()
        interview.transitionTo(InterviewStatus.IN_PROGRESS);
        interviewRepository.save(interview);

        log.info("Interview {} recovered: GENERATING_VIDEOS → IN_PROGRESS (text-only fallback for {} questions)",
                interviewId, totalQuestions - questionsWithAvatars);
    }

    // ════════════════════════════════════════════════════════════════
    // PROCESSING recovery
    // ════════════════════════════════════════════════════════════════

    /**
     * Find interviews stuck in {@code PROCESSING} and transition them
     * to {@code FAILED}.
     *
     * <p>The {@code PROCESSING} state means the user has completed the
     * interview and is waiting for AI feedback. If feedback generation
     * stalls for longer than the timeout, the interview is marked as
     * {@code FAILED} so the user can see a clear error state instead
     * of waiting indefinitely.</p>
     *
     * <p>A future enhancement could offer a "retry feedback" button on
     * the dashboard that re-triggers {@code AIFeedbackService.generateFeedbackAsync()}
     * for interviews in {@code FAILED} state.</p>
     *
     * <p>Note: We use {@code completedAt} as the reference timestamp for
     * PROCESSING interviews because {@code startedAt} reflects when the
     * interview was first created, not when it entered PROCESSING. If
     * {@code completedAt} is null (shouldn't happen but defensive), we
     * fall back to {@code startedAt}.</p>
     *
     * @return the number of interviews recovered
     */
    private int recoverStuckProcessing() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(processingTimeoutMinutes);

        // For PROCESSING interviews, we ideally want to check completedAt
        // (the time the user submitted for processing). However, our
        // repository method uses startedAt. We'll query using startedAt
        // and filter in-memory, which is fine for the expected small
        // number of stuck interviews.
        List<Interview> stuckInterviews = interviewRepository
                .findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
                        InterviewStatus.PROCESSING, cutoff);

        // Additional filter: check completedAt if available, since a user
        // might have started an interview long ago but only recently
        // submitted it for processing.
        List<Interview> trulyStuck = stuckInterviews.stream()
                .filter(interview -> {
                    LocalDateTime referenceTime = interview.getCompletedAt() != null
                            ? interview.getCompletedAt()
                            : interview.getStartedAt();
                    return referenceTime.isBefore(cutoff);
                })
                .toList();

        if (trulyStuck.isEmpty()) {
            return 0;
        }

        log.warn("Found {} interviews stuck in PROCESSING (timeout={}min, cutoff={})",
                trulyStuck.size(), processingTimeoutMinutes, cutoff);

        int recovered = 0;
        for (Interview interview : trulyStuck) {
            try {
                recoverProcessingInterview(interview);
                recovered++;
            } catch (Exception e) {
                log.error("Failed to recover interview {} from PROCESSING: {}",
                        interview.getId(), e.getMessage(), e);
            }
        }

        return recovered;
    }

    /**
     * Recover a single interview stuck in PROCESSING.
     *
     * <p>Transitions the interview to FAILED status. The user will see an
     * error state on their dashboard and can be prompted to contact support
     * or retry.</p>
     *
     * @param interview the stuck interview entity
     */
    @Transactional
    public void recoverProcessingInterview(Interview interview) {
        Long interviewId = interview.getId();

        LocalDateTime referenceTime = interview.getCompletedAt() != null
                ? interview.getCompletedAt()
                : interview.getStartedAt();
        long elapsedMinutes = java.time.Duration.between(
                referenceTime, LocalDateTime.now()).toMinutes();

        log.warn("Recovering interview {} from PROCESSING → FAILED: "
                        + "elapsed={}min since submission, user={}",
                interviewId, elapsedMinutes,
                interview.getUser() != null ? interview.getUser().getId() : "unknown");

        // AUDIT-FIX: Use transitionTo() for state machine enforcement instead of raw setStatus()
        interview.transitionTo(InterviewStatus.FAILED);
        interviewRepository.save(interview);

        log.info("Interview {} recovered: PROCESSING → FAILED", interviewId);
    }
}
