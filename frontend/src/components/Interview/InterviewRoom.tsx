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
} from "@mui/material";
import {
  VideoLibrary,
  CheckCircle,
  RadioButtonChecked,
  Replay,
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

  /**
   * Handle video blob submission: upload via presigned flow, advance question.
   */
  const handleVideoSubmit = useCallback(
    async (videoBlob: Blob) => {
      // FIX: Top-level guard — never proceed if no question or interview terminated
      if (!currentQuestion) return;
      if (isTerminated) return; // FIX: Don't allow submission if interview has been terminated by proctoring (Issue 3)

      setIsUploading(true);
      setUploadProgress(0);
      setError(null);

      try {
        // FIX: Try presigned upload first, fall back to legacy multipart if it fails
        try {
          await interviewService.submitVideoPresigned(
            interviewId,
            currentQuestion.questionId,
            videoBlob,
            (progress) => setUploadProgress(progress),
          );
        } catch (presignedErr: any) {
          // FIX: Presigned upload failed — fall back to legacy multipart upload so the answer is not lost
          console.warn(
            "Presigned upload failed, falling back to legacy multipart:",
            presignedErr?.message,
          );
          setUploadProgress(0);
          await interviewService.submitVideoResponse(
            videoBlob,
            interviewId,
            currentQuestion.questionId,
            (progress) => setUploadProgress(progress),
          );
        }

        // Mark locally as answered
        setAnsweredQuestionIds((prev) => {
          const next = new Set(prev);
          next.add(currentQuestion.questionId);
          return next;
        });

        if (!isLastQuestion) {
          // FIX: Advance to next question and reset recorder state (Issue 2 — sequential one-at-a-time flow)
          const next = currentQuestionIndex + 1; // FIX: Capture next index before state update
          setCurrentQuestionIndex(next);
          setRecordingState(false);
          setUploadProgress(0);
        } else {
          // Last question answered — complete the interview
          await handleComplete();
        }
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
      interviewId,
      currentQuestion,
      isLastQuestion,
      currentQuestionIndex,
      setCurrentQuestionIndex,
      setRecordingState,
      handleComplete,
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
          <Typography variant="body2" color="text.secondary">
            {answeredCount} answered
          </Typography>
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
      {error && (
        <Alert
          severity="error"
          onClose={() => setError(null)}
          sx={{ mb: 3, borderRadius: 2 }}
        >
          {error}
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

      {/* ── Main interview layout ── */}
      {!isCompleting && currentQuestion && (
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
      {!isCompleting && (
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
            disabled={currentQuestionIndex === 0 || isUploading || isTerminated} // FIX: Disable navigation when terminated (Issue 3); also only allow going back to already-answered questions (Issue 2)
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
              disabled={isUploading || isCompleting || isTerminated} // FIX: Disable finish when terminated by proctoring (Issue 3)
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
              disabled={isUploading || isTerminated} // FIX: Disable navigation when terminated by proctoring (Issue 3)
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
    </Container>
  );
};

export default InterviewRoom;
