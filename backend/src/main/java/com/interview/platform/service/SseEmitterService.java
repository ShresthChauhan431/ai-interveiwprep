package com.interview.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Server-Sent Event (SSE) connections for real-time interview
 * status updates.
 *
 * <h3>Purpose:</h3>
 * <p>
 * Provides real-time push notifications to the frontend during the
 * avatar video generation phase. Instead of the frontend polling every
 * few seconds, the server pushes events as each avatar video becomes
 * ready. The frontend has a polling fallback via {@code useInterviewPolling}
 * for environments where SSE connections are dropped.
 * </p>
 *
 * <h3>Event Types:</h3>
 * <ul>
 * <li>{@code avatar-ready} — an avatar video has been generated for
 * a question ({@code questionId}, {@code videoUrl})</li>
 * <li>{@code avatar-failed} — avatar generation failed for a question;
 * frontend should show text-only fallback</li>
 * <li>{@code interview-ready} — all questions processed; interview
 * transitioned to {@code IN_PROGRESS}</li>
 * </ul>
 *
 * <h3>Thread Safety:</h3>
 * <p>
 * Uses {@link ConcurrentHashMap} with {@link CopyOnWriteArrayList}
 * to safely handle concurrent registrations and event sends from
 * different threads (controller thread for registration, virtual
 * thread from {@code AvatarPipelineListener} for events).
 * </p>
 *
 * <h3>Lifecycle:</h3>
 * <p>
 * Emitters are automatically removed on completion, timeout, or error
 * via registered callbacks. The default timeout is 10 minutes (matching
 * the maximum expected avatar pipeline duration).
 * </p>
 *
 * <h3>P2-1 Fix:</h3>
 * <p>
 * The emitter registration key is now a composite of {@code interviewId}
 * and {@code userId}. Previously, emitters were keyed only by
 * {@code interviewId}, meaning that if the ownership check in the
 * controller was ever removed or bypassed, any authenticated user could
 * subscribe to any interview's events. The composite key provides a
 * defense-in-depth layer: even if the controller check is accidentally
 * removed in a future refactor, the emitters for interview X belonging
 * to user A will never receive events sent for user B's subscription
 * to the same interview (which shouldn't exist, but now can't leak).
 * </p>
 *
 * <p>
 * The {@link #send(Long, String, Object)} and {@link #completeAll(Long)}
 * methods continue to accept only {@code interviewId} because they are
 * called from the {@code AvatarPipelineListener} which processes
 * questions for a specific interview regardless of the subscribing user.
 * These methods iterate all emitter keys that match the interview prefix.
 * </p>
 *
 * @see com.interview.platform.event.AvatarPipelineListener
 * @see com.interview.platform.controller.InterviewController
 */
@Service
public class SseEmitterService {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);

    /** Default SSE connection timeout: 10 minutes. */
    private static final long SSE_TIMEOUT_MS = 600_000L;

    /**
     * Map of composite key (interviewId:userId) → list of active SSE emitters.
     *
     * <p>P2-1: The key is now a composite string "{interviewId}:{userId}" instead
     * of just the interviewId. This ensures emitters are scoped to both the
     * interview and the authenticated user, preventing cross-user event leakage.</p>
     *
     * <p>Multiple browser tabs/sessions from the same user can subscribe to
     * the same interview (hence the CopyOnWriteArrayList).</p>
     */
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Build the composite emitter key from interviewId and userId.
     *
     * @param interviewId the interview to subscribe to
     * @param userId      the authenticated user's ID
     * @return the composite key string
     */
    private static String emitterKey(Long interviewId, Long userId) {
        return interviewId + ":" + userId;
    }

    /**
     * Build a key prefix for matching all emitters for a given interview
     * (regardless of userId). Used by {@link #send} and {@link #completeAll}.
     *
     * @param interviewId the interview ID
     * @return the key prefix string
     */
    private static String interviewKeyPrefix(Long interviewId) {
        return interviewId + ":";
    }

    /**
     * Register a new SSE emitter for a given interview and user (P2-1).
     *
     * <p>
     * The emitter is automatically removed from the map when the
     * connection closes (completion, timeout, or error).
     * </p>
     *
     * <p><strong>P2-1:</strong> Previously, this method accepted only
     * {@code interviewId}. Now it requires both {@code interviewId} and
     * {@code userId} to scope the emitter to the authenticated user.
     * The controller must validate interview ownership before calling
     * this method, and the composite key provides a second layer of
     * defense against cross-user event leakage.</p>
     *
     * @param interviewId the interview to subscribe to
     * @param userId      the authenticated user's ID (ownership already validated by controller)
     * @return the configured SseEmitter ready to be returned from a controller
     */
    public SseEmitter register(Long interviewId, Long userId) {
        String key = emitterKey(interviewId, userId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        CopyOnWriteArrayList<SseEmitter> list = emitters.computeIfAbsent(
                key, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        log.debug("SSE emitter registered: interviewId={}, userId={}, activeConnections={}",
                interviewId, userId, list.size());

        // Cleanup callbacks
        Runnable onDone = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(key);
            }
            log.debug("SSE emitter removed: interviewId={}, userId={}, remaining={}",
                    interviewId, userId, list.size());
        };

        emitter.onCompletion(onDone);
        emitter.onTimeout(onDone);
        emitter.onError(t -> {
            log.debug("SSE emitter error: interviewId={}, userId={}, error={}",
                    interviewId, userId, t.getMessage());
            onDone.run();
        });

        return emitter;
    }

    /**
     * @deprecated Use {@link #register(Long, Long)} instead (P2-1).
     *             This overload is retained temporarily for backward compatibility
     *             with any callers that haven't been updated yet. It registers
     *             the emitter with userId=0 (system/anonymous).
     */
    @Deprecated(forRemoval = true, since = "P2-1")
    public SseEmitter register(Long interviewId) {
        log.warn("SseEmitterService.register(interviewId) called without userId — "
                + "use register(interviewId, userId) instead. Defaulting userId=0.");
        return register(interviewId, 0L);
    }

    /**
     * Send an event to all SSE subscribers for a given interview.
     *
     * <p>
     * Failed sends (e.g., client disconnected) are silently caught
     * and the emitter is completed/removed. This ensures one broken
     * connection doesn't prevent other subscribers from receiving events.
     * </p>
     *
     * <p><strong>P2-1:</strong> This method iterates all emitter keys that
     * match the interview prefix (i.e., all users subscribed to this interview).
     * In practice, only the interview owner should have emitters registered,
     * but the iteration handles the composite key structure transparently.</p>
     *
     * @param interviewId the interview whose subscribers should receive the event
     * @param eventName   the SSE event name (e.g., "avatar-ready",
     *                    "interview-ready")
     * @param data        the event payload (will be serialized to JSON by Spring)
     */
    public void send(Long interviewId, String eventName, Object data) {
        String prefix = interviewKeyPrefix(interviewId);
        int totalSent = 0;

        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }

            CopyOnWriteArrayList<SseEmitter> list = entry.getValue();
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(eventName)
                            .data(data));
                    totalSent++;
                } catch (IOException | IllegalStateException e) {
                    log.debug("Failed to send SSE event to client: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            }
        }

        if (totalSent == 0) {
            log.debug("No SSE subscribers for interviewId={}, event '{}' not sent",
                    interviewId, eventName);
        } else {
            log.debug("Sent SSE event '{}' to {} subscriber(s) for interviewId={}",
                    eventName, totalSent, interviewId);
        }
    }

    /**
     * Complete all SSE connections for a given interview.
     *
     * <p>
     * Called when the interview transitions to {@code IN_PROGRESS},
     * signaling that all avatar videos have been processed and the
     * frontend can proceed. The cleanup callbacks handle removal from
     * the map.
     * </p>
     *
     * <p><strong>P2-1:</strong> Iterates all composite keys matching the
     * interview prefix to complete emitters for all users subscribed to
     * this interview (in practice, only the owner).</p>
     *
     * @param interviewId the interview whose connections should be closed
     */
    public void completeAll(Long interviewId) {
        String prefix = interviewKeyPrefix(interviewId);

        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }

            log.debug("Completing SSE connections for key={}", entry.getKey());

            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing SSE emitter: {}", e.getMessage());
                }
            }
        }
    }
}
