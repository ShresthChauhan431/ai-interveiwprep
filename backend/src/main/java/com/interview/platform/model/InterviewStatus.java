package com.interview.platform.model;

/**
 * State machine for interview lifecycle.
 *
 * <pre>
 * CREATED ──► GENERATING_VIDEOS ──► IN_PROGRESS ──► PROCESSING ──► COMPLETED
 *                    │                    │               │
 *                    └────────────────────┴───────────────┴──────► FAILED
 * </pre>
 *
 * <h3>State Descriptions:</h3>
 * <ul>
 *   <li><strong>CREATED:</strong> Interview entity and questions are saved to DB.
 *       No async work has started yet.</li>
 *   <li><strong>GENERATING_VIDEOS:</strong> Avatar video generation (ElevenLabs TTS → D-ID)
 *       is running asynchronously for each question. The frontend should poll or listen
 *       via SSE for readiness. A scheduled recovery task transitions interviews stuck in
 *       this state for more than 15 minutes to IN_PROGRESS (text-only fallback).</li>
 *   <li><strong>IN_PROGRESS:</strong> All avatar videos are ready (or timed out with
 *       text-only fallback). The user can now watch questions and record answers.</li>
 *   <li><strong>PROCESSING:</strong> The user has completed the interview and submitted
 *       it for evaluation. AI feedback generation (OpenAI) is running asynchronously.</li>
 *   <li><strong>COMPLETED:</strong> Feedback has been generated and persisted. The
 *       interview is available for review on the dashboard.</li>
 *   <li><strong>FAILED:</strong> An unrecoverable error occurred during any phase
 *       (e.g., OpenAI question generation failed, critical DB error). The user should
 *       be prompted to retry or contact support.</li>
 * </ul>
 *
 * <h3>P0 Note:</h3>
 * <p>The new states (CREATED, GENERATING_VIDEOS, FAILED) are added in P0 for
 * forward compatibility. The existing service logic continues to use IN_PROGRESS,
 * PROCESSING, and COMPLETED. The P1 event-driven architecture refactor will
 * integrate the full state machine into InterviewService and the new
 * AvatarPipelineListener.</p>
 */
public enum InterviewStatus {

    /** Interview entity saved, questions persisted, no async work started. */
    CREATED,

    /** Avatar videos are being generated asynchronously for each question. */
    GENERATING_VIDEOS,

    /** Videos ready (or timed out). User can record answers. */
    IN_PROGRESS,

    /** User completed the interview. Feedback is being generated. */
    PROCESSING,

    /** Feedback generated and available for review. */
    COMPLETED,

    /** Unrecoverable error — user should retry or contact support. */
    FAILED,

    /** Interview terminated by proctoring system due to violations. */ // FIX: Added DISQUALIFIED status for proctoring termination (Issue 3)
    DISQUALIFIED;

    /**
     * Centralized state machine enforcement (P1-11).
     *
     * <p>Validates whether a transition from this status to the given target
     * is legal according to the interview lifecycle. All status transitions
     * in the codebase should call this method before mutating the entity.</p>
     *
     * <pre>
     *   CREATED ──► GENERATING_VIDEOS ──► IN_PROGRESS ──► PROCESSING ──► COMPLETED
     *                      │                    │               │
     *                      └────────────────────┴───────────────┴──────► FAILED
     * </pre>
     *
     * @param target the desired next status
     * @return {@code true} if the transition is valid
     */
    public boolean canTransitionTo(InterviewStatus target) {
        return switch (this) {
            case CREATED -> target == GENERATING_VIDEOS || target == IN_PROGRESS || target == FAILED; // FIX: Allow CREATED → IN_PROGRESS since D-ID removed and TTS is synchronous (skip GENERATING_VIDEOS)
            case GENERATING_VIDEOS -> target == IN_PROGRESS || target == FAILED;
            case IN_PROGRESS -> target == PROCESSING || target == FAILED || target == DISQUALIFIED; // FIX: Allow IN_PROGRESS → DISQUALIFIED for proctoring termination (Issue 3)
            case PROCESSING -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED, DISQUALIFIED -> false; // FIX: DISQUALIFIED is a terminal state (Issue 3)
        };
    }

    /**
     * Transition to the target status, throwing if the transition is illegal.
     *
     * @param target the desired next status
     * @return the target status (for fluent usage)
     * @throws IllegalStateException if the transition is not allowed
     */
    public InterviewStatus transitionTo(InterviewStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    String.format("Illegal interview status transition: %s → %s. "
                            + "Allowed targets from %s: %s",
                            this, target, this, allowedTargets()));
        }
        return target;
    }

    /**
     * Returns a human-readable list of valid target states from this status.
     */
    private String allowedTargets() {
        var targets = new java.util.ArrayList<InterviewStatus>();
        for (InterviewStatus candidate : values()) {
            if (canTransitionTo(candidate)) {
                targets.add(candidate);
            }
        }
        return targets.isEmpty() ? "(terminal state)" : targets.toString();
    }
}
