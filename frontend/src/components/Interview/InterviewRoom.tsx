import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react"; // FIX: Added useRef for candidate video element reference (Issue 3)
import { useNavigate } from "react-router-dom";
import {
  Box,
  Container,
  Typography,
  CircularProgress,
  Paper,
  Chip,
  Button,
  Alert,
  LinearProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
} from "@mui/material";
import {
  VideoLibrary,
  CheckCircle,
  RadioButtonChecked,
  Replay,
  Stop,
  AutoAwesome, // HYBRID: Icon for dynamic question generation indicator
} from "@mui/icons-material";
import { InterviewDTO, InterviewQuestion } from "../../types";
import { interviewService } from "../../services/interview.service";
// FIX: Replaced D-ID AvatarPlayer with ElevenLabs TTS QuestionPresenter
import QuestionPresenter from "./QuestionPresenter";
import VideoRecorder from "../VideoRecorder/VideoRecorder";
import { useInterviewStore } from "../../stores/useInterviewStore";
import { useInterviewEvents } from "../../hooks/useInterviewEvents";
import { useProctoring } from "../../hooks/useProctoring"; // FIX: Import proctoring hook for surveillance system (Issue 3)
import { ProctoringWarning } from "./ProctoringWarning"; // FIX: Import proctoring warning overlay component (Issue 3)
import { useHybridInterview } from "../../hooks/useHybridInterview"; // HYBRID: Import hybrid interview hook for dynamic question generation

// ============================================================
// Props
// ============================================================

interface InterviewRoomProps {
  interviewId: number;
  initialData?: InterviewDTO;
}

// ============================================================
// Constants
// ============================================================

const MAX_VIOLATIONS = 3; // FIX: Maximum proctoring violations before interview termination (Issue 3)

// ============================================================
// InterviewRoom Component
// ============================================================

