import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
// PREFETCH OPTIMIZATION:
// - While user is answering a question, prefetch the next question in background
// - This reduces perceived wait time between questions
// - Prefetched questions are cached and used when advancing
//
// The hook exposes:
// - currentQuestion: The current question object
// - isTransitioning: True when generating next question or uploading
// - generationMode: "PRE_GENERATED" | "DYNAMIC" | null
// - advanceToNext: Submit answer and get next question (hybrid flow)
// - isLastQuestion: True if on the final question
// - totalQuestions: Total number of questions in interview
// - transitionError: Error message if transition failed
// - isPrefetching: True when background prefetch is in progress
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
  /** True when waiting for transcription to complete */
  isTranscribing: boolean;
  /** True when prefetching next question in background */
  isPrefetching: boolean;
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
  /**
   * Manually trigger prefetch of next question (called when user starts recording)
   */
  startPrefetch: () => void;
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
  const [isTranscribing, setIsTranscribing] = useState(false);
  const [isPrefetching, setIsPrefetching] = useState(false);
  const [transitionError, setTransitionError] = useState<string | null>(null);
  const [generationMode, setGenerationMode] = useState<
    "PRE_GENERATED" | "DYNAMIC" | null
  >(null);

  // ── Prefetch Cache ──────────────────────────────────────────
  // Cache for prefetched next question (keyed by current question ID)
  // Store the Promise so advanceToNext can await it if it's still in progress!
  const prefetchCacheRef = useRef<Map<number, Promise<NextQuestionResponse>>>(new Map());
  const prefetchInProgressRef = useRef<Set<number>>(new Set());

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

  // ── Prefetch Next Question ──────────────────────────────────
  // Called when user starts recording to pre-generate the next question
  // This runs in parallel with the user answering, reducing wait time
  const startPrefetch = useCallback(async () => {
    if (!currentQuestion || isLastQuestion) return;
    
    const questionId = currentQuestion.questionId;
    
    // Skip if already prefetching or cached for this question
    if (prefetchInProgressRef.current.has(questionId)) return;
    if (prefetchCacheRef.current.has(questionId)) return;
    
    // Check if next question is already pre-generated (in questions array)
    const nextIndex = currentQuestionIndex + 1;
    if (nextIndex < questions.length && questions[nextIndex]) {
      // Next question already exists, no need to prefetch
      return;
    }
    
    prefetchInProgressRef.current.add(questionId);
    setIsPrefetching(true);
    
    try {
      console.log(`[Prefetch] Starting prefetch for question after ${questionId}`);
      
      // Call the backend to pre-generate the next question
      // Stores the Promise so we can await it if advanceToNext is called before it finishes.
      const prefetchPromise = interviewService.submitAnswerAndGetNext(
        interviewId,
        questionId,
        "", // Empty transcript - backend will handle gracefully
        undefined, // No video URL yet
      );
      
      // Cache the promise immediately
      prefetchCacheRef.current.set(questionId, prefetchPromise);
      
      await prefetchPromise;
      console.log(`[Prefetch] Cached prefetched question for ${questionId}`);
      
    } catch (err) {
      // Prefetch failure is non-fatal - we'll generate on demand
      console.warn(`[Prefetch] Failed to prefetch next question:`, err);
    } finally {
      prefetchInProgressRef.current.delete(questionId);
      setIsPrefetching(false);
    }
  }, [currentQuestion, currentQuestionIndex, interviewId, isLastQuestion, questions]);

  // ── Auto-prefetch when question changes ─────────────────────
  // Automatically start prefetching when user views a new question
  useEffect(() => {
    if (currentQuestion && !isLastQuestion && !isTransitioning) {
      // Small delay to avoid prefetching during rapid transitions
      const timer = setTimeout(() => {
        startPrefetch();
      }, 2000); // Start prefetch 2 seconds after question appears
      
      return () => clearTimeout(timer);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentQuestion?.questionId, isLastQuestion, isTransitioning]);

  // ── Poll for Transcription ──────────────────────────────────
  const pollForTranscription = useCallback(
    async (
      questionId: number,
      maxWaitMs = 30000,
      intervalMs = 2000,
    ): Promise<string> => {
      const deadline = Date.now() + maxWaitMs;
      while (Date.now() < deadline) {
        try {
          const result = await interviewService.getTranscriptionStatus(
            interviewId,
            questionId,
          );
          if (result.status === "COMPLETED" && result.transcription) {
            return result.transcription;
          }
        } catch (err) {
          // Network error — keep trying until deadline
          console.warn("Transcription poll error (retrying):", err);
        }
        // Wait before next poll
        await new Promise((resolve) => setTimeout(resolve, intervalMs));
      }
      // Timeout — return empty string (graceful degradation)
      console.warn(
        "Transcription polling timed out for question",
        questionId,
        "— proceeding without transcript",
      );
      return "";
    },
    [interviewId],
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

        // Step 1b: Wait for AssemblyAI to transcribe the uploaded video
        // In local mode, AssemblyAI can't access the files, so use browser transcript as fallback
        setIsTranscribing(true);
        let finalTranscript = answerTranscript; // Start with browser transcript
        
        if (!finalTranscript) {
          // No browser transcript - poll for AssemblyAI transcription
          finalTranscript = await pollForTranscription(
            currentQuestion.questionId,
          );
        } else {
          // Have browser transcript - still poll but use browser transcript if AssemblyAI fails
          try {
            const assemblyTranscript = await pollForTranscription(
              currentQuestion.questionId,
            );
            // If AssemblyAI returns a real transcript (not placeholder), prefer it
            if (assemblyTranscript && 
                !assemblyTranscript.includes("[Transcription unavailable") &&
                !assemblyTranscript.includes("No response recorded") &&
                assemblyTranscript.length > finalTranscript.length * 0.5) {
              finalTranscript = assemblyTranscript;
              console.log("[Transcription] Using AssemblyAI transcript");
            } else {
              console.log("[Transcription] Using browser transcript (AssemblyAI unavailable)");
            }
          } catch {
            // AssemblyAI polling failed - use browser transcript
            console.log("[Transcription] AssemblyAI poll failed, using browser transcript");
          }
        }
        setIsTranscribing(false);

        // Step 2: Check if we have an in-progress or completed prefetch
        let response: NextQuestionResponse;
        const prefetchPromise = prefetchCacheRef.current.get(currentQuestion.questionId);
        
        if (prefetchPromise) {
          // Await the prefetched promise - if it's already done this resolves immediately!
          console.log(`[Prefetch] Awaiting prefetched response for question ${currentQuestion.questionId}`);
          
          try {
            response = await prefetchPromise;
            prefetchCacheRef.current.delete(currentQuestion.questionId);
            
            // Still need to submit the actual answer with transcript
            // Fire and forget - don't wait for this since we have the next question
            interviewService.submitAnswerAndGetNext(
              interviewId,
              currentQuestion.questionId,
              finalTranscript,
              videoUrl,
            ).catch((err) => {
              console.warn("[Prefetch] Background answer submission failed:", err);
            });
          } catch (e) {
            console.warn("[Prefetch] Prefetch failed, falling back to synchronous generation", e);
            response = await interviewService.submitAnswerAndGetNext(
              interviewId,
              currentQuestion.questionId,
              finalTranscript,
              videoUrl,
            );
          }
        } else {
          // No prefetch available - generate next question synchronously
          console.log(`[Prefetch] No prefetch found, synchronous generation required`);
          response = await interviewService.submitAnswerAndGetNext(
            interviewId,
            currentQuestion.questionId,
            finalTranscript,
            videoUrl,
          );
        }

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

          let nextIndex;

          if (existingNextIndex === -1) {
            // Dynamic question — add to array and sort to maintain order
            updatedQuestions.push(nextQuestion);
            updatedQuestions.sort((a, b) => a.questionNumber - b.questionNumber);
            nextIndex = updatedQuestions.findIndex(
              (q) => q.questionId === response.nextQuestionId
            );
          } else {
            // Pre-generated question — update with any new data (e.g., audio URL)
            updatedQuestions[existingNextIndex] = {
              ...updatedQuestions[existingNextIndex],
              audioUrl:
                response.nextQuestionAudioUrl ||
                updatedQuestions[existingNextIndex].audioUrl,
            };
            nextIndex = existingNextIndex;
          }

          // Update interview in store
          if (interview) {
            setInterview({
              ...interview,
              questions: updatedQuestions,
            });
          }

          // Advance to next question index
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
        setIsTranscribing(false);
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
      pollForTranscription,
    ],
  );

  return {
    currentQuestion,
    currentQuestionIndex,
    isTransitioning,
    isTranscribing,
    isPrefetching,
    generationMode,
    isLastQuestion,
    totalQuestions,
    questions,
    transitionError,
    clearTransitionError,
    advanceToNext,
    goToQuestion,
    startPrefetch,
  };
};

export default useHybridInterview;
