import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
    Box,
    Button,
    LinearProgress,
    Typography,
    Alert,
    CircularProgress,
    Paper,
} from '@mui/material';
import {
    Videocam,
    Stop,
    Send,
    Replay,
    VideocamOff,
} from '@mui/icons-material';
import { useVideoRecording } from '../../hooks/useVideoRecording';
import { useMediaPermissions } from '../../hooks/useMediaPermissions';

// ============================================================
// Props
// ============================================================

interface VideoRecorderProps {
    onRecordingComplete: (videoBlob: Blob) => Promise<void>;
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
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
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
        startRecording,
        stopRecording,
    } = useVideoRecording();

    const {
        hasPermission,
        isChecking: isCheckingPermissions,
        error: permissionError,
        requestPermissions,
    } = useMediaPermissions();

    const [submitError, setSubmitError] = useState<string | null>(null);

    const liveVideoRef = useRef<HTMLVideoElement | null>(null);
    const streamRef = useRef<MediaStream | null>(null);

    // ============================================================
    // Live camera preview
    // ============================================================

    const startCameraPreview = useCallback(async () => {
        const stream = await requestPermissions();
        if (stream && liveVideoRef.current) {
            liveVideoRef.current.srcObject = stream;
            streamRef.current = stream;
        }
    }, [requestPermissions]);

    // ============================================================
    // Auto-start and Auto-submit states
    // ============================================================
    const [autoStartCountdown, setAutoStartCountdown] = useState<number | null>(null);
    const [isAutoSubmitting, setIsAutoSubmitting] = useState(false);

    // Stop live preview tracks when not needed
    const stopCameraPreview = useCallback(() => {
        if (streamRef.current) {
            streamRef.current.getTracks().forEach((track) => track.stop());
            streamRef.current = null;
        }
        if (liveVideoRef.current) {
            liveVideoRef.current.srcObject = null;
        }
    }, []);

    // Start camera preview when permission is granted
    useEffect(() => {
        if (hasPermission && !isRecording && !recordedBlob) {
            startCameraPreview();
        }

        return () => {
            stopCameraPreview();
        };
    }, [hasPermission, isRecording, recordedBlob, startCameraPreview, stopCameraPreview]);

    // Handle auto-start countdown
    useEffect(() => {
        let timer: NodeJS.Timeout;
        if (hasPermission && !isRecording && !recordedBlob && !isCheckingPermissions && !isAvatarSpeaking) {
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
    }, [hasPermission, isRecording, recordedBlob, isCheckingPermissions, isAvatarSpeaking, autoStartCountdown]);

    // ============================================================
    // Handlers
    // ============================================================

    const handleStartRecording = async () => {
        setSubmitError(null);
        stopCameraPreview();
        setIsAutoSubmitting(false);
        await startRecording(maxDuration);
    };

    const handleStopRecording = () => {
        stopRecording();
    };

    const handleFinishAnswering = () => {
        setIsAutoSubmitting(true);
        stopRecording();
    };

    const handleReRecord = () => {
        setSubmitError(null);
        setIsAutoSubmitting(false);
        startCameraPreview();
    };

    const handleSubmit = useCallback(async () => {
        if (!recordedBlob) return;

        setSubmitError(null);

        try {
            await onRecordingComplete(recordedBlob);
        } catch (err: any) {
            setSubmitError(err.message || 'Failed to submit response. Please try again.');
        }
    }, [recordedBlob, onRecordingComplete]);

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

    const recordingProgress = maxDuration ? (recordingTime / maxDuration) * 100 : 0;

    // ============================================================
    // Render
    // ============================================================

    const error = recordingError || permissionError || submitError;

    return (
        <Paper
            elevation={0}
            sx={{
                p: 3,
                borderRadius: 3,
                border: '1px solid',
                borderColor: 'divider',
                bgcolor: 'background.paper',
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
                <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6 }}>
                    <CircularProgress size={48} sx={{ mb: 2 }} />
                    <Typography color="text.secondary">
                        Checking camera and microphone permissions...
                    </Typography>
                </Box>
            )}

            {/* ===== State: Permission Denied ===== */}
            {!isCheckingPermissions && hasPermission === false && (
                <Box sx={{ textAlign: 'center', py: 6 }}>
                    <VideocamOff sx={{ fontSize: 64, color: 'text.disabled', mb: 2 }} />
                    <Typography variant="h6" gutterBottom>
                        Camera Access Required
                    </Typography>
                    <Typography color="text.secondary" sx={{ mb: 3, maxWidth: 400, mx: 'auto' }}>
                        Please allow camera and microphone access in your browser settings to record your response.
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

            {/* ===== State: Ready to Record (live camera preview) ===== */}
            {hasPermission && !isRecording && !recordedBlob && !isCheckingPermissions && (
                <Box>
                    {/* Live Preview */}
                    <Box
                        sx={{
                            position: 'relative',
                            borderRadius: 2,
                            overflow: 'hidden',
                            bgcolor: '#000',
                            mb: 2,
                        }}
                    >
                        <video
                            ref={liveVideoRef}
                            autoPlay
                            playsInline
                            muted
                            style={{ width: '100%', display: 'block', maxHeight: 400 }}
                        />
                    </Box>

                    {/* Start Button or Surveillance message */}
                    {isAvatarSpeaking ? (
                        <Typography
                            variant="body2"
                            color="error"
                            textAlign="center"
                            mt={2}
                            sx={{
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: 1,
                            }}
                        >
                            <Videocam sx={{ animation: 'pulse 1.5s infinite' }} />
                            Camera active. Please listen to the question...
                        </Typography>
                    ) : (
                        <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', mt: 2 }}>
                            {autoStartCountdown !== null && autoStartCountdown > 0 ? (
                                <Typography variant="h6" color="error" sx={{ fontWeight: 'bold' }}>
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
                        sx={{ display: 'block', textAlign: 'center', mt: 1 }}
                    >
                        Maximum duration: {formatTime(maxDuration)}
                    </Typography>
                </Box>
            )}

            {/* ===== State: Recording ===== */}
            {isRecording && (
                <Box>
                    {/* Recording indicator */}
                    <Box
                        sx={{
                            position: 'relative',
                            borderRadius: 2,
                            overflow: 'hidden',
                            bgcolor: '#000',
                            mb: 2,
                        }}
                    >
                        {/* Recording badge */}
                        <Box
                            sx={{
                                position: 'absolute',
                                top: 12,
                                left: 12,
                                display: 'flex',
                                alignItems: 'center',
                                gap: 1,
                                bgcolor: 'rgba(0,0,0,0.6)',
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
                                    borderRadius: '50%',
                                    bgcolor: 'error.main',
                                    animation: 'pulse 1s infinite',
                                    '@keyframes pulse': {
                                        '0%, 100%': { opacity: 1 },
                                        '50%': { opacity: 0.3 },
                                    },
                                }}
                            />
                            <Typography variant="body2" sx={{ color: '#fff', fontFamily: 'monospace' }}>
                                {formatTime(recordingTime)}
                            </Typography>
                        </Box>

                        <Typography
                            variant="body2"
                            sx={{
                                position: 'absolute',
                                bottom: 12,
                                right: 12,
                                color: '#fff',
                                bgcolor: 'rgba(0,0,0,0.6)',
                                px: 1.5,
                                py: 0.5,
                                borderRadius: 1,
                                zIndex: 1,
                            }}
                        >
                            Recording...
                        </Typography>
                    </Box>

                    {/* Duration progress bar */}
                    <LinearProgress
                        variant="determinate"
                        value={recordingProgress}
                        color="error"
                        sx={{ mb: 2, height: 6, borderRadius: 3 }}
                    />

                    {/* Stop Button */}
                    <Box sx={{ display: 'flex', justifyContent: 'center' }}>
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
                </Box>
            )}

            {/* ===== State: Preview (recorded video) ===== */}
            {!isRecording && recordedBlob && previewUrl && (
                <Box>
                    {/* Playback */}
                    <Box
                        sx={{
                            borderRadius: 2,
                            overflow: 'hidden',
                            bgcolor: '#000',
                            mb: 2,
                        }}
                    >
                        <video
                            src={previewUrl}
                            controls
                            playsInline
                            style={{ width: '100%', display: 'block', maxHeight: 400 }}
                        />
                    </Box>

                    {/* Action Buttons */}
                    <Box sx={{ display: 'flex', justifyContent: 'center', gap: 2 }}>
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
                            {isUploading ? 'Submitting...' : 'Submit Answer'}
                        </Button>
                    </Box>

                    {/* Upload Progress */}
                    {isUploading && (
                        <Box sx={{ mt: 2 }}>
                            <LinearProgress
                                variant={uploadProgress > 0 ? 'determinate' : 'indeterminate'}
                                value={uploadProgress}
                                sx={{ height: 6, borderRadius: 3 }}
                            />
                            <Typography
                                variant="caption"
                                color="text.secondary"
                                sx={{ display: 'block', textAlign: 'center', mt: 0.5 }}
                            >
                                {uploadProgress > 0 ? `Uploading... ${uploadProgress}%` : 'Uploading...'}
                            </Typography>
                        </Box>
                    )}
                </Box>
            )}
        </Paper>
    );
};

export default VideoRecorder;