const InterviewRoom: React.FC<InterviewRoomProps> = ({
  interviewId,
  initialData,
}) => {
  const navigate = useNavigate();

  // ── Global Store ────────────────────────────────────────────
  const {
    interview,
    currentQuestionIndex,
    setInterview,
    setCurrentQuestionIndex,
    setRecordingState,
    isRecording: showRecording,
  } = useInterviewStore();

  // ── Initial Data Setup ──────────────────────────────────────
  useEffect(() => {
    if (
      initialData &&
      (!interview || interview.interviewId !== initialData.interviewId)
    ) {
      setInterview(initialData);
    }
  }, [initialData, interview, setInterview]);

  // ── Real-time Events (SSE) ──────────────────────────────────
  useInterviewEvents(interviewId);

  // ── Hybrid Interview Hook ───────────────────────────────────
  // HYBRID: Use the hybrid interview hook for dynamic question generation
  const {
    isTransitioning: isHybridTransitioning,
    generationMode,
    transitionError: hybridError,
    clearTransitionError: clearHybridError,
    advanceToNext,
  } = useHybridInterview({
    interviewId,
    onError: (err) => setError(err),
    onComplete: () => handleComplete(),
  });

  // ── Local UI State ──────────────────────────────────────────
  const [uploadProgress, setUploadProgress] = useState(0);
  const [isUploading, setIsUploading] = useState(false);
  const [isCompleting, setIsCompleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [answeredQuestionIds, setAnsweredQuestionIds] = useState<Set<number>>(
    new Set(),
  );
  const [hasStarted, setHasStarted] = useState(false);
  const [avatarReplayKey, setAvatarReplayKey] = useState(0); // bump to replay avatar
  const [endInterviewOpen, setEndInterviewOpen] = useState(false); // End Interview dialog state

  // FIX: Ref to the candidate's camera video element for BlazeFace face detection (Issue 3)
  const candidateVideoRef = useRef<HTMLVideoElement | null>(null);

  // ── Derived State ───────────────────────────────────────────
  const questions: InterviewQuestion[] = useMemo(() => {
    return interview?.questions || [];
  }, [interview]);

  const currentQuestion: InterviewQuestion | null =
    questions[currentQuestionIndex] || null;
  const totalQuestions = questions.length;
  const isLastQuestion = currentQuestionIndex === totalQuestions - 1;

  // How many avatar videos are ready
  // FIX: Count questions with audio ready instead of avatar video
  const videosReady = useMemo(
    () => questions.filter((q) => !!q.audioUrl).length,
    [questions],
  );

  const progressPercent =
    totalQuestions > 0 ? Math.round((videosReady / totalQuestions) * 100) : 0;

  // Interview is ready when IN_PROGRESS (or already further along)
  const isReady =
    interview?.status === "IN_PROGRESS" ||
    interview?.status === "PROCESSING" ||
    interview?.status === "COMPLETED";

  // How many questions have been answered (local + from server)
  const answeredCount = useMemo(() => {
    const serverAnswered = questions.filter((q) => q.answered).length;
    return Math.max(serverAnswered, answeredQuestionIds.size);
  }, [questions, answeredQuestionIds]);

  // FIX: Check if the current question has already been answered — used for read-only review mode (Issue 2)
  const isCurrentQuestionAnswered = useMemo(() => {
    if (!currentQuestion) return false;
    return (
      currentQuestion.answered ||
      answeredQuestionIds.has(currentQuestion.questionId)
    ); // FIX: Check both server-side and local answered state
  }, [currentQuestion, answeredQuestionIds]);

  // ============================================================
  // Proctoring System (Issue 3)
  // ============================================================

  // FIX: Only activate proctoring after the interview has started and first question is displayed
  const isProctoringActive = hasStarted && isReady && !isCompleting;

  const {
    violationCount,
    isWarningVisible,
    isTerminated,
    lastViolationReason,
  } = useProctoring({
    maxViolations: MAX_VIOLATIONS,
    videoRef: candidateVideoRef, // FIX: Pass candidate camera ref for face detection
    isActive: isProctoringActive, // FIX: Only activate after interview has started (not during setup/countdown)
    onViolation: (count, reason) => {
      console.warn(`Proctoring violation ${count}: ${reason}`); // FIX: Log violation for debugging
    },
    onTerminate: async (reason) => {
      // FIX: Call backend to mark interview as DISQUALIFIED — terminateInterview swallows errors internally
      await interviewService.terminateInterview(interviewId, reason);
      navigate("/interview-disqualified", {
        state: {
          violationCount: MAX_VIOLATIONS,
          reason,
          interviewId,
        },
      }); // FIX: Navigate to disqualified page with proctoring details even if backend call fails
    },
  });

  // ============================================================
  // Handlers
  // ============================================================

  /**
   * Called when the avatar video ends naturally — show the recorder.
   */
  const handleAvatarVideoEnd = useCallback(() => {
    setRecordingState(true);
  }, [setRecordingState]);

  /**
   * Complete the interview and navigate to the complete/feedback page.
   * Route: /interview/:id/complete
   */
  const handleComplete = useCallback(async () => {
    setIsCompleting(true);
    setError(null);

    try {
      await interviewService.completeInterview(interviewId);
      // ✅ Fixed: navigate to /complete which maps to InterviewComplete component
      navigate(`/interview/${interviewId}/complete`);
    } catch (err: any) {
      setError(
        err.message || "Failed to complete interview. Please try again.",
      );
      setIsCompleting(false);
    }
  }, [interviewId, navigate]);

  // ============================================================
  // End Interview Handler
  // ============================================================
  
  const handleEndInterview = useCallback(async () => {
    // Stop any ongoing TTS playback
    if (window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }

    setIsCompleting(true);
    setEndInterviewOpen(false);

    try {
      // Complete the interview with whatever answers have been submitted
      await interviewService.completeInterview(interviewId);
      navigate(`/interview/${interviewId}/complete`);
    } catch (err: any) {
      // Even if API fails, navigate to results if user has answered some questions
      if (answeredQuestionIds.size > 0) {
        navigate(`/interview/${interviewId}/complete`);
      } else {
        setError(err.message || "Failed to end interview. Please try again.");
        setIsCompleting(false);
      }
    }
  }, [interviewId, navigate, answeredQuestionIds.size]);

  const handleOpenEndInterview = useCallback(() => {
    // Stop any ongoing TTS playback
    if (window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
    setEndInterviewOpen(true);
  }, []);

  const handleCloseEndInterview = useCallback(() => {
    setEndInterviewOpen(false);
  }, []);

  /**
   * Handle video blob submission: upload via hybrid flow, advance question.
   * HYBRID: This now uses the hybrid interview hook which handles:
   * 1. Video upload (presigned or legacy fallback)
   * 2. Answer submission to backend
   * 3. Dynamic question generation (if in dynamic zone)
   * 4. Question advancement
   */
  const handleVideoSubmit = useCallback(
    async (videoBlob: Blob, answerTranscript?: string) => {
      // FIX: Top-level guard — never proceed if no question or interview terminated
      if (!currentQuestion) return;
      if (isTerminated) return; // FIX: Don't allow submission if interview has been terminated by proctoring (Issue 3)

      setIsUploading(true);
      setUploadProgress(0);
      setError(null);

      try {
        // HYBRID: Use the hybrid flow which handles upload + answer submission + next question generation
        // Pass an empty transcript if not provided — backend will get it from transcription service
        const transcript = answerTranscript || "";
        
        const success = await advanceToNext(
          videoBlob,
          transcript,
          (progress) => setUploadProgress(progress),
        );

        if (success) {
          // Mark locally as answered
          setAnsweredQuestionIds((prev) => {
            const next = new Set(prev);
            next.add(currentQuestion.questionId);
            return next;
          });
          setUploadProgress(0);
        }
        // Note: advanceToNext handles question advancement and calls onComplete for last question
      } catch (err: any) {
        // FIX: Show inline error with retry hint — NEVER navigate away from the interview on a submit error
        setError(
          (err?.message || "Failed to upload video.") +
            " Please try again — your recording is preserved.",
        );
      } finally {
        setIsUploading(false);
      }
    },
    [
      currentQuestion,
      advanceToNext,
      isTerminated, // FIX: Added isTerminated dependency for proctoring guard (Issue 3)
    ],
  );

  /**
   * Skip the current question's avatar video and start recording immediately.
   */
  const handleSkipVideo = useCallback(() => {
    setRecordingState(true);
  }, [setRecordingState]);

  // FIX: Callback to capture the candidate video element from the DOM for proctoring face detection (Issue 3)
  useEffect(() => {
    if (!hasStarted || !isReady) return;

    // FIX: Use a small delay to ensure the VideoRecorder has mounted and the video element exists in DOM
    const timer = setTimeout(() => {
      const videoEl = document.querySelector(
        "video[autoplay]",
      ) as HTMLVideoElement | null;
      if (videoEl) {
        candidateVideoRef.current = videoEl; // FIX: Capture candidate camera video element for BlazeFace
      }
    }, 1000);

    return () => clearTimeout(timer);
  }, [hasStarted, isReady, currentQuestionIndex]);

  // ============================================================
  // Render: Loading / Generating videos
  // ============================================================

  if (!isReady) {
    return (
      <Container maxWidth="sm" sx={{ py: 8, textAlign: "center" }}>
        <Paper
          elevation={0}
          sx={{
            p: 5,
            borderRadius: 4,
            border: "1px solid",
            borderColor: "divider",
          }}
        >
          {/* Circular progress with percentage */}
          <Box
            sx={{
              position: "relative",
              display: "inline-flex",
              mb: 3,
            }}
          >
            <CircularProgress
              variant="determinate"
              value={progressPercent}
              size={100}
              thickness={4}
              sx={{ color: "primary.main" }}
            />
            <Box
              sx={{
                top: 0,
                left: 0,
                bottom: 0,
                right: 0,
                position: "absolute",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <Typography variant="h5" color="primary" fontWeight={700}>
                {progressPercent}%
              </Typography>
            </Box>
          </Box>

          <Typography variant="h5" fontWeight={600} gutterBottom>
            Preparing Your Interview
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 3 }}>
            Generating AI avatar videos for your questions...
          </Typography>

          {/* Per-question progress */}
          <Box sx={{ mb: 2 }}>
            <Box
              sx={{
                display: "flex",
                justifyContent: "space-between",
                mb: 0.5,
              }}
            >
              <Typography variant="body2" color="text.secondary">
                <VideoLibrary
                  sx={{ fontSize: 16, mr: 0.5, verticalAlign: "middle" }}
                />
                Avatar videos ready
              </Typography>
              <Chip
                label={`${videosReady} / ${totalQuestions}`}
                size="small"
                color={videosReady === totalQuestions ? "success" : "default"}
              />
            </Box>
            <LinearProgress
              variant="determinate"
              value={progressPercent}
              sx={{ height: 6, borderRadius: 3 }}
            />
          </Box>

          <Typography variant="caption" color="text.secondary">
            This usually takes 30–60 seconds. Please wait...
          </Typography>
        </Paper>
      </Container>
    );
  }

  // ============================================================
  // Render: Ready to Begin — requires user gesture for autoplay
  // ============================================================

  if (!hasStarted) {
    return (
      <Container maxWidth="sm" sx={{ py: 8, textAlign: "center" }}>
        <Paper
          elevation={0}
          sx={{
            p: 5,
            borderRadius: 4,
            border: "1px solid",
            borderColor: "divider",
          }}
        >
          <CheckCircle sx={{ fontSize: 64, color: "success.main", mb: 2 }} />
          <Typography variant="h5" fontWeight={600} gutterBottom>
            Your Interview is Ready!
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 1 }}>
            {totalQuestions} questions have been tailored to your resume.
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 4 }}>
            Ensure your <strong>camera, microphone, and speakers</strong> are
            turned on. Each response can be up to 3 minutes long.
          </Typography>

          {/* FIX: Proctoring notice — inform candidate about monitoring before they start (Issue 3) */}
          <Alert severity="info" sx={{ mb: 3, textAlign: "left" }}>
            <Typography variant="body2" fontWeight={600}>
              🔒 Proctoring Enabled
            </Typography>
            <Typography variant="body2">
              This interview is monitored. Switching tabs, leaving the window,
              or looking away from the camera will be recorded as violations.
              After {MAX_VIOLATIONS} violations, the interview will be
              automatically terminated.
            </Typography>
          </Alert>

          {/* Summary chips */}
          <Box
            sx={{
              display: "flex",
              gap: 1,
              justifyContent: "center",
              mb: 4,
              flexWrap: "wrap",
            }}
          >
            <Chip
              icon={<VideoLibrary />}
              label={`${videosReady} / ${totalQuestions} videos ready`}
              color={videosReady === totalQuestions ? "success" : "warning"}
              variant="outlined"
            />
            <Chip
              icon={<RadioButtonChecked />}
              label="Video responses"
              color="primary"
              variant="outlined"
            />
          </Box>

          <Button
            variant="contained"
            size="large"
            onClick={() => setHasStarted(true)}
            sx={{ px: 6, py: 1.5, fontSize: "1rem", fontWeight: 600 }}
          >
            Begin Interview
          </Button>
        </Paper>
      </Container>
    );
  }

  // ============================================================
  // Render: Active Interview
  // ============================================================

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      {/* FIX: Proctoring warning overlay — renders above everything when a violation occurs (Issue 3) */}
      <ProctoringWarning
        violationCount={violationCount}
        maxViolations={MAX_VIOLATIONS}
        reason={lastViolationReason}
        isVisible={isWarningVisible}
        isTerminated={isTerminated}
      />

      {/* ── Top progress bar ── */}
      <Box sx={{ mb: 3 }}>
        <Box
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            mb: 1,
          }}
        >
          <Typography variant="body2" color="text.secondary" fontWeight={500}>
            Question {currentQuestionIndex + 1} of {totalQuestions}
          </Typography>
          <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
            <Typography variant="body2" color="text.secondary">
              {answeredCount} answered
            </Typography>
            {/* End Interview Button */}
            <Button
              variant="outlined"
              color="error"
              size="small"
              startIcon={<Stop />}
              onClick={handleOpenEndInterview}
              sx={{ 
                borderRadius: 2,
                textTransform: "none",
                fontWeight: 500,
              }}
            >
              End Interview
            </Button>
          </Box>
        </Box>
        <LinearProgress
          variant="determinate"
          value={
            totalQuestions > 0
              ? (currentQuestionIndex / totalQuestions) * 100
              : 0
          }
          sx={{ height: 6, borderRadius: 3 }}
        />
      </Box>

      {/* ── Error alert ── */}
      {(error || hybridError) && (
        <Alert
          severity="error"
          onClose={() => {
            setError(null);
            clearHybridError();
          }}
          sx={{ mb: 3, borderRadius: 2 }}
        >
          {error || hybridError}
        </Alert>
      )}

      {/* ── Completing overlay ── */}
      {isCompleting && (
        <Box sx={{ textAlign: "center", py: 6 }}>
          <CircularProgress size={48} sx={{ mb: 2 }} />
          <Typography variant="h6">Submitting your interview...</Typography>
          <Typography color="text.secondary">
            Please wait while we save your responses.
          </Typography>
        </Box>
      )}

      {/* HYBRID: Generating next question overlay */}
      {isHybridTransitioning && !isCompleting && (
        <Box sx={{ textAlign: "center", py: 6 }}>
          <CircularProgress size={48} sx={{ mb: 2 }} color="secondary" />
          <Typography variant="h6">
            <AutoAwesome sx={{ mr: 1, verticalAlign: "middle" }} />
            Generating Your Next Question...
          </Typography>
          <Typography color="text.secondary">
            Our AI is crafting a personalized follow-up based on your answer.
          </Typography>
        </Box>
      )}

      {/* ── Main interview layout ── */}
      {!isCompleting && !isHybridTransitioning && currentQuestion && (
        <Box
          display="grid"
          gridTemplateColumns={{ xs: "1fr", md: "1fr 1fr" }}
          gap={4}
        >
          {/* ── Left column: Avatar Player ── */}
          <Box>
            {/* FIX: Replaced D-ID AvatarPlayer with ElevenLabs TTS QuestionPresenter */}
            <QuestionPresenter
              key={`presenter-${currentQuestion.questionId}-${avatarReplayKey}`}
              questionText={currentQuestion.questionText}
              audioUrl={currentQuestion.audioUrl || null}
              questionNumber={currentQuestionIndex + 1}
              totalQuestions={totalQuestions}
              onAudioComplete={handleAvatarVideoEnd}
            />

            {/* Repeat Question button */}
            <Box
              sx={{
                display: "flex",
                gap: 1,
                mt: 1.5,
                flexWrap: "wrap",
                alignItems: "center",
              }}
            >
              <Button
                variant="outlined"
                size="small"
                startIcon={<Replay />}
                disabled={isTerminated} // FIX: Disable replay when terminated by proctoring (Issue 3)
                onClick={() => {
                  // Replay avatar by incrementing key; hide recorder until video ends again
                  setAvatarReplayKey((k) => k + 1);
                  setRecordingState(false);
                }}
                sx={{ borderRadius: 2 }}
              >
                Repeat Question
              </Button>
              {currentQuestion.category && (
                <Chip
                  label={currentQuestion.category}
                  size="small"
                  variant="outlined"
                  color="primary"
                />
              )}
              {currentQuestion.difficulty && (
                <Chip
                  label={currentQuestion.difficulty}
                  size="small"
                  variant="outlined"
                  color={
                    currentQuestion.difficulty === "HARD"
                      ? "error"
                      : currentQuestion.difficulty === "MEDIUM"
                        ? "warning"
                        : "success"
                  }
                />
              )}
              {/* HYBRID: Show indicator when question was dynamically generated */}
              {generationMode === "DYNAMIC" && (
                <Chip
                  icon={<AutoAwesome sx={{ fontSize: 14 }} />}
                  label="Adaptive"
                  size="small"
                  variant="filled"
                  color="secondary"
                  sx={{ fontWeight: 500 }}
                />
              )}
            </Box>
          </Box>

          {/* ── Right column: Video Recorder ── */}
          <Box>
            {/* FIX: If current question is already answered (navigated back), show read-only review instead of recorder (Issue 2) */}
            {isCurrentQuestionAnswered ? (
              <Paper
                elevation={0}
                sx={{
                  p: 4,
                  borderRadius: 3,
                  border: "1px solid",
                  borderColor: "success.main",
                  bgcolor: "success.50",
                  textAlign: "center",
                }}
              >
                <CheckCircle
                  sx={{ fontSize: 48, color: "success.main", mb: 1 }}
                />
                <Typography variant="h6" fontWeight={600} color="success.dark">
                  Answer Submitted
                </Typography>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  sx={{ mt: 1 }}
                >
                  Your response for this question has been recorded.
                  {/* FIX: Do not allow re-recording a previously submitted answer (Issue 2) */}
                </Typography>
              </Paper>
            ) : (
              <>
                <VideoRecorder
                  key={currentQuestion.questionId}
                  onRecordingComplete={handleVideoSubmit}
                  questionText={currentQuestion.questionText}
                  isUploading={isUploading}
                  uploadProgress={uploadProgress}
                  maxDuration={180}
                  isAvatarSpeaking={!showRecording}
                />
                {/* Provide skip option beneath if waiting for avatar */}
                {!showRecording && (
                  <Box sx={{ mt: 2, textAlign: "center" }}>
                    <Button
                      variant="outlined"
                      size="small"
                      disabled={isTerminated} // FIX: Disable skip when terminated by proctoring (Issue 3)
                      onClick={handleSkipVideo}
                      sx={{ borderRadius: 2 }}
                    >
                      Skip Video & Start Recording
                    </Button>
                  </Box>
                )}
              </>
            )}
          </Box>
        </Box>
      )}

      {/* ── Navigation row (previous / finish early) ── */}
      {!isCompleting && !isHybridTransitioning && (
        <Box
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            mt: 4,
            pt: 2,
            borderTop: "1px solid",
            borderColor: "divider",
          }}
        >
          <Button
            variant="outlined"
            disabled={currentQuestionIndex === 0 || isUploading || isTerminated || isHybridTransitioning} // FIX: Disable navigation when terminated (Issue 3); HYBRID: Also disable during question generation
            onClick={() => {
              // FIX: Only allow going back if previous question was already answered — read-only review mode (Issue 2)
              const prevIndex = currentQuestionIndex - 1;
              const prevQuestion = questions[prevIndex];
              if (
                prevQuestion &&
                (prevQuestion.answered ||
                  answeredQuestionIds.has(prevQuestion.questionId))
              ) {
                setCurrentQuestionIndex(prevIndex);
                setRecordingState(false);
              }
            }}
            sx={{ borderRadius: 2 }}
          >
            ← Previous Question
          </Button>

          <Typography variant="body2" color="text.secondary">
            {answeredCount} / {totalQuestions} answered
          </Typography>

          {/* Show "Finish Interview" only if at least 1 response submitted */}
          {answeredCount > 0 && isLastQuestion && (
            <Button
              variant="contained"
              color="success"
              onClick={handleComplete}
              disabled={isUploading || isCompleting || isTerminated || isHybridTransitioning} // FIX: Disable finish when terminated by proctoring (Issue 3); HYBRID: Also disable during question generation
              sx={{ borderRadius: 2 }}
            >
              Finish Interview
            </Button>
          )}

          {/* If not last question, show a "skip to next" option */}
          {!isLastQuestion && (
            <Button
              variant="text"
              color="inherit"
              disabled={isUploading || isTerminated || isHybridTransitioning} // FIX: Disable navigation when terminated by proctoring (Issue 3); HYBRID: Also disable during question generation
              onClick={() => {
                setCurrentQuestionIndex(currentQuestionIndex + 1);
                setRecordingState(false);
              }}
              sx={{ borderRadius: 2, color: "text.secondary" }}
            >
              Next Question →
            </Button>
          )}
        </Box>
      )}

      {/* End Interview Confirmation Dialog */}
      <Dialog
        open={endInterviewOpen}
        onClose={handleCloseEndInterview}
        aria-labelledby="end-interview-dialog-title"
        aria-describedby="end-interview-dialog-description"
      >
        <DialogTitle id="end-interview-dialog-title" sx={{ fontWeight: 600 }}>
          End Interview?
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="end-interview-dialog-description">
            Are you sure you want to end the interview? 
            {answeredQuestionIds.size > 0 ? (
              <> Your {answeredQuestionIds.size} answer{answeredQuestionIds.size > 1 ? "s" : ""} will be submitted for feedback.</>
            ) : (
              <> No answers will be recorded.</>
            )}
          </DialogContentText>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={handleCloseEndInterview} color="inherit">
            Cancel
          </Button>
          <Button 
            onClick={handleEndInterview} 
            color="error" 
            variant="contained"
            disabled={isCompleting}
            startIcon={<Stop />}
          >
            {isCompleting ? "Ending..." : "End Interview"}
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
};

export default InterviewRoom;
