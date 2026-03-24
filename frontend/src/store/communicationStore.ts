import { create } from "zustand";

export interface Message {
  id: string;
  role: "ai" | "user";
  content: string;
  feedback?: Feedback; // Optional feedback for user messages
}

export interface Feedback {
  fluencyScore: number;
  grammarIssues: string[];
  correctedSentence: string;
  confidence: string;
  suggestions: string[];
}

export interface OverallFeedback {
  overallScore: number;
  overallFeedback: string;
  weakPoints: string[];
  improvementTips: string[];
}

interface CommunicationState {
  messages: Message[];
  currentFeedback: Feedback | null;
  overallFeedback: OverallFeedback | null;
  isRecording: boolean;
  isAnalyzing: boolean;
  isAILoading: boolean;
  isOverallAnalyzing: boolean;

  // Actions
  addMessage: (
    role: "ai" | "user",
    content: string,
    feedback?: Feedback,
  ) => void;
  setFeedback: (feedback: Feedback | null) => void;
  setOverallFeedback: (feedback: OverallFeedback | null) => void;
  setIsRecording: (isRecording: boolean) => void;
  setIsAnalyzing: (isAnalyzing: boolean) => void;
  setIsAILoading: (isAILoading: boolean) => void;
  setIsOverallAnalyzing: (isOverallAnalyzing: boolean) => void;
  reset: () => void;
}

export const useCommunicationStore = create<CommunicationState>((set) => ({
  messages: [],
  currentFeedback: null,
  overallFeedback: null,
  isRecording: false,
  isAnalyzing: false,
  isAILoading: false,
  isOverallAnalyzing: false,

  addMessage: (role, content, feedback) =>
    set((state) => ({
      messages: [
        ...state.messages,
        {
          id: Date.now().toString() + Math.random().toString(36).substring(7),
          role,
          content,
          feedback,
        },
      ],
    })),

  setFeedback: (feedback) => set({ currentFeedback: feedback }),
  setOverallFeedback: (feedback) => set({ overallFeedback: feedback }),
  setIsRecording: (isRecording) => set({ isRecording }),
  setIsAnalyzing: (isAnalyzing) => set({ isAnalyzing }),
  setIsAILoading: (isAILoading) => set({ isAILoading }),
  setIsOverallAnalyzing: (isOverallAnalyzing) => set({ isOverallAnalyzing }),

  reset: () =>
    set({
      messages: [],
      currentFeedback: null,
      overallFeedback: null,
      isRecording: false,
      isAnalyzing: false,
      isAILoading: false,
      isOverallAnalyzing: false,
    }),
}));
