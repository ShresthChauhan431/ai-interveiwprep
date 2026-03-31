import { useCallback, useEffect, useRef, useState } from "react";
import { SUPPORTED_VIDEO_TYPES } from "../utils/constants";

// ============================================================
// useSessionRecording Hook
// ============================================================
//
// This hook provides continuous video recording for the entire
// interview session. Unlike useVideoRecording which records
// individual answers, this records from interview start to finish,
// allowing users to review their complete interview experience.
//
// The session recording runs in the background while individual
// question recordings happen, providing a complete record of
// the interview for self-review purposes.
//
// ============================================================

interface UseSessionRecordingOptions {
  /** Auto-start recording when hook mounts */
  autoStart?: boolean;
  /** Maximum recording duration in seconds (default: 3600 = 1 hour) */
  maxDuration?: number;
  /** Callback when recording stops */
  onRecordingComplete?: (blob: Blob) => void;
}

interface UseSessionRecordingReturn {
  /** Whether the session is currently being recorded */
  isRecording: boolean;
  /** The recorded video blob (available after stopping) */
  recordedBlob: Blob | null;
  /** Preview URL for the recorded video */
  previewUrl: string | null;
  /** Recording duration in seconds */
  recordingTime: number;
  /** Error message if recording fails */
  error: string | null;
  /** Start session recording */
  startRecording: () => Promise<void>;
  /** Stop session recording */
  stopRecording: () => void;
  /** Get the current recording as a blob without stopping */
  getPartialRecording: () => Promise<Blob | null>;
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

export const useSessionRecording = (
  options: UseSessionRecordingOptions = {}
): UseSessionRecordingReturn => {
  const { autoStart = false, maxDuration = 3600, onRecordingComplete } = options;

  // ── State ───────────────────────────────────────────────────
  const [isRecording, setIsRecording] = useState(false);
  const [recordedBlob, setRecordedBlob] = useState<Blob | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [recordingTime, setRecordingTime] = useState(0);
  const [error, setError] = useState<string | null>(null);

  // ── Refs ────────────────────────────────────────────────────
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<NodeJS.Timeout | null>(null);

  // ── Timer Effect ────────────────────────────────────────────
  useEffect(() => {
    if (isRecording) {
      timerRef.current = setInterval(() => {
        setRecordingTime((prev) => {
          const next = prev + 1;

          // Auto-stop if maxDuration reached
          if (maxDuration && next >= maxDuration) {
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
  }, [isRecording, maxDuration]);

  // ── Start Recording ─────────────────────────────────────────
  const startRecording = useCallback(async () => {
    try {
      setError(null);
      setRecordedBlob(null);
      setRecordingTime(0);
      chunksRef.current = [];

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

      // Choose best codec
      const mimeType = getSupportedMimeType();

      // Create MediaRecorder with moderate bitrate for long recordings
      const recorder = new MediaRecorder(stream, {
        mimeType,
        videoBitsPerSecond: 1500000, // 1.5 Mbps for smaller file size
      });

      // Collect chunks periodically (every 10 seconds)
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

        setIsRecording(false);

        // Callback
        if (onRecordingComplete) {
          onRecordingComplete(blob);
        }
      };

      recorder.onerror = () => {
        setError("An error occurred during session recording.");
        setIsRecording(false);
      };

      // Start recording with periodic data availability (every 10 seconds)
      // This allows getting partial recordings without stopping
      recorder.start(10000);
      mediaRecorderRef.current = recorder;
      setIsRecording(true);

      console.log("[SessionRecording] Started full interview session recording");
    } catch (err: unknown) {
      if (err instanceof Error) {
        if (err.name === "NotAllowedError") {
          setError("Camera and microphone access was denied.");
        } else if (err.name === "NotFoundError") {
          setError("No camera or microphone found.");
        } else if (err.name === "NotReadableError") {
          setError("Camera or microphone is already in use.");
        } else {
          setError(err.message || "Failed to start session recording.");
        }
      } else {
        setError("Failed to start session recording.");
      }
      setIsRecording(false);
    }
  }, [previewUrl, onRecordingComplete]);

  // ── Stop Recording ──────────────────────────────────────────
  const stopRecording = useCallback(() => {
    // Stop MediaRecorder
    if (
      mediaRecorderRef.current &&
      mediaRecorderRef.current.state !== "inactive"
    ) {
      mediaRecorderRef.current.stop();
      console.log("[SessionRecording] Stopped full interview session recording");
    }

    // Stop all media tracks
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }

    setIsRecording(false);
  }, []);

  // ── Get Partial Recording ───────────────────────────────────
  const getPartialRecording = useCallback(async (): Promise<Blob | null> => {
    if (chunksRef.current.length === 0) {
      return null;
    }

    const mimeType = getSupportedMimeType();
    return new Blob(chunksRef.current, { type: mimeType });
  }, []);

  // ── Auto-start Effect ───────────────────────────────────────
  useEffect(() => {
    if (autoStart) {
      startRecording();
    }

    return () => {
      // Cleanup on unmount
      if (
        mediaRecorderRef.current &&
        mediaRecorderRef.current.state !== "inactive"
      ) {
        mediaRecorderRef.current.stop();
      }

      if (streamRef.current) {
        streamRef.current.getTracks().forEach((track) => track.stop());
      }

      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
    };
    // Only run on mount/unmount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    isRecording,
    recordedBlob,
    previewUrl,
    recordingTime,
    error,
    startRecording,
    stopRecording,
    getPartialRecording,
  };
};

export default useSessionRecording;
