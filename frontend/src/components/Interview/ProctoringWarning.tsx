import React from 'react';
import { Alert, Box, Typography, LinearProgress } from '@mui/material';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';

// ============================================================
// ProctoringWarning — Overlay component for proctoring violations
// ============================================================
//
// Displays two modes:
//   1. Warning banner (top of screen) when a violation is detected
//   2. Full-screen termination overlay when max violations are reached
//
// Part of the Proctoring / Surveillance System (Issue 3).
// ============================================================

interface Props {
  violationCount: number;
  maxViolations: number;
  reason: string;
  isVisible: boolean;
  isTerminated: boolean;
}

export const ProctoringWarning: React.FC<Props> = ({
  violationCount, maxViolations, reason, isVisible, isTerminated
}) => {
  // FIX: Don't render anything if no warning is active and interview isn't terminated
  if (!isVisible && !isTerminated) return null;

  // FIX: Full-screen termination overlay — interview is over due to proctoring violations
  if (isTerminated) {
    return (
      <Box sx={{
        position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
        backgroundColor: 'rgba(0,0,0,0.85)', zIndex: 9999,
        display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', gap: 3
      }}>
        <WarningAmberIcon sx={{ fontSize: 80, color: 'error.main' }} /> {/* FIX: Large warning icon for visual emphasis */}
        <Typography variant="h4" color="error" fontWeight="bold">
          Interview Terminated
        </Typography>
        <Typography variant="h6" color="white" textAlign="center" maxWidth={500}>
          You have been disqualified due to {maxViolations} proctoring violations.
        </Typography>
        <Typography variant="body2" color="grey.400" textAlign="center">
          Last violation: {reason} {/* FIX: Show the last violation reason for transparency */}
        </Typography>
      </Box>
    );
  }

  // FIX: Warning banner — shown temporarily (5 seconds) after each violation
  return (
    <Box sx={{
      position: 'fixed', top: 16, left: '50%', transform: 'translateX(-50%)',
      zIndex: 9000, minWidth: 400, maxWidth: 600
    }}>
      <Alert severity="warning" icon={<WarningAmberIcon />}
        sx={{ boxShadow: 4, border: '2px solid orange' }}> {/* FIX: High-contrast warning border for visibility */}
        <Typography fontWeight="bold">
          ⚠️ Proctoring Violation {violationCount} of {maxViolations} {/* FIX: Show current count and max for clarity */}
        </Typography>
        <Typography variant="body2">{reason}</Typography>
        <Typography variant="body2" color="error" mt={0.5}>
          {maxViolations - violationCount} warning(s) remaining before disqualification. {/* FIX: Show remaining warnings */}
        </Typography>
        <LinearProgress
          variant="determinate"
          value={(violationCount / maxViolations) * 100} // FIX: Visual progress bar showing how close to disqualification
          color="error" sx={{ mt: 1, height: 6, borderRadius: 3 }}
        />
      </Alert>
    </Box>
  );
};

export default ProctoringWarning;
