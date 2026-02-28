import React, { useCallback, useEffect, useMemo, useState } from "react";
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
import AvatarPlayer from "../AIAvatar/AvatarPlayer";
import VideoRecorder from "../VideoRecorder/VideoRecorder";
import { useInterviewStore } from "../../stores/useInterviewStore";
import { useInterviewEvents } from "../../hooks/useInterviewEvents";

// ============================================================
// Props
// ============================================================

interface InterviewRoomProps {
  interviewId: number;
  initialData?: InterviewDTO;
}

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

  // ── Derived State ───────────────────────────────────────────
  const questions: InterviewQuestion[] = useMemo(() => {
    return interview?.questions || [];
  }, [interview]);

  const currentQuestion: InterviewQuestion | null =
    questions[currentQuestionIndex] || null;
  const totalQuestions = questions.length;
  const isLastQuestion = currentQuestionIndex === totalQuestions - 1;

  // How many avatar videos are ready
  const videosReady = useMemo(
    () => questions.filter((q) => !!q.avatarVideoUrl).length,
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
      if (!currentQuestion) return;

      setIsUploading(true);
      setUploadProgress(0);
      setError(null);

      try {
        await interviewService.submitVideoPresigned(
          interviewId,
          currentQuestion.questionId,
          videoBlob,
          (progress) => setUploadProgress(progress),
        );

        // Mark locally as answered
        setAnsweredQuestionIds((prev) => {
          const next = new Set(prev);
          next.add(currentQuestion.questionId);
          return next;
        });

        if (!isLastQuestion) {
          // Advance to next question and reset recorder state
          setCurrentQuestionIndex(currentQuestionIndex + 1);
          setRecordingState(false);
          setUploadProgress(0);
        } else {
          // Last question answered — complete the interview
          await handleComplete();
        }
      } catch (err: any) {
        setError(err.message || "Failed to upload video. Please try again.");
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
    ],
  );

  /**
   * Skip the current question's avatar video and start recording immediately.
   */
  const handleSkipVideo = useCallback(() => {
    setRecordingState(true);
  }, [setRecordingState]);

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
            <AvatarPlayer
              key={`avatar-${currentQuestion.questionId}-${avatarReplayKey}`}
              videoUrl={currentQuestion.avatarVideoUrl || undefined}
              questionText={currentQuestion.questionText}
              onVideoEnd={handleAvatarVideoEnd}
              questionNumber={currentQuestionIndex + 1}
              totalQuestions={totalQuestions}
            />

            {/* Repeat Question button */}
            <Box sx={{ display: 'flex', gap: 1, mt: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
              <Button
                variant="outlined"
                size="small"
                startIcon={<Replay />}
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
              <Box sx={{ mt: 2, textAlign: 'center' }}>
                <Button
                  variant="outlined"
                  size="small"
                  onClick={handleSkipVideo}
                  sx={{ borderRadius: 2 }}
                >
                  Skip Video & Start Recording
                </Button>
              </Box>
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
            disabled={currentQuestionIndex === 0 || isUploading}
            onClick={() => {
              setCurrentQuestionIndex(currentQuestionIndex - 1);
              setRecordingState(false);
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
              disabled={isUploading || isCompleting}
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
              disabled={isUploading}
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
