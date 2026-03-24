import api from "./api.service";

export interface FeedbackResponse {
  fluencyScore: number;
  grammarIssues: string[];
  correctedSentence: string;
  confidence: string;
  suggestions: string[];
}

export interface OverallFeedbackResponse {
  overallScore: number;
  overallFeedback: string;
  weakPoints: string[];
  improvementTips: string[];
}

export const communicationService = {
  /**
   * Starts a new communication practice session and returns the initial AI message.
   */
  async startConversation(): Promise<string> {
    const response = await api.post<{ message: string }>(
      "/api/communication/start",
    );
    return response.data.message;
  },

  /**
   * Analyzes the user's spoken sentence in real-time.
   */
  async analyzeLive(text: string): Promise<FeedbackResponse> {
    const response = await api.post<FeedbackResponse>(
      "/api/communication/analyze-live",
      { text },
    );
    return response.data;
  },

  /**
   * Sends the conversation history to get the next AI response.
   */
  async getNextMessage(
    history: { role: string; content: string }[],
  ): Promise<string> {
    const response = await api.post<{ message: string }>(
      "/api/communication/next",
      { history },
    );
    return response.data.message;
  },

  /**
   * Analyzes the overall conversation history.
   */
  async analyzeOverall(
    history: { role: string; content: string }[],
  ): Promise<OverallFeedbackResponse> {
    const response = await api.post<OverallFeedbackResponse>(
      "/api/communication/analyze-overall",
      { history },
    );
    return response.data;
  },
};
