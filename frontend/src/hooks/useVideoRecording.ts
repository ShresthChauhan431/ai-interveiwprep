import { useCallback, useEffect, useRef, useState } from "react";
import { SUPPORTED_VIDEO_TYPES } from "../utils/constants";

// ============================================================
// useVideoRecording Hook
// ============================================================

interface UseVideoRecordingReturn {
  isRecording: boolean;
  recordedBlob: Blob | null;
  previewUrl: string | null;
  error: string | null;
  recordingTime: number;
  liveStream: MediaStream | null;
  startRecording: (maxDuration?: number) => Promise<void>;
  stopRecording: () => void;
}

/**
 * Determine the best supported MIME type for video recording.
 */
const getSupportedMimeType = (): string => {
  const types = SUPPORTED_VIDEO_TYPES;

  for (const type of types) {
    if (
      typeof MediaRecorder !== "undefined" &&
      MediaRecorder.isTypeSupported(type)
    ) {
      return type;
    }
  }

  return "video/webm";
};

export const useVideoRecording = (): UseVideoRecordingReturn => {
  const [isRecording, setIsRecording] = useState(false);
  const [recordedBlob, setRecordedBlob] = useState<Blob | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [recordingTime, setRecordingTime] = useState(0);
  const [liveStream, setLiveStream] = useState<MediaStream | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<NodeJS.Timeout | null>(null);
  const maxDurationRef = useRef<number | undefined>(undefined);

  // ============================================================
  // Recording Timer
  // ============================================================

  useEffect(() => {
    if (isRecording) {
      timerRef.current = setInterval(() => {
        setRecordingTime((prev) => {
          const next = prev + 1;

          // Auto-stop if maxDuration reached
          if (maxDurationRef.current && next >= maxDurationRef.current) {
            mediaRecorderRef.current?.stop();
          }

          return next;
        });
      }, 1000);
    } else {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    }

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
    };
  }, [isRecording]);

  // ============================================================
  // Stop Recording
  // ============================================================

  const stopRecording = useCallback(() => {
    // Stop MediaRecorder
    if (
      mediaRecorderRef.current &&
      mediaRecorderRef.current.state !== "inactive"
    ) {
      mediaRecorderRef.current.stop();
    }

    // Stop all media tracks
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }

    setLiveStream(null);
    setIsRecording(false);
  }, []);

  // ============================================================
  // Start Recording
  // ============================================================

  const startRecording = useCallback(
    async (maxDuration?: number) => {
      try {
        setError(null);
        setRecordedBlob(null);
        setRecordingTime(0);
        chunksRef.current = [];
        maxDurationRef.current = maxDuration;

        // Revoke previous preview URL
        if (previewUrl) {
          URL.revokeObjectURL(previewUrl);
          setPreviewUrl(null);
        }

        // Get user media (video + audio)
        const stream = await navigator.mediaDevices.getUserMedia({
          video: {
            width: { ideal: 1280 },
            height: { ideal: 720 },
            facingMode: "user",
          },
          audio: true,
        });
        streamRef.current = stream;
        setLiveStream(stream);

        // Choose best codec
        const mimeType = getSupportedMimeType();

        // Create MediaRecorder with 2.5 Mbps bitrate
        const recorder = new MediaRecorder(stream, {
          mimeType,
          videoBitsPerSecond: 2500000, // 2.5 Mbps
        });

        // Handle data chunks
        recorder.ondataavailable = (event: BlobEvent) => {
          if (event.data.size > 0) {
            chunksRef.current.push(event.data);
          }
        };

        // Handle recording stop
        recorder.onstop = () => {
          // Create blob from chunks
          const blob = new Blob(chunksRef.current, { type: mimeType });
          setRecordedBlob(blob);

          // Generate preview URL
          const url = URL.createObjectURL(blob);
          setPreviewUrl(url);

          setLiveStream(null);
          setIsRecording(false);
        };

        recorder.onerror = () => {
          setError("An error occurred during recording.");
          setIsRecording(false);
        };

        // Start recording with timeslice of 1000ms
        recorder.start(1000);
        mediaRecorderRef.current = recorder;
        setIsRecording(true);
      } catch (err: any) {
        if (err.name === "NotAllowedError") {
          setError("Camera and microphone access was denied.");
        } else if (err.name === "NotFoundError") {
          setError("No camera or microphone found.");
        } else if (err.name === "NotReadableError") {
          setError("Camera or microphone is already in use.");
        } else {
          setError(err.message || "Failed to start recording.");
        }
        setIsRecording(false);
      }
    },
    [previewUrl],
  );

  // ============================================================
  // Cleanup on unmount
  // ============================================================

  useEffect(() => {
    return () => {
      // Stop recorder
      if (
        mediaRecorderRef.current &&
        mediaRecorderRef.current.state !== "inactive"
      ) {
        mediaRecorderRef.current.stop();
      }

      // Stop media tracks
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((track) => track.stop());
      }

      // Clear timer
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }

      // Revoke preview URL
      // Note: previewUrl state may be stale in cleanup, so we skip revoking here
      // The browser will GC the blob URL when the page unloads
    };
  }, []);

  return {
    isRecording,
    recordedBlob,
    previewUrl,
    error,
    recordingTime,
    liveStream,
    startRecording,
    stopRecording,
  };
};

export default useVideoRecording;
