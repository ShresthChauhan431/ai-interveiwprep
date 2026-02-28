import { create } from 'zustand';
import { InterviewDTO } from '../types';

interface InterviewState {
  interview: InterviewDTO | null;
  currentQuestionIndex: number;
  isRecording: boolean;
  connectionStatus: 'connecting' | 'connected' | 'error' | 'disconnected';
  
  // Actions
  setInterview: (interview: InterviewDTO) => void;
  setCurrentQuestionIndex: (index: number) => void;
  nextQuestion: () => void;
  prevQuestion: () => void;
  setRecordingState: (isRecording: boolean) => void;
  setConnectionStatus: (status: InterviewState['connectionStatus']) => void;
  updateQuestionVideoUrl: (questionId: number, videoUrl: string) => void;
  reset: () => void;
}

export const useInterviewStore = create<InterviewState>((set) => ({
  interview: null,
  currentQuestionIndex: 0,
  isRecording: false,
  connectionStatus: 'disconnected',

  setInterview: (interview) => set({ interview }),
  
  setCurrentQuestionIndex: (index) => set((state) => {
    if (!state.interview?.questions) return state;
    const maxIndex = state.interview.questions.length - 1;
    return { currentQuestionIndex: Math.max(0, Math.min(index, maxIndex)) };
  }),

  nextQuestion: () => set((state) => {
    if (!state.interview?.questions) return state;
    const nextIndex = state.currentQuestionIndex + 1;
    if (nextIndex < state.interview.questions.length) {
      return { currentQuestionIndex: nextIndex };
    }
    return state;
  }),

  prevQuestion: () => set((state) => {
    const prevIndex = state.currentQuestionIndex - 1;
    if (prevIndex >= 0) {
      return { currentQuestionIndex: prevIndex };
    }
    return state;
  }),

  setRecordingState: (isRecording) => set({ isRecording }),
  
  setConnectionStatus: (connectionStatus) => set({ connectionStatus }),

  updateQuestionVideoUrl: (questionId, videoUrl) => set((state) => {
    if (!state.interview || !state.interview.questions) return state;
    
    // Find question and update its videoUrl
    // We need to return a new interview object with the updated question
    const updatedQuestions = state.interview.questions.map((q) => 
      q.questionId === questionId ? { ...q, avatarVideoUrl: videoUrl } : q
    );

    const allReady = updatedQuestions.every(q => !!q.avatarVideoUrl);
    const newStatus = allReady && state.interview.status === 'GENERATING_VIDEOS' 
      ? 'IN_PROGRESS' 
      : state.interview.status;

    return { 
      interview: { 
        ...state.interview, 
        questions: updatedQuestions,
        status: newStatus
      } 
    };
  }),

  reset: () => set({
    interview: null,
    currentQuestionIndex: 0,
    isRecording: false,
    connectionStatus: 'disconnected'
  })
}));
