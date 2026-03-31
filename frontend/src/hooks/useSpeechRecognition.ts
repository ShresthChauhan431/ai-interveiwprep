import { useCallback, useEffect, useRef, useState } from "react";

// ============================================================
// Web Speech API — Types are declared in
// src/types/speech-recognition.d.ts
// ============================================================

// ============================================================
// useSpeechRecognition Hook
// ============================================================
//
// This hook provides real-time speech-to-text transcription using
// the browser's Web Speech API. It serves as a fallback when
// AssemblyAI cannot access local video files (e.g., in development
// mode with localhost URLs).
//
// The transcription is captured in real-time during recording and
// can be submitted alongside the video blob to ensure transcriptions
// are available even when cloud transcription is unavailable.
//
// ============================================================

interface UseSpeechRecognitionOptions {
  /** Language for speech recognition (default: en-US) */
  language?: string;
  /** Callback when final transcript is available */
  onTranscript?: (transcript: string) => void;
  /** Callback when interim transcript updates */
  onInterimTranscript?: (interim: string) => void;
}

interface UseSpeechRecognitionReturn {
  /** Whether the browser supports Web Speech API */
  isSupported: boolean;
  /** Whether speech recognition is currently active */
  isListening: boolean;
  /** The accumulated final transcript */
  transcript: string;
  /** The current interim transcript (in-progress speech) */
  interimTranscript: string;
  /** Error message if speech recognition fails */
  error: string | null;
  /** Start listening for speech */
  startListening: () => void;
  /** Stop listening and finalize transcript */
  stopListening: () => void;
  /** Reset the transcript to empty */
  resetTranscript: () => void;
}

export const useSpeechRecognition = (
  options: UseSpeechRecognitionOptions = {}
): UseSpeechRecognitionReturn => {
  const { language = "en-US", onTranscript, onInterimTranscript } = options;

  // ── State ───────────────────────────────────────────────────
  const [isSupported, setIsSupported] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [interimTranscript, setInterimTranscript] = useState("");
  const [error, setError] = useState<string | null>(null);

  // ── Refs ────────────────────────────────────────────────────
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const transcriptRef = useRef("");

  // ── Initialize Speech Recognition ───────────────────────────
  useEffect(() => {
    const SpeechRecognition =
      window.SpeechRecognition || window.webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setIsSupported(false);
      return;
    }

    setIsSupported(true);

    const recognition = new SpeechRecognition();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = language;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      let finalTranscript = "";
      let interim = "";

      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        const text = result[0].transcript;

        if (result.isFinal) {
          finalTranscript += text + " ";
        } else {
          interim += text;
        }
      }

      // Update interim transcript
      setInterimTranscript(interim);
      if (onInterimTranscript) {
        onInterimTranscript(interim);
      }

      // Append final transcript
      if (finalTranscript) {
        transcriptRef.current += finalTranscript;
        setTranscript(transcriptRef.current);
        if (onTranscript) {
          onTranscript(transcriptRef.current);
        }
      }
    };

    recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
      console.error("Speech recognition error:", event.error);

      // Handle specific error types
      switch (event.error) {
        case "no-speech":
          // This is not a critical error - user just hasn't spoken yet
          // Don't set error state for this
          break;
        case "audio-capture":
          setError("No microphone detected. Please check your audio settings.");
          break;
        case "not-allowed":
          setError(
            "Microphone access denied. Please allow microphone permissions."
          );
          break;
        case "network":
          setError(
            "Network error during speech recognition. Check your connection."
          );
          break;
        case "aborted":
          // Recognition was aborted intentionally, not an error
          break;
        default:
          setError(`Speech recognition error: ${event.error}`);
      }
    };

    recognition.onend = () => {
      setIsListening(false);
      // Clear interim when recognition ends
      setInterimTranscript("");
    };

    recognitionRef.current = recognition;

    // Cleanup
    return () => {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.abort();
        } catch {
          // Ignore abort errors during cleanup
        }
      }
    };
  }, [language, onTranscript, onInterimTranscript]);

  // ── Start Listening ─────────────────────────────────────────
  const startListening = useCallback(() => {
    if (!recognitionRef.current) {
      setError("Speech recognition not supported in this browser.");
      return;
    }

    setError(null);
    setInterimTranscript("");

    try {
      recognitionRef.current.start();
      setIsListening(true);
    } catch (err: unknown) {
      // Handle "already started" error gracefully
      if (err instanceof Error && err.message.includes("already started")) {
        setIsListening(true);
      } else {
        console.error("Failed to start speech recognition:", err);
        setError("Failed to start speech recognition.");
      }
    }
  }, []);

  // ── Stop Listening ──────────────────────────────────────────
  const stopListening = useCallback(() => {
    if (!recognitionRef.current) return;

    try {
      recognitionRef.current.stop();
    } catch {
      // Ignore stop errors
    }

    setIsListening(false);
    setInterimTranscript("");

    // Final callback with complete transcript
    if (onTranscript && transcriptRef.current.trim()) {
      onTranscript(transcriptRef.current.trim());
    }
  }, [onTranscript]);

  // ── Reset Transcript ────────────────────────────────────────
  const resetTranscript = useCallback(() => {
    transcriptRef.current = "";
    setTranscript("");
    setInterimTranscript("");
    setError(null);
  }, []);

  return {
    isSupported,
    isListening,
    transcript,
    interimTranscript,
    error,
    startListening,
    stopListening,
    resetTranscript,
  };
};

export default useSpeechRecognition;
