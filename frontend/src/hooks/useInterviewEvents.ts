import { useEffect } from "react";
import { useInterviewStore } from "../stores/useInterviewStore";
import { API_BASE_URL, TOKEN_KEY } from "../utils/constants";
import api from "../services/api.service";
// FIX: useInterviewEvents now handles both avatar-ready (legacy) and audio-ready (TTS) events

/**
 * Hook that manages real-time SSE events for interview avatar generation progress.
 *
 * **P0-5 Fix:** Previously, the full JWT was passed as a query parameter
 * (`?token=...`) to the SSE EventSource endpoint. This leaked the JWT into
 * browser history, server access logs, reverse proxy logs, and Referer headers.
 *
 * Now uses a short-lived, single-use **ticket** mechanism:
 * 1. Frontend requests a ticket via `POST /api/interviews/{id}/sse-ticket`
 *    (authenticated via the Authorization header as usual).
 * 2. Backend issues a ticket that expires in 30 seconds and is single-use.
 * 3. Frontend passes the ticket as `?ticket=...` in the EventSource URL.
 * 4. Backend validates and consumes the ticket on the SSE endpoint.
 *
 * If the ticket endpoint is not yet implemented (returns 404 or error),
 * the hook falls back to polling-only mode gracefully.
 */
export const useInterviewEvents = (interviewId: number) => {
  const {
    setConnectionStatus,
    updateQuestionVideoUrl,
    updateQuestionAudioUrl,
    setInterview,
  } = useInterviewStore(); // FIX: Added updateQuestionAudioUrl for TTS audio ready events

  useEffect(() => {
    const token = sessionStorage.getItem(TOKEN_KEY); // FIX: was localStorage — token is stored in sessionStorage after audit migration, causing 401 on SSE ticket requests
    if (!token) return;

    let eventSource: EventSource | null = null;
    let isActive = true;

    /**
     * Attempt to establish an SSE connection using a short-lived ticket.
     * Falls back to polling-only if ticket acquisition fails.
     */
    const connectSSE = async () => {
      try {
        // Step 1: Fetch a short-lived, single-use SSE ticket
        const ticketResponse = await api.post<{ ticket: string }>(
          `/api/interviews/${interviewId}/sse-ticket`,
        );
        const ticket = ticketResponse.data.ticket;

        if (!isActive) return;

        // Step 2: Connect EventSource using the ticket (NOT the JWT)
        const url = `${API_BASE_URL}/api/interviews/${interviewId}/events?ticket=${ticket}`;
        eventSource = new EventSource(url);

        setConnectionStatus("connecting");

        eventSource.onopen = () => {
          console.log("SSE Connected (ticket-based)");
          setConnectionStatus("connected");
        };

        eventSource.onerror = (error) => {
          console.error("SSE Error:", error);
          setConnectionStatus("error");
          if (eventSource) {
            eventSource.close();
            eventSource = null;
          }
        };

        // Listen for avatar-ready events
        eventSource.addEventListener("avatar-ready", (_event: MessageEvent) => {
          console.log("Avatar Ready Event received");
          // FIX: Handle new TTS audio ready event from backend
          try {
            const data = JSON.parse(_event.data); // FIX: Parse SSE event data for audioUrl
            if (data.questionId && data.audioUrl) {
              useInterviewStore.getState().updateQuestionAudioUrl(
                // FIX: Update question audio URL in store when TTS audio is ready
                data.questionId,
                data.audioUrl,
              );
            }
          } catch (e) {
            // FIX: Graceful fallback — SSE data may not contain audioUrl in legacy events
            console.debug(
              "Could not parse avatar-ready event data for audioUrl:",
              e,
            );
          }
        });

        // FIX: Handle new TTS audio ready event from backend (dedicated event type)
        eventSource.addEventListener(
          "question:audio:ready",
          (e: MessageEvent) => {
            console.log("Question Audio Ready Event received"); // FIX: Log TTS audio ready event
            try {
              const data = JSON.parse(e.data); // FIX: Parse SSE event data
              useInterviewStore.getState().updateQuestionAudioUrl(
                // FIX: Update question audio URL in store
                data.questionId,
                data.audioUrl,
              );
            } catch (err) {
              console.error("Failed to parse question:audio:ready event:", err); // FIX: Log parse errors
            }
          },
        );

        // Listen for interview-ready events (all videos done)
        eventSource.addEventListener(
          "interview-ready",
          (_event: MessageEvent) => {
            console.log("Interview Ready Event received");
          },
        );

        // Listen for avatar-failed events
        eventSource.addEventListener("avatar-failed", (event: MessageEvent) => {
          console.error("Avatar Failed Event:", event.data);
        });
      } catch (err) {
        // Ticket endpoint not available or failed — fall back to polling only
        console.warn(
          "SSE ticket acquisition failed, falling back to polling-only mode:",
          err,
        );
        setConnectionStatus("disconnected");
      }
    };

    // Initiate SSE connection
    connectSSE();

    // POLLING FALLBACK: Since SSE payload doesn't contain the full video URLs
    // and to handle the race condition where videos generate before SSE connects,
    // we poll the interview endpoint every 2.5s until the status is IN_PROGRESS.
    const pollInterval = setInterval(async () => {
      try {
        const { interviewService } =
          await import("../services/interview.service");
        const data = await interviewService.getInterview(interviewId);
        if (isActive && data) {
          // Update the entire interview object in the store to get the new video URLs
          useInterviewStore.setState({ interview: data });
          if (
            data.status === "IN_PROGRESS" ||
            data.status === "COMPLETED" ||
            data.status === "PROCESSING"
          ) {
            clearInterval(pollInterval);
            if (eventSource) {
              eventSource.close();
              eventSource = null;
            }
          }
        }
      } catch (err) {
        console.error("Failed to poll interview status", err);
      }
    }, 2500);

    return () => {
      isActive = false;
      console.log("Closing SSE connection and Polling");
      clearInterval(pollInterval);
      if (eventSource) {
        eventSource.close();
        eventSource = null;
      }
      setConnectionStatus("disconnected");
    };
  }, [
    interviewId,
    setConnectionStatus,
    updateQuestionVideoUrl,
    updateQuestionAudioUrl,
    setInterview,
  ]); // FIX: Added updateQuestionAudioUrl to dependency array
};
