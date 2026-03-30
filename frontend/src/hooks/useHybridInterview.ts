import { useCallback, useMemo, useState } from "react";
import { useInterviewStore } from "../stores/useInterviewStore";
import { interviewService } from "../services/interview.service";
import { InterviewQuestion, NextQuestionResponse } from "../types";

// ============================================================
// Hybrid Interview Hook
// ============================================================
//
// This hook manages the hybrid interview flow where:
// - Questions 1 to pregenCount are pre-generated before interview starts
// - Questions pregenCount+1 to totalQuestions are generated dynamically
//   after each answer using Ollama, informed by job role, resume, and
//   ALL previous Q&A pairs.
//
// The hook exposes:
// - currentQuestion: The current question object
// - isTransitioning: True when generating next question or uploading
// - generationMode: "PRE_GENERATED" | "DYNAMIC" | null
// - advanceToNext: Submit answer and get next question (hybrid flow)
// - isLastQuestion: True if on the final question
// - totalQuestions: Total number of questions in interview
// - transitionError: Error message if transition failed
// ============================================================

interface UseHybridInterviewOptions {
  /** Interview ID */
  interviewId: number;
  /** Callback when answer submission fails */
  onError?: (error: string) => void;
  /** Callback when interview completes (last question answered) */
  onComplete?: () => void;
}

interface UseHybridInterviewReturn {
  /** Current question object */
  currentQuestion: InterviewQuestion | null;
  /** Current 0-based question index */
  currentQuestionIndex: number;
  /** True when uploading video or generating next question */
  isTransitioning: boolean;
  /** How the current question was generated */
  generationMode: "PRE_GENERATED" | "DYNAMIC" | null;
  /** True if this is the last question */
  isLastQuestion: boolean;
  /** Total number of questions in interview */
  totalQuestions: number;
  /** All questions available so far */
  questions: InterviewQuestion[];
  /** Error message from last transition attempt */
  transitionError: string | null;
  /** Clear the transition error */
  clearTransitionError: () => void;
  /**
   * Submit video blob, record answer, and get next question.
   * In hybrid mode, this may trigger dynamic question generation.
   *
   * @param videoBlob - Recorded video blob
   * @param answerTranscript - Transcribed answer text
   * @param onUploadProgress - Optional progress callback (0-100)
   * @returns True if transition succeeded, false otherwise
   */
  advanceToNext: (
    videoBlob: Blob,
    answerTranscript: string,
    onUploadProgress?: (progress: number) => void,
  ) => Promise<boolean>;
  /**
   * Navigate to a specific question index (for review mode only).
   * Only allows navigating to already-answered questions.
   */
  goToQuestion: (index: number) => void;
}

