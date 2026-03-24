import React, { useCallback, useEffect, useRef, useState } from "react";
import ReactPlayer from "react-player";
import {
  Box,
  Card,
  Typography,
  CircularProgress,
  Button,
  Alert,
  LinearProgress,
  IconButton,
  Tooltip,
} from "@mui/material";
import {
  Refresh,
  QuestionAnswer,
  PlayCircleOutline,
  MicNone,
  Replay,
} from "@mui/icons-material";

// Robot SVG Component - Static robot image
const RobotIcon: React.FC<{ size?: number }> = ({ size = 120 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 120 120"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    {/* Robot Head */}
    <rect x="30" y="20" width="60" height="50" rx="8" fill="#4A90D9" />
    {/* Robot Eyes */}
    <circle cx="45" cy="40" r="8" fill="#00FF88" />
    <circle cx="75" cy="40" r="8" fill="#00FF88" />
    {/* Robot Mouth */}
    <rect x="45" y="52" width="30" height="8" rx="4" fill="#1a1a2e" />
    {/* Antenna */}
    <rect x="57" y="8" width="6" height="15" fill="#4A90D9" />
    <circle cx="60" cy="8" r="5" fill="#FF6B6B" />
    {/* Robot Ears */}
    <rect x="20" y="35" width="12" height="20" rx="4" fill="#3A7BC8" />
    <rect x="88" y="35" width="12" height="20" rx="4" fill="#3A7BC8" />
    {/* Neck */}
    <rect x="50" y="70" width="20" height="8" fill="#2D5A8A" />
    {/* Robot Body */}
    <rect x="25" y="78" width="70" height="35" rx="6" fill="#4A90D9" />
    {/* Body Panel */}
    <rect x="35" y="85" width="50" height="20" rx="4" fill="#3A7BC8" />
    {/* Buttons */}
    <circle cx="50" cy="95" r="5" fill="#00FF88" />
    <circle cx="70" cy="95" r="5" fill="#FFD93D" />
    <circle cx="60" cy="105" r="4" fill="#FF6B6B" />
  </svg>
);

// Cast ReactPlayer to any to suppress "url" prop type error
const Player = ReactPlayer as any;

// ============================================================
// Props
// ============================================================

interface AvatarPlayerProps {
  /** Video URL — if undefined/empty, text-only fallback is shown */
  videoUrl?: string;
  questionText: string;
  onVideoEnd: () => void;
  questionNumber?: number;
  totalQuestions?: number;
  /**
   * Seconds to wait after video starts loading before auto-triggering
   * onVideoEnd as a fallback (so recorder always appears even if video
   * fails silently). Default: 60 seconds.
   */
  autoFallbackSeconds?: number;
}

// ============================================================
// AvatarPlayer Component
// ============================================================

const AvatarPlayer: React.FC<AvatarPlayerProps> = ({
  videoUrl,
  questionText,
  onVideoEnd,
  questionNumber,
  totalQuestions,
  autoFallbackSeconds = 60,
}) => {
  const hasVideo = Boolean(videoUrl && videoUrl.trim().length > 0);
  const [isLoading, setIsLoading] = useState(hasVideo);
  const [hasError, setHasError] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [fallbackCountdown, setFallbackCountdown] = useState<number | null>(
    null,
  );
  const [videoEnded, setVideoEnded] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);

  // Store current question text for repeat functionality
  const questionTextRef = useRef(questionText);

  // Ref to avoid stale closure in fallback timer
  const onVideoEndRef = useRef(onVideoEnd);
  useEffect(() => {
    onVideoEndRef.current = onVideoEnd;
  }, [onVideoEnd]);

  // Update ref when question text changes
  useEffect(() => {
    questionTextRef.current = questionText;
  }, [questionText]);

  // TTS function - extracted for reuse with repeat button
  const speakQuestion = useCallback(() => {
    if (!('speechSynthesis' in window)) {
      return;
    }

    // Cancel any ongoing speech
    window.speechSynthesis.cancel();

    const utterance = new SpeechSynthesisUtterance(questionTextRef.current);

    // Try to find a good English voice
    const voices = window.speechSynthesis.getVoices();
    const preferredVoice = voices.find(v => v.lang.startsWith('en') && v.name.includes('Google'))
      || voices.find(v => v.lang.startsWith('en'));
    if (preferredVoice) utterance.voice = preferredVoice;
    utterance.rate = 0.95;

    utterance.onstart = () => {
      setIsSpeaking(true);
    };

    utterance.onend = () => {
      setIsSpeaking(false);
      onVideoEndRef.current();
    };

    utterance.onerror = () => {
      setIsSpeaking(false);
      onVideoEndRef.current();
    };

    window.speechSynthesis.speak(utterance);
  }, []);

  // Auto-play after a short delay once video is available to prevent
  // play() being interrupted by pause() on fast remounts
  useEffect(() => {
    if (hasVideo && !hasError) {
      const timer = setTimeout(() => {
        setPlaying(true);
      }, 500);
      return () => clearTimeout(timer);
    }
  }, [hasVideo, hasError, videoUrl, retryKey]);

  // When there is NO video at all, speak the text using Web Speech API, then fire onVideoEnd.
  useEffect(() => {
    if (!hasVideo) {
      setIsLoading(false);
      // Slight delay before speaking so the UI mounts
      const timer = setTimeout(() => {
        speakQuestion();
      }, 800);
      return () => {
        clearTimeout(timer);
        window.speechSynthesis?.cancel();
      };
    }
  }, [hasVideo, speakQuestion]);

  // Auto-fallback countdown: if video doesn't finish within
  // autoFallbackSeconds, automatically trigger onVideoEnd so the
  // recorder is never permanently hidden.
  useEffect(() => {
    if (!hasVideo || hasError || videoEnded) return;

    setFallbackCountdown(autoFallbackSeconds);

    const interval = setInterval(() => {
      setFallbackCountdown((prev) => {
        if (prev === null || prev <= 1) {
          clearInterval(interval);
          // Fire onVideoEnd as fallback
          onVideoEndRef.current();
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasVideo, hasError, videoEnded, retryKey, autoFallbackSeconds]);

  const handleReadyOrStart = useCallback(() => {
    setIsLoading(false);
    setHasError(false);
  }, []);

  const handleProgress = useCallback((state: any) => {
    if (state.played > 0) {
      setIsLoading(false);
      setHasError(false);
    }
  }, []);

  const handleError = useCallback((err: any) => {
    console.error("ReactPlayer Error:", err);
    setIsLoading(false);
    setHasError(true);
    setPlaying(false);
    setFallbackCountdown(null);
    // When video errors, immediately show the recorder so the user
    // is not stuck waiting.
    setVideoEnded(true);
    onVideoEndRef.current();
  }, []);

  const handleEnded = useCallback(() => {
    setPlaying(false);
    setVideoEnded(true);
    setFallbackCountdown(null);
    onVideoEndRef.current();
  }, []);

  const handleRetry = useCallback(() => {
    setIsLoading(true);
    setHasError(false);
    setVideoEnded(false);
    setPlaying(false);
    setFallbackCountdown(autoFallbackSeconds);
    setRetryKey((prev) => prev + 1);
  }, [autoFallbackSeconds]);

  // Prevent right-click / download
  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
  }, []);

  return (
    <Card
      elevation={0}
      sx={{
        borderRadius: 3,
        border: "1px solid",
        borderColor: "divider",
        overflow: "hidden",
      }}
    >
      {/* Video Player — 16:9 aspect ratio */}
      <Box
        onContextMenu={handleContextMenu}
        sx={{
          position: "relative",
          width: "100%",
          paddingTop: "56.25%",
          bgcolor: "#0a0a0a",
        }}
      >
        {/* Text-only fallback — no avatar video available or permanent error */}
        {(!hasVideo || hasError) && (
          <Box
            sx={{
              position: "absolute",
              top: 0,
              left: 0,
              width: "100%",
              height: "100%",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              zIndex: 2,
              background:
                "linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)",
              p: 3,
            }}
          >
            {/* Robot Icon - Static robotic image */}
            <Box sx={{ mb: 2 }}>
              <RobotIcon size={120} />
            </Box>
            
            <Typography
              variant="body1"
              sx={{ color: "grey.300", textAlign: "center", maxWidth: 400, mb: 2 }}
            >
              {hasError
                ? "Avatar video failed to load."
                : "AI Interviewer is ready to ask you a question."}
            </Typography>

            {/* Repeat Question Button */}
            <Tooltip title="Repeat the question">
              <Button
                variant="outlined"
                color="primary"
                startIcon={isSpeaking ? <MicNone /> : <Replay />}
                onClick={speakQuestion}
                disabled={isSpeaking}
                sx={{ 
                  borderRadius: 8, 
                  px: 3,
                  mb: 2,
                  borderColor: "primary.main",
                  color: "primary.main",
                  "&:hover": {
                    borderColor: "primary.dark",
                    backgroundColor: "rgba(74, 144, 217, 0.1)",
                  }
                }}
              >
                {isSpeaking ? "Speaking..." : "Repeat Question"}
              </Button>
            </Tooltip>

            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <MicNone sx={{ color: "success.light", fontSize: 20 }} />
              <Typography variant="body2" sx={{ color: "success.light" }}>
                Recorder is starting...
              </Typography>
            </Box>
          </Box>
        )}

        {/* Loading Spinner */}
        {hasVideo && isLoading && !hasError && (
          <Box
            sx={{
              position: "absolute",
              top: 0,
              left: 0,
              width: "100%",
              height: "100%",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              zIndex: 2,
            }}
          >
            <CircularProgress size={48} sx={{ color: "#fff", mb: 1.5 }} />
            <Typography variant="body2" sx={{ color: "grey.400" }}>
              Loading avatar video...
            </Typography>
          </Box>
        )}

        {/* Error State */}
        {hasVideo && hasError && !videoEnded && (
          <Box
            sx={{
              position: "absolute",
              top: 0,
              left: 0,
              width: "100%",
              height: "100%",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              zIndex: 2,
              p: 3,
            }}
          >
            <Alert
              severity="warning"
              sx={{ mb: 2, maxWidth: 360 }}
              action={
                <Button
                  color="inherit"
                  size="small"
                  onClick={handleRetry}
                  startIcon={<Refresh />}
                >
                  Retry
                </Button>
              }
            >
              Video failed to load — recorder will start automatically.
            </Alert>
            <Typography
              variant="body2"
              sx={{ color: "grey.400", textAlign: "center" }}
            >
              You can read the question below and record your answer.
            </Typography>
          </Box>
        )}

        {/* React Player */}
        {hasVideo && !hasError && !videoEnded && (
          <Box
            sx={{
              position: "absolute",
              top: 0,
              left: 0,
              width: "100%",
              height: "100%",
              cursor: "pointer",
            }}
            onClick={() => setPlaying(!playing)}
          >
            <Player
              key={retryKey}
              url={videoUrl}
              playing={playing}
              controls={true}
              width="100%"
              height="100%"
              onReady={handleReadyOrStart}
              onStart={handleReadyOrStart}
              onPlay={handleReadyOrStart}
              onProgress={handleProgress}
              onError={handleError}
              onEnded={handleEnded}
              config={{
                file: {
                  attributes: {
                    playsInline: true,
                  },
                },
              }}
            />

            {/* Fallback countdown bar — shows when video is playing */}
            {playing &&
              !isLoading &&
              fallbackCountdown !== null &&
              fallbackCountdown > 0 && (
                <Box
                  sx={{
                    position: "absolute",
                    bottom: 0,
                    left: 0,
                    width: "100%",
                    zIndex: 11,
                    px: 1,
                    pb: 0.5,
                  }}
                >
                  <LinearProgress
                    variant="determinate"
                    value={
                      100 - (fallbackCountdown / autoFallbackSeconds) * 100
                    }
                    sx={{
                      height: 3,
                      borderRadius: 0,
                      bgcolor: "rgba(255,255,255,0.2)",
                      "& .MuiLinearProgress-bar": { bgcolor: "success.light" },
                    }}
                  />
                </Box>
              )}

            {/* Play Overlay if paused or blocked */}
            {!playing && !isLoading && !videoEnded && (
              <Box
                sx={{
                  position: "absolute",
                  top: 0,
                  left: 0,
                  width: "100%",
                  height: "100%",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  bgcolor: "rgba(0,0,0,0.3)",
                  zIndex: 10,
                }}
              >
                <Button
                  variant="contained"
                  size="large"
                  startIcon={<PlayCircleOutline />}
                  onClick={(e) => {
                    e.stopPropagation();
                    setPlaying(true);
                    // Reset countdown when user manually plays
                    setFallbackCountdown(autoFallbackSeconds);
                  }}
                  sx={{ borderRadius: 8, px: 3, py: 1.5 }}
                >
                  Play Question
                </Button>
              </Box>
            )}
          </Box>
        )}
      </Box>

      {/* Question Info */}
      <Box sx={{ p: 2.5 }}>
        {/* Question Counter */}
        {questionNumber && totalQuestions && (
          <Box
            sx={{
              display: "inline-flex",
              alignItems: "center",
              gap: 0.75,
              bgcolor: "primary.main",
              color: "#fff",
              px: 1.5,
              py: 0.4,
              borderRadius: 1,
              mb: 1.5,
            }}
          >
            <QuestionAnswer sx={{ fontSize: 16 }} />
            <Typography variant="caption" sx={{ fontWeight: 600 }}>
              Question {questionNumber} of {totalQuestions}
            </Typography>
          </Box>
        )}

        {/* Question Text with Repeat Button */}
        <Box sx={{ display: "flex", alignItems: "flex-start", gap: 2 }}>
          <Typography 
            variant="body1" 
            sx={{ fontWeight: 500, lineHeight: 1.6, flex: 1 }}
          >
            {questionText}
          </Typography>
          
          {/* Repeat Question Button - Always visible */}
          <Tooltip title="Repeat the question">
            <IconButton
              onClick={speakQuestion}
              disabled={isSpeaking}
              color="primary"
              size="small"
              sx={{
                backgroundColor: "rgba(74, 144, 217, 0.1)",
                "&:hover": {
                  backgroundColor: "rgba(74, 144, 217, 0.2)",
                }
              }}
            >
              {isSpeaking ? <MicNone fontSize="small" /> : <Replay fontSize="small" />}
            </IconButton>
          </Tooltip>
        </Box>
      </Box>
    </Card>
  );
};

export default AvatarPlayer;
