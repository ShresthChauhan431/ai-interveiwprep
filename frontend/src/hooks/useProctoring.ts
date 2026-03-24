import { useEffect, useRef, useState, useCallback } from 'react';

// ============================================================
// useProctoring Hook — Proctoring / Surveillance System (Issue 3)
// ============================================================
//
// Detects when a candidate looks away from the screen during an
// interview and terminates the session after a configurable number
// of violations (default: 3).
//
// Detection methods:
//   1. Page Visibility API — detects tab switches / window minimize
//   2. Window focus/blur — detects alt-tab, clicking outside browser
//   3. BlazeFace (TensorFlow.js) — detects face absence from camera
//
// All three methods share the SAME violation counter with a 10-second
// cooldown between violations so a single incident doesn't consume
// all warnings at once.
//
// Safety guardrails:
//   - If BlazeFace/TensorFlow fails to load, the interview continues
//     with fallback proctoring only (visibility + blur).
//   - All intervals, timeouts, and event listeners are cleaned up
//     in useEffect cleanup functions to prevent memory leaks.
//   - Proctoring is only active when `isActive` prop is true (i.e.,
//     after the first question is displayed and recording has started).
// ============================================================

interface ProctoringState {
  violationCount: number;
  isWarningVisible: boolean;
  isTerminated: boolean;
  lastViolationReason: string;
}

interface UseProctoringOptions {
  maxViolations?: number; // FIX: default 3 — configurable max violations before termination
  onTerminate: (reason: string) => void; // FIX: callback when interview should be terminated
  onViolation: (count: number, reason: string) => void; // FIX: callback on each violation for logging
  videoRef: React.RefObject<HTMLVideoElement | null>; // FIX: ref to candidate's camera feed for face detection
  isActive: boolean; // FIX: only activate proctoring when interview has started (not during setup/countdown)
}

const COOLDOWN_MS = 10000; // FIX: 10-second cooldown between violations so a single incident doesn't consume all warnings