export const useHybridInterview = ({
  interviewId,
  onError,
  onComplete,
}: UseHybridInterviewOptions): UseHybridInterviewReturn => {
  // ── Global Store ────────────────────────────────────────────
  const {
    interview,
    currentQuestionIndex,
    setCurrentQuestionIndex,
    setRecordingState,
    setInterview,
  } = useInterviewStore();

  // ── Local State ─────────────────────────────────────────────
  const [isTransitioning, setIsTransitioning] = useState(false);
  const [transitionError, setTransitionError] = useState<string | null>(null);
  const [generationMode, setGenerationMode] = useState<
    "PRE_GENERATED" | "DYNAMIC" | null
  >(null);

  // ── Derived State ───────────────────────────────────────────
  const questions: InterviewQuestion[] = useMemo(() => {
    return interview?.questions || [];
  }, [interview]);

  const currentQuestion: InterviewQuestion | null =
    questions[currentQuestionIndex] || null;

  const totalQuestions = useMemo(() => {
    // Use totalQuestions from interview if available (hybrid mode),
    // otherwise fall back to questions array length
    // Note: In hybrid mode, totalQuestions may be greater than questions.length
    // because dynamic questions haven't been generated yet
    return (interview as any)?.totalQuestions || questions.length;
  }, [interview, questions.length]);

  const isLastQuestion = currentQuestionIndex >= totalQuestions - 1;

  // ── Clear Error ─────────────────────────────────────────────
  const clearTransitionError = useCallback(() => {
    setTransitionError(null);
  }, []);

  // ── Navigate to Question (Review Mode) ──────────────────────
  const goToQuestion = useCallback(
    (index: number) => {
      if (index < 0 || index >= questions.length) return;

      // Only allow navigation to answered questions
      const targetQuestion = questions[index];
      if (targetQuestion && targetQuestion.answered) {
        setCurrentQuestionIndex(index);
        setRecordingState(false);
      }
    },
    [questions, setCurrentQuestionIndex, setRecordingState],
  );

  // ── Advance to Next Question ────────────────────────────────
  const advanceToNext = useCallback(
    async (
      videoBlob: Blob,
      answerTranscript: string,
      onUploadProgress?: (progress: number) => void,
    ): Promise<boolean> => {
      if (!currentQuestion) {
        setTransitionError("No current question to answer.");
        return false;
      }

      setIsTransitioning(true);
      setTransitionError(null);

      try {
        // Step 1: Upload video via presigned URL flow
        let videoUrl: string | undefined;
        try {
          const confirmResponse = await interviewService.submitVideoPresigned(
            interviewId,
            currentQuestion.questionId,
            videoBlob,
            onUploadProgress,
          );
          videoUrl = confirmResponse.s3Key; // Use S3 key as reference
        } catch (uploadError: any) {
          // Try legacy multipart upload as fallback
          console.warn(
            "Presigned upload failed, trying legacy multipart:",
            uploadError?.message,
          );
          if (onUploadProgress) onUploadProgress(0);
          try {
            await interviewService.submitVideoResponse(
              videoBlob,
              interviewId,
              currentQuestion.questionId,
              onUploadProgress,
            );
          } catch (legacyError: any) {
            throw new Error(
              legacyError?.message || "Failed to upload video response.",
            );
          }
        }

        // Step 2: Submit answer and get next question (hybrid flow)
        const response: NextQuestionResponse =
          await interviewService.submitAnswerAndGetNext(
            interviewId,
            currentQuestion.questionId,
            answerTranscript,
            videoUrl,
          );

        // Step 3: Handle response
        if (response.interviewComplete) {
          // Interview is complete — trigger completion callback
          if (onComplete) {
            onComplete();
          }
          return true;
        }

        // Step 4: Add the next question to the store
        if (
          response.nextQuestionId &&
          response.nextQuestionText &&
          response.nextQuestionNumber
        ) {
          // Create new question object
          const nextQuestion: InterviewQuestion = {
            questionId: response.nextQuestionId,
            questionNumber: response.nextQuestionNumber,
            questionText: response.nextQuestionText,
            category: response.nextQuestionCategory || "GENERAL",
            difficulty: response.nextQuestionDifficulty || "MEDIUM",
            audioUrl: response.nextQuestionAudioUrl,
            avatarVideoUrl: null, // Legacy field
            answered: false,
            responseVideoUrl: null,
            responseTranscription: null,
          };

          // Update generation mode for UI feedback
          setGenerationMode(response.generationMode);

          // Mark current question as answered and add next question to store
          const updatedQuestions = questions.map((q) =>
            q.questionId === currentQuestion.questionId
              ? { ...q, answered: true }
              : q,
          );

          // Check if next question already exists (pre-generated)
          const existingNextIndex = updatedQuestions.findIndex(
            (q) => q.questionId === response.nextQuestionId,
          );

          if (existingNextIndex === -1) {
            // Dynamic question — add to array
            updatedQuestions.push(nextQuestion);
          } else {
            // Pre-generated question — update with any new data (e.g., audio URL)
            updatedQuestions[existingNextIndex] = {
              ...updatedQuestions[existingNextIndex],
              audioUrl:
                response.nextQuestionAudioUrl ||
                updatedQuestions[existingNextIndex].audioUrl,
            };
          }

          // Update interview in store
          if (interview) {
            setInterview({
              ...interview,
              questions: updatedQuestions,
            });
          }

          // Advance to next question index
          const nextIndex =
            existingNextIndex !== -1
              ? existingNextIndex
              : updatedQuestions.length - 1;
          setCurrentQuestionIndex(nextIndex);
          setRecordingState(false);
        }

        return true;
      } catch (error: any) {
        const errorMessage =
          error?.message || "Failed to submit answer and get next question.";
        setTransitionError(errorMessage);
        if (onError) {
          onError(errorMessage);
        }
        return false;
      } finally {
        setIsTransitioning(false);
      }
    },
    [
      interviewId,
      currentQuestion,
      questions,
      interview,
      setInterview,
      setCurrentQuestionIndex,
      setRecordingState,
      onError,
      onComplete,
    ],
  );

  return {
    currentQuestion,
    currentQuestionIndex,
    isTransitioning,
    generationMode,
    isLastQuestion,
    totalQuestions,
    questions,
    transitionError,
    clearTransitionError,
    advanceToNext,
    goToQuestion,
  };
};

export default useHybridInterview;
