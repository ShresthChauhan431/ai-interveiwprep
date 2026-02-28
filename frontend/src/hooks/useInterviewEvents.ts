import { useEffect } from 'react';
import { useInterviewStore } from '../stores/useInterviewStore';
import { API_BASE_URL, TOKEN_KEY } from '../utils/constants';

export const useInterviewEvents = (interviewId: number) => {
  const {
    setConnectionStatus,
    updateQuestionVideoUrl,
    setInterview
  } = useInterviewStore();

  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return;

    const url = `${API_BASE_URL}/api/interviews/${interviewId}/events?token=${token}`;
    const eventSource = new EventSource(url);

    setConnectionStatus('connecting');

    eventSource.onopen = () => {
      console.log('SSE Connected');
      setConnectionStatus('connected');
    };

    eventSource.onerror = (error) => {
      console.error('SSE Error:', error);
      setConnectionStatus('error');
      eventSource.close();
    };

    // Listen for avatar-ready events
    eventSource.addEventListener('avatar-ready', (event: MessageEvent) => {
      console.log('Avatar Ready Event received');
    });

    // Listen for interview-ready events (all videos done)
    eventSource.addEventListener('interview-ready', (event: MessageEvent) => {
      console.log('Interview Ready Event received');
    });

    // Listen for avatar-failed events
    eventSource.addEventListener('avatar-failed', (event: MessageEvent) => {
      console.error('Avatar Failed Event:', event.data);
    });

    // POLLING FALLBACK: Since SSE payload doesn't contain the full video URLs 
    // and to handle the race condition where videos generate before SSE connects,
    // we poll the interview endpoint every 2.5s until the status is IN_PROGRESS.
    let isActive = true;
    const pollInterval = setInterval(async () => {
      try {
        const { interviewService } = await import('../services/interview.service');
        const data = await interviewService.getInterview(interviewId);
        if (isActive && data) {
          // Update the entire interview object in the store to get the new video URLs
          useInterviewStore.setState({ interview: data });
          if (data.status === 'IN_PROGRESS' || data.status === 'COMPLETED' || data.status === 'PROCESSING') {
            clearInterval(pollInterval);
            eventSource.close();
          }
        }
      } catch (err) {
        console.error("Failed to poll interview status", err);
      }
    }, 2500);

    return () => {
      isActive = false;
      console.log('Closing SSE connection and Polling');
      clearInterval(pollInterval);
      eventSource.close();
      setConnectionStatus('disconnected');
    };
  }, [interviewId, setConnectionStatus, updateQuestionVideoUrl, setInterview]);
};