export const useProctoring = ({
  maxViolations = 3,
  onTerminate,
  onViolation,
  videoRef,
  isActive,
}: UseProctoringOptions): ProctoringState => {
  const [violationCount, setViolationCount] = useState(0);
  const [isWarningVisible, setIsWarningVisible] = useState(false);
  const [isTerminated, setIsTerminated] = useState(false);
  const [lastViolationReason, setLastViolationReason] = useState('');

  // FIX: Use refs for mutable state that needs to be accessed in callbacks without re-renders
  const violationRef = useRef(0);
  const lastViolationTimeRef = useRef(0); // FIX: Track last violation time for cooldown enforcement
  const isTerminatedRef = useRef(false); // FIX: Mirror terminated state in ref for async callbacks
  const isActiveRef = useRef(false); // FIX: Mirror active state in ref for event handlers
  const detectionIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const warningTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // FIX: Keep refs in sync with props/state
  useEffect(() => {
    isActiveRef.current = isActive;
  }, [isActive]);

  useEffect(() => {
    isTerminatedRef.current = isTerminated;
  }, [isTerminated]);

  // FIX: Stable refs for callbacks to avoid stale closures in event handlers
  const onTerminateRef = useRef(onTerminate);
  useEffect(() => {
    onTerminateRef.current = onTerminate;
  }, [onTerminate]);

  const onViolationRef = useRef(onViolation);
  useEffect(() => {
    onViolationRef.current = onViolation;
  }, [onViolation]);

  const triggerViolation = useCallback((reason: string) => {
    // FIX: Don't trigger violations when proctoring is inactive or already terminated
    if (!isActiveRef.current || isTerminatedRef.current) return;

    // FIX: Enforce 10-second cooldown between violations so a single look-away doesn't consume all warnings
    const now = Date.now();
    if (now - lastViolationTimeRef.current < COOLDOWN_MS) {
      return; // FIX: Within cooldown period — ignore this detection
    }
    lastViolationTimeRef.current = now;

    violationRef.current += 1; // FIX: Increment shared violation counter (same counter for all detection methods)
    const newCount = violationRef.current;

    setViolationCount(newCount);
    setLastViolationReason(reason);
    setIsWarningVisible(true);
    onViolationRef.current(newCount, reason);

    // FIX: Auto-hide warning after 5 seconds
    if (warningTimeoutRef.current) clearTimeout(warningTimeoutRef.current);
    warningTimeoutRef.current = setTimeout(() => setIsWarningVisible(false), 5000);

    if (newCount >= maxViolations) {
      // FIX: Max violations reached — terminate the interview
      setIsTerminated(true);
      isTerminatedRef.current = true;
      onTerminateRef.current(`Interview terminated after ${newCount} violations. Last: ${reason}`);
    }
  }, [maxViolations]);

  // ============================================================
  // Detection Method 1: Page Visibility API (tab switch / minimize)
  // ============================================================

  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.hidden && isActiveRef.current && !isTerminatedRef.current) {
        triggerViolation('Tab switched or window minimized'); // FIX: Detect tab switch via Page Visibility API
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange); // FIX: Register visibility change listener
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange); // FIX: Clean up listener on unmount
  }, [triggerViolation]);

  // ============================================================
  // Detection Method 2: Window focus/blur (alt-tab, clicking outside)
  // ============================================================

  useEffect(() => {
    let blurTimer: ReturnType<typeof setTimeout> | null = null;

    const handleBlur = () => {
      // FIX: Grace period of 2 seconds before counting as violation (avoids false positives from brief focus loss)
      blurTimer = setTimeout(() => {
        if (isActiveRef.current && !isTerminatedRef.current) {
          triggerViolation('Window lost focus — candidate looked away'); // FIX: Detect window blur via focus/blur events
        }
      }, 2000);
    };

    const handleFocus = () => {
      // FIX: Cancel pending blur violation if user returns within grace period
      if (blurTimer) {
        clearTimeout(blurTimer);
        blurTimer = null;
      }
    };

    window.addEventListener('blur', handleBlur); // FIX: Register blur listener
    window.addEventListener('focus', handleFocus); // FIX: Register focus listener
    return () => {
      window.removeEventListener('blur', handleBlur); // FIX: Clean up blur listener on unmount
      window.removeEventListener('focus', handleFocus); // FIX: Clean up focus listener on unmount
      if (blurTimer) clearTimeout(blurTimer); // FIX: Clean up pending timer on unmount
    };
  }, [triggerViolation]);

  // ============================================================
  // Detection Method 3: BlazeFace — detect face absence from camera
  // ============================================================

  useEffect(() => {
    // FIX: Only attempt face detection when proctoring is active and not yet terminated
    if (!isActive || isTerminated) return;
    if (!videoRef.current) return;

    let model: any = null;
    let active = true; // FIX: Track whether this effect instance is still active for async cleanup

    const loadAndDetect = async () => {
      try {
        // FIX: Dynamic import to avoid blocking initial page load if packages aren't installed
        const blazeface = await import('@tensorflow-models/blazeface');
        await import('@tensorflow/tfjs');
        model = await blazeface.load();

        let noFaceFrames = 0;
        const FRAMES_BEFORE_VIOLATION = 15; // FIX: ~3 seconds at 200ms check interval (15 * 200ms = 3s)

        detectionIntervalRef.current = setInterval(async () => {
          // FIX: Skip detection if hook has been deactivated, terminated, or video isn't ready
          if (!active || isTerminatedRef.current || !isActiveRef.current) return;
          if (!videoRef.current || videoRef.current.readyState < 2) return; // FIX: Video not ready — skip frame

          try {
            const predictions = await model.estimateFaces(videoRef.current, false); // FIX: Run face detection on current camera frame
            if (predictions.length === 0) {
              noFaceFrames++; // FIX: Increment consecutive no-face counter
              if (noFaceFrames >= FRAMES_BEFORE_VIOLATION) {
                noFaceFrames = 0; // FIX: Reset counter after triggering violation
                triggerViolation('Face not detected in camera — candidate may have looked away'); // FIX: Trigger violation after sustained face absence
              }
            } else {
              noFaceFrames = 0; // FIX: Reset counter when face is detected
            }
          } catch (_e) {
            // FIX: Silently ignore detection errors — don't crash the interview because of a proctoring error
          }
        }, 200); // FIX: Check every 200ms for responsive face detection
      } catch (_e) {
        // FIX: BlazeFace/TensorFlow failed to load — interview continues with fallback proctoring only (visibility + blur)
        console.warn('Face detection unavailable, using fallback proctoring only (visibility API + window blur)');
      }
    };

    loadAndDetect();

    return () => {
      active = false; // FIX: Signal async operations to stop
      if (detectionIntervalRef.current) {
        clearInterval(detectionIntervalRef.current); // FIX: Clean up detection interval on unmount
        detectionIntervalRef.current = null;
      }
    };
  }, [videoRef, triggerViolation, isActive, isTerminated]);

  // ============================================================
  // Cleanup warning timeout on unmount
  // ============================================================

  useEffect(() => {
    return () => {
      if (warningTimeoutRef.current) {
        clearTimeout(warningTimeoutRef.current); // FIX: Clean up warning timeout on unmount to prevent memory leaks
      }
    };
  }, []);

  return { violationCount, isWarningVisible, isTerminated, lastViolationReason };
};
