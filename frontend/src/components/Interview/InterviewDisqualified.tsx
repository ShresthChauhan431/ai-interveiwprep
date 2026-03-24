import React from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Box,
  Container,
  Typography,
  Paper,
  Button,
  Divider,
} from '@mui/material';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import DashboardIcon from '@mui/icons-material/Dashboard';

// ============================================================
// InterviewDisqualified — Post-termination page (Issue 3)
// ============================================================
//
// Shown after the proctoring system terminates an interview due
// to exceeding the maximum allowed violations. Displays:
//   - Clear disqualification message
//   - Violation count and last violation reason
//   - Link back to Dashboard (no restart option)
//
// Part of the Proctoring / Surveillance System (Issue 3).
// ============================================================

interface LocationState {
  violationCount?: number;
  reason?: string;
  interviewId?: number;
}

const InterviewDisqualified: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  // FIX: Extract proctoring details from router state (passed by InterviewRoom on termination)
  const state = (location.state as LocationState) || {};
  const violationCount = state.violationCount ?? 3;
  const reason = state.reason ?? 'Multiple proctoring violations detected';
  const interviewId = state.interviewId;

  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Paper
        elevation={0}
        sx={{
          p: 5,
          borderRadius: 4,
          border: '2px solid',
          borderColor: 'error.main',
          textAlign: 'center',
        }}
      >
        {/* FIX: Large warning icon for visual emphasis on disqualification */}
        <WarningAmberIcon sx={{ fontSize: 80, color: 'error.main', mb: 2 }} />

        <Typography variant="h4" fontWeight={700} color="error" gutterBottom>
          Interview Disqualified
        </Typography>

        <Typography variant="body1" color="text.secondary" sx={{ mb: 3, maxWidth: 400, mx: 'auto' }}>
          Your interview session has been terminated by the proctoring system
          due to multiple violations of the monitoring policy.
        </Typography>

        <Divider sx={{ my: 3 }} />

        {/* FIX: Show violation details for transparency */}
        <Box sx={{ textAlign: 'left', mb: 3, px: 2 }}>
          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
            Violation Details
          </Typography>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Total Violations:
            </Typography>
            <Typography variant="body2" fontWeight={600} color="error">
              {violationCount} {/* FIX: Display total violation count */}
            </Typography>
          </Box>

          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Last Violation:
            </Typography>
            <Typography variant="body2" fontWeight={500} color="text.primary" sx={{ maxWidth: 250, textAlign: 'right' }}>
              {reason} {/* FIX: Display last violation reason for user awareness */}
            </Typography>
          </Box>

          {interviewId && (
            <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
              <Typography variant="body2" color="text.secondary">
                Interview ID:
              </Typography>
              <Typography variant="body2" fontWeight={500} color="text.primary">
                #{interviewId} {/* FIX: Show interview ID for reference */}
              </Typography>
            </Box>
          )}
        </Box>

        <Divider sx={{ my: 3 }} />

        {/* FIX: Informational note about proctoring policy */}
        <Typography variant="body2" color="text.secondary" sx={{ mb: 4, fontStyle: 'italic' }}>
          Proctoring monitors for tab switches, window focus loss, and face detection
          to ensure interview integrity. Please ensure you remain focused on the
          interview screen during future sessions.
        </Typography>

        {/* FIX: Only show Dashboard button — do NOT allow restarting the same interview */}
        <Button
          variant="contained"
          size="large"
          startIcon={<DashboardIcon />}
          onClick={() => navigate('/dashboard')} // FIX: Navigate to dashboard, not back to interview
          sx={{
            px: 4,
            py: 1.5,
            borderRadius: 2,
            fontWeight: 600,
          }}
        >
          Back to Dashboard
        </Button>
      </Paper>
    </Container>
  );
};

export default InterviewDisqualified;
