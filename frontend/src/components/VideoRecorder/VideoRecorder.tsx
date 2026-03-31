import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Box,
  Button,
  LinearProgress,
  Typography,
  Alert,
  CircularProgress,
  Paper,
  Chip,
} from "@mui/material";
import { Videocam, Send, Replay, VideocamOff, Mic, MicOff } from "@mui/icons-material";
import { useVideoRecording } from "../../hooks/useVideoRecording";
import { useMediaPermissions } from "../../hooks/useMediaPermissions";
import { useSpeechRecognition } from "../../hooks/useSpeechRecognition";

// ============================================================
// Props
// ============================================================

interface VideoRecorderProps {
  onRecordingComplete: (videoBlob: Blob, transcript?: string) => Promise<void>;
  maxDuration?: number;
  questionText: string;
  isUploading?: boolean;
  uploadProgress?: number;
  isAvatarSpeaking?: boolean;
}

// ============================================================
// Helper — format seconds to MM:SS
// ============================================================

const formatTime = (seconds: number): string => {
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
};

// ============================================================
// VideoRecorder Component
// ============================================================

const VideoRecorder: React.FC<VideoRecorderProps> = ({
  onRecordingComplete,
  maxDuration = 180,
  questionText,
  isUploading = false,
  uploadProgress = 0,
  isAvatarSpeaking = false,
}) => {
  const {
    isRecording,
    recordedBlob,
    previewUrl,
    error: recordingError,
    recordingTime,
    liveStream,
    startRecording,
    stopRecording,
  } = useVideoRecording();

  const {
    hasPermission,
    isChecking: isCheckingPermissions,
    error: permissionError,
    requestPermissions,
  } = useMediaPermissions();

  // Browser-based speech recognition for local mode transcription fallback
  const {
    isSupported: isSpeechSupported,
    isListening,
    transcript,
    interimTranscript,
    error: speechError,
    startListening,
    stopListening,
    resetTranscript,
  } = useSpeechRecognition();

  const [submitError, setSubmitError] = useState<string | null>(null);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    if (videoRef.current && liveStream) {
      if (videoRef.current.srcObject !== liveStream) {
        videoRef.current.srcObject = liveStream;
      }
    }
  }, [liveStream, isRecording]);

  // ============================================================
  // Live camera preview
  // ============================================================

  const startCameraPreview = useCallback(async () => {
    const stream = await requestPermissions();
    if (stream && videoRef.current) {
      videoRef.current.srcObject = stream;
      streamRef.current = stream;
    }
  }, [requestPermissions]);

  // ============================================================
  // Auto-start and Auto-submit states
  // ============================================================
  const [autoStartCountdown, setAutoStartCountdown] = useState<number | null>(
    null,
  );
  const [isAutoSubmitting, setIsAutoSubmitting] = useState(false);

  // Stop live preview tracks when not needed
  const stopCameraPreview = useCallback(() => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
  }, []);

  // ============================================================
  // Handlers
  // ============================================================

  const handleStartRecording = useCallback(async () => {
    setSubmitError(null);
    stopCameraPreview();
    setIsAutoSubmitting(false);
    resetTranscript(); // Reset browser transcript for new recording
    await startRecording(maxDuration);
    
    // Start browser speech recognition alongside video recording
    if (isSpeechSupported) {
      startListening();
    }
  }, [maxDuration, startRecording, stopCameraPreview, isSpeechSupported, startListening, resetTranscript]);

  // Start camera preview when permission is granted
  useEffect(() => {
    if (hasPermission && !isRecording && !recordedBlob) {
      startCameraPreview();
    }

    return () => {
      stopCameraPreview();
    };
  }, [
    hasPermission,
    isRecording,
    recordedBlob,
    startCameraPreview,
    stopCameraPreview,
  ]);

  // Handle auto-start countdown
  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (
      hasPermission &&
      !isRecording &&
      !recordedBlob &&
      !isCheckingPermissions &&
      !isAvatarSpeaking
    ) {
      if (autoStartCountdown === null) {
        setAutoStartCountdown(3);
      } else if (autoStartCountdown > 0) {
        timer = setTimeout(() => {
          setAutoStartCountdown((prev) => (prev !== null ? prev - 1 : null));
        }, 1000);
      } else if (autoStartCountdown === 0) {
        handleStartRecording();
        setAutoStartCountdown(null);
      }
    } else {
      // Reset countdown if state changes (e.g. avatar starts speaking again or recording starts)
      setAutoStartCountdown(null);
    }
    return () => clearTimeout(timer);
  }, [
    hasPermission,
    isRecording,
    recordedBlob,
    isCheckingPermissions,
    isAvatarSpeaking,
    autoStartCountdown,
    handleStartRecording,
  ]);

  const handleFinishAnswering = () => {
    setIsAutoSubmitting(true);
    stopRecording();
    stopListening(); // Stop browser speech recognition
  };

  const handleReRecord = () => {
    setSubmitError(null);
    setIsAutoSubmitting(false);
    resetTranscript(); // Clear previous transcript
    startCameraPreview();
  };

  const handleSubmit = useCallback(async () => {
    if (!recordedBlob) return;

    setSubmitError(null);

    try {
      // Pass the browser-captured transcript alongside the video blob
      // This provides transcription even when AssemblyAI can't access local files
      const browserTranscript = transcript.trim() || undefined;
      await onRecordingComplete(recordedBlob, browserTranscript);
    } catch (err: any) {
      setSubmitError(
        err.message || "Failed to submit response. Please try again.",
      );
    }
  }, [recordedBlob, onRecordingComplete, transcript]);

  // Auto-submit when recording is ready
  useEffect(() => {
    if (recordedBlob && isAutoSubmitting && !submitError) {
      handleSubmit();
      setIsAutoSubmitting(false); // Prevent multiple submissions
    }
  }, [recordedBlob, isAutoSubmitting, submitError, handleSubmit]);

  // ============================================================
  // Recording progress (percentage of max duration)
  // ============================================================

  const recordingProgress = maxDuration
    ? (recordingTime / maxDuration) * 100
    : 0;

  // ============================================================
  // Render
  // ============================================================

  const error = recordingError || permissionError || submitError || speechError;

  return (
    <Paper
      elevation={0}
      sx={{
        p: 3,
        borderRadius: 3,
        border: "1px solid",
        borderColor: "divider",
        bgcolor: "background.paper",
      }}
    >
      {/* Question Text */}
      <Typography variant="h6" gutterBottom sx={{ mb: 2 }}>
        {questionText}
      </Typography>

      {/* Error Alert */}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* ===== State: Checking Permissions ===== */}
      {isCheckingPermissions && (
        <Box
          sx={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            py: 6,
          }}
        >
          <CircularProgress size={48} sx={{ mb: 2 }} />
          <Typography color="text.secondary">
            Checking camera and microphone permissions...
          </Typography>
        </Box>
      )}

      {/* ===== State: Permission Denied ===== */}
      {!isCheckingPermissions && hasPermission === false && (
        <Box sx={{ textAlign: "center", py: 6 }}>
          <VideocamOff sx={{ fontSize: 64, color: "text.disabled", mb: 2 }} />
          <Typography variant="h6" gutterBottom>
            Camera Access Required
          </Typography>
          <Typography
            color="text.secondary"
            sx={{ mb: 3, maxWidth: 400, mx: "auto" }}
          >
            Please allow camera and microphone access in your browser settings
            to record your response.
          </Typography>
          <Button
            variant="contained"
            startIcon={<Videocam />}
            onClick={() => requestPermissions()}
          >
            Grant Access
          </Button>
        </Box>
      )}

      {/* ===== Persistent Video Container ===== */}
      {hasPermission && !recordedBlob && !isCheckingPermissions && (
        <Box>
          <Box
            sx={{
              position: "relative",
              borderRadius: 2,
              overflow: "hidden",
              bgcolor: "#000",
              mb: 2,
            }}
          >
            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted
              style={{ width: "100%", display: "block", maxHeight: 400 }}
            />

            {/* Recording overlays */}
            {isRecording && (
              <>
                <Box
                  sx={{
                    position: "absolute",
                    top: 12,
                    left: 12,
                    display: "flex",
                    alignItems: "center",
                    gap: 1,
                    bgcolor: "rgba(0,0,0,0.6)",
                    px: 1.5,
                    py: 0.5,
                    borderRadius: 1,
                    zIndex: 1,
                  }}
                >
                  <Box
                    sx={{
                      width: 10,
                      height: 10,
                      borderRadius: "50%",
                      bgcolor: "error.main",
                      animation: "pulse 1s infinite",
                      "@keyframes pulse": {
                        "0%, 100%": { opacity: 1 },
                        "50%": { opacity: 0.3 },
                      },
                    }}
                  />
                  <Typography
                    variant="body2"
                    sx={{ color: "#fff", fontFamily: "monospace" }}
                  >
                    {formatTime(recordingTime)}
                  </Typography>
                </Box>
                
                {/* Speech recognition indicator */}
                {isSpeechSupported && (
                  <Box
                    sx={{
                      position: "absolute",
                      top: 12,
                      right: 12,
                      display: "flex",
                      alignItems: "center",
                      gap: 0.5,
                      bgcolor: "rgba(0,0,0,0.6)",
                      px: 1,
                      py: 0.5,
                      borderRadius: 1,
                      zIndex: 1,
                    }}
                  >
                    {isListening ? (
                      <Mic sx={{ fontSize: 16, color: "#4caf50" }} />
                    ) : (
                      <MicOff sx={{ fontSize: 16, color: "#ff9800" }} />
                    )}
                    <Typography
                      variant="caption"
                      sx={{ color: "#fff", fontSize: "0.7rem" }}
                    >
                      {isListening ? "Transcribing" : "No speech"}
                    </Typography>
                  </Box>
                )}

                <Typography
                  variant="body2"
                  sx={{
                    position: "absolute",
                    bottom: 12,
                    right: 12,
                    color: "#fff",
                    bgcolor: "rgba(0,0,0,0.6)",
                    px: 1.5,
                    py: 0.5,
                    borderRadius: 1,
                    zIndex: 1,
                  }}
                >
                  Recording...
                </Typography>
              </>
            )}
          </Box>

          {/* ===== State: Ready to Record Controls ===== */}
          {!isRecording && (
            <>
              {isAvatarSpeaking ? (
                <Typography
                  variant="body2"
                  color="error"
                  textAlign="center"
                  mt={2}
                  sx={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    gap: 1,
                  }}
                >
                  <Videocam sx={{ animation: "pulse 1.5s infinite" }} />
                  Camera active. Please listen to the question...
                </Typography>
              ) : (
                <Box
                  sx={{
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "center",
                    mt: 2,
                  }}
                >
                  {autoStartCountdown !== null && autoStartCountdown > 0 ? (
                    <Typography
                      variant="h6"
                      color="error"
                      sx={{ fontWeight: "bold" }}
                    >
                      Recording starts in {autoStartCountdown}...
                    </Typography>
                  ) : (
                    <Button
                      variant="contained"
                      color="error"
                      size="large"
                      startIcon={<Videocam />}
                      onClick={handleStartRecording}
                      sx={{ px: 4, py: 1.5 }}
                    >
                      Start Recording Now
                    </Button>
                  )}
                </Box>
              )}

              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: "block", textAlign: "center", mt: 1 }}
              >
                Maximum duration: {formatTime(maxDuration)}
              </Typography>
            </>
          )}

          {/* ===== State: Recording Controls ===== */}
          {isRecording && (
            <>
              <LinearProgress
                variant="determinate"
                value={recordingProgress}
                color="error"
                sx={{ mb: 2, height: 6, borderRadius: 3 }}
              />
              
              {/* Live transcript preview */}
              {isSpeechSupported && (transcript || interimTranscript) && (
                <Paper
                  variant="outlined"
                  sx={{
                    p: 1.5,
                    mb: 2,
                    bgcolor: "grey.50",
                    maxHeight: 80,
                    overflowY: "auto",
                  }}
                >
                  <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
                    <Mic sx={{ fontSize: 12, mr: 0.5, verticalAlign: "middle" }} />
                    Live Transcription:
                  </Typography>
                  <Typography variant="body2" sx={{ wordBreak: "break-word" }}>
                    {transcript}
                    <span style={{ color: "#888", fontStyle: "italic" }}>
                      {interimTranscript}
                    </span>
                  </Typography>
                </Paper>
              )}

              <Box sx={{ display: "flex", justifyContent: "center" }}>
                <Button
                  variant="contained"
                  color="primary"
                  size="large"
                  startIcon={<Send />}
                  onClick={handleFinishAnswering}
                  sx={{ px: 4, py: 1.5 }}
                >
                  Finish Answering & Submit
                </Button>
              </Box>
            </>
          )}
        </Box>
      )}

      {/* ===== State: Preview (recorded video) ===== */}
      {!isRecording && recordedBlob && previewUrl && (
        <Box>
          {/* Playback */}
          <Box
            sx={{
              borderRadius: 2,
              overflow: "hidden",
              bgcolor: "#000",
              mb: 2,
            }}
          >
            <video
              src={previewUrl}
              controls
              playsInline
              style={{ width: "100%", display: "block", maxHeight: 400 }}
            />
          </Box>

          {/* Captured transcript preview */}
          {transcript && (
            <Paper
              variant="outlined"
              sx={{
                p: 1.5,
                mb: 2,
                bgcolor: "success.50",
                border: "1px solid",
                borderColor: "success.light",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.5, mb: 0.5 }}>
                <Mic sx={{ fontSize: 14, color: "success.main" }} />
                <Typography variant="caption" color="success.dark" fontWeight={500}>
                  Captured Transcript
                </Typography>
                <Chip 
                  label="Browser" 
                  size="small" 
                  sx={{ ml: "auto", fontSize: "0.65rem", height: 18 }} 
                />
              </Box>
              <Typography variant="body2" sx={{ wordBreak: "break-word", maxHeight: 60, overflowY: "auto" }}>
                {transcript}
              </Typography>
            </Paper>
          )}

          {/* Action Buttons */}
          <Box sx={{ display: "flex", justifyContent: "center", gap: 2 }}>
            <Button
              variant="outlined"
              startIcon={<Replay />}
              onClick={handleReRecord}
              disabled={isUploading}
            >
              Re-record
            </Button>
            <Button
              variant="contained"
              color="primary"
              startIcon={<Send />}
              onClick={handleSubmit}
              disabled={isUploading}
              sx={{ px: 4 }}
            >
              {isUploading ? "Submitting..." : "Submit Answer"}
            </Button>
          </Box>

          {/* Upload Progress */}
          {isUploading && (
            <Box sx={{ mt: 2 }}>
              <LinearProgress
                variant={uploadProgress > 0 ? "determinate" : "indeterminate"}
                value={uploadProgress}
                sx={{ height: 6, borderRadius: 3 }}
              />
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: "block", textAlign: "center", mt: 0.5 }}
              >
                {uploadProgress > 0
                  ? `Uploading... ${uploadProgress}%`
                  : "Uploading..."}
              </Typography>
            </Box>
          )}
        </Box>
      )}
    </Paper>
  );
};

export default VideoRecorder;
