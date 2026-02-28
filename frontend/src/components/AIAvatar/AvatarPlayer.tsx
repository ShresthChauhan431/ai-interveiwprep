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
} from "@mui/material";
import {
  Refresh,
  QuestionAnswer,
  PlayCircleOutline,
  MicNone,
} from "@mui/icons-material";

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

  // Ref to avoid stale closure in fallback timer
  const onVideoEndRef = useRef(onVideoEnd);
  useEffect(() => {
    onVideoEndRef.current = onVideoEnd;
  }, [onVideoEnd]);

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
      if ('speechSynthesis' in window) {
        setIsLoading(false);
        const utterance = new SpeechSynthesisUtterance(questionText);

        // Try to find a good English voice
        const voices = window.speechSynthesis.getVoices();
        const preferredVoice = voices.find(v => v.lang.startsWith('en') && v.name.includes('Google'))
          || voices.find(v => v.lang.startsWith('en'));
        if (preferredVoice) utterance.voice = preferredVoice;
        utterance.rate = 0.95; // Slightly slower for clarity

        utterance.onend = () => {
          onVideoEndRef.current();
        };
        utterance.onerror = () => {
          onVideoEndRef.current();
        };

        // Slight delay before speaking so the UI mounts
        const timer = setTimeout(() => {
          window.speechSynthesis.speak(utterance);
        }, 800);

        return () => {
          clearTimeout(timer);
          window.speechSynthesis.cancel();
        };
      } else {
        const timer = setTimeout(() => {
          onVideoEndRef.current();
        }, 800); // small delay so the UI renders first
        return () => clearTimeout(timer);
      }
    }
  }, [hasVideo, questionText]);

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
            <QuestionAnswer sx={{ fontSize: 48, color: "grey.400", mb: 1.5 }} />
            <Typography
              variant="body1"
              sx={{ color: "grey.300", textAlign: "center", maxWidth: 400 }}
            >
              {hasError
                ? "Avatar video failed to load."
                : "Avatar video is not available for this question."}
            </Typography>
            <Typography
              variant="body2"
              sx={{ color: "grey.500", textAlign: "center", mt: 0.5 }}
            >
              Read the question below — the recorder will appear shortly.
            </Typography>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1, mt: 2 }}>
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

        {/* Question Text */}
        <Typography variant="body1" sx={{ fontWeight: 500, lineHeight: 1.6 }}>
          {questionText}
        </Typography>
      </Box>
    </Card>
  );
};

export default AvatarPlayer;
