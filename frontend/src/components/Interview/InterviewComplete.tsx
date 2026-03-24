import React, { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Container,
  Divider,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Typography,
} from "@mui/material";
import {
  CheckCircle,
  ExpandMore,
  Home,
  Lightbulb,
  PlayArrow,
  Replay,
  Star,
  TrendingDown,
  TrendingUp,
  Visibility,
} from "@mui/icons-material";
import { Feedback, asFeedbackList } from "../../types";
import { interviewService } from "../../services/interview.service";

// ============================================================
// Props
// ============================================================

interface InterviewCompleteProps {
  interviewId: number;
}

// ============================================================
// Score color helper
// ============================================================

const getScoreColor = (score: number): string => {
  if (score >= 80) return "#4caf50"; // green
  if (score >= 60) return "#ff9800"; // orange
  return "#f44336"; // red
};

const getScoreLabel = (score: number): string => {
  if (score >= 80) return "Excellent";
  if (score >= 60) return "Good";
  if (score >= 40) return "Needs Improvement";
  return "Poor";
};

// ============================================================
// InterviewComplete Component
// ============================================================

const InterviewComplete: React.FC<InterviewCompleteProps> = ({
  interviewId,
}) => {
  const navigate = useNavigate();

  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);

  const pollIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // ============================================================
  // Fetch / Poll for feedback
  // ============================================================

  const fetchFeedback = useCallback(async () => {
    try {
      const result = await interviewService.getFeedback(interviewId);

      // FIX: Check for FAILED status in response and stop polling with error message
      if ((result as any).status === "FAILED") {
        setIsLoading(false);
        setIsProcessing(false);
        setError("Analysis failed. Please contact support.");
        if (pollIntervalRef.current) {
          clearInterval(pollIntervalRef.current);
          pollIntervalRef.current = null;
        }
        return;
      }

      setFeedback(result);
      setIsLoading(false);
      setIsProcessing(false);

      // Stop polling once feedback is received
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    } catch (err: any) {
      if (err.message?.includes("still being generated")) {
        // Feedback not ready yet — keep polling
        setIsProcessing(true);
        setIsLoading(false);
      } else if (err?.status === 404 || err.message?.includes("not found")) {
        // FIX: 404 means feedback hasn't been created yet — keep polling silently
        setIsProcessing(true);
        setIsLoading(false);
      } else {
        // FIX: For other errors, keep polling instead of showing error immediately
        // (transient network blips shouldn't stop the polling loop)
        console.warn("Feedback poll error (will retry):", err.message);
        setIsProcessing(true);
        setIsLoading(false);
      }
    }
  }, [interviewId]);

  useEffect(() => {
    // Initial fetch
    fetchFeedback();

    // FIX: Poll every 3 seconds instead of 5 for faster feedback delivery
    pollIntervalRef.current = setInterval(fetchFeedback, 3000);

    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, [fetchFeedback]);

  // ============================================================
  // Render: Loading
  // ============================================================

  if (isLoading) {
    return (
      <Container maxWidth="sm" sx={{ py: 8, textAlign: "center" }}>
        <CircularProgress size={56} sx={{ mb: 3 }} />
        {/* FIX: Show "Analysing" message during initial load to set expectations */}
        <Typography variant="h6">Analysing your responses...</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          This usually takes 1–2 minutes.
        </Typography>
      </Container>
    );
  }

  // ============================================================
  // Render: Processing (feedback not ready yet)
  // ============================================================

  if (isProcessing && !feedback) {
    return (
      <Container maxWidth="sm" sx={{ py: 8, textAlign: "center" }}>
        <CheckCircle sx={{ fontSize: 64, color: "success.main", mb: 2 }} />
        <Typography variant="h5" fontWeight={700} gutterBottom>
          Interview Complete!
        </Typography>
        <Typography color="text.secondary" sx={{ mb: 4 }}>
          Great job! Our AI is now analyzing your responses...
        </Typography>

        <Card
          elevation={0}
          sx={{
            borderRadius: 3,
            border: "1px solid",
            borderColor: "divider",
            p: 3,
          }}
        >
          <CircularProgress size={32} sx={{ mb: 2 }} />
          <Typography variant="body1" fontWeight={500} gutterBottom>
            Analyzing your responses...
          </Typography>
          <Typography variant="body2" color="text.secondary">
            This usually takes 1–2 minutes. We'll show your results as soon as
            they're ready.
          </Typography>
        </Card>

        <Box sx={{ mt: 4 }}>
          <Button
            variant="outlined"
            startIcon={<Home />}
            onClick={() => navigate("/dashboard")}
          >
            Go to Dashboard
          </Button>
        </Box>
      </Container>
    );
  }

  // ============================================================
  // Render: Error
  // ============================================================

  if (error) {
    return (
      <Container maxWidth="sm" sx={{ py: 8 }}>
        <Alert severity="error" sx={{ mb: 3 }}>
          {error}
        </Alert>
        <Button
          variant="outlined"
          startIcon={<Replay />}
          onClick={fetchFeedback}
        >
          Retry
        </Button>
      </Container>
    );
  }

  // ============================================================
  // Render: Feedback ready
  // ============================================================

  if (!feedback) return null;

  const scoreColor = getScoreColor(feedback.overallScore);

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      {/* Success Header */}
      <Box sx={{ textAlign: "center", mb: 4 }}>
        <CheckCircle sx={{ fontSize: 56, color: "success.main", mb: 1 }} />
        <Typography variant="h4" fontWeight={700} gutterBottom>
          Interview Complete!
        </Typography>
        <Typography color="text.secondary">
          Here's a summary of your performance.
        </Typography>
      </Box>

      {/* ===== Score Card ===== */}
      <Card
        elevation={0}
        sx={{
          mb: 3,
          borderRadius: 3,
          border: "1px solid",
          borderColor: "divider",
        }}
      >
        <CardContent sx={{ p: 4, textAlign: "center" }}>
          <Box sx={{ position: "relative", display: "inline-flex", mb: 2 }}>
            <CircularProgress
              variant="determinate"
              value={feedback.overallScore}
              size={120}
              thickness={5}
              sx={{ color: scoreColor }}
            />
            <Box
              sx={{
                position: "absolute",
                top: 0,
                left: 0,
                bottom: 0,
                right: 0,
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <Typography
                variant="h3"
                fontWeight={700}
                sx={{ color: scoreColor }}
              >
                {feedback.overallScore}
              </Typography>
            </Box>
          </Box>
          <Typography variant="h6" fontWeight={600} sx={{ color: scoreColor }}>
            {getScoreLabel(feedback.overallScore)}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Overall Score
          </Typography>
        </CardContent>
      </Card>

      {/* ===== Strengths ===== */}
      <Accordion
        defaultExpanded
        elevation={0}
        sx={{
          mb: 2,
          borderRadius: "12px !important",
          border: "1px solid",
          borderColor: "divider",
          "&:before": { display: "none" },
        }}
      >
        <AccordionSummary expandIcon={<ExpandMore />}>
          <TrendingUp color="success" sx={{ mr: 1.5 }} />
          <Typography fontWeight={600}>
            Strengths ({asFeedbackList(feedback.strengths).length})
          </Typography>
        </AccordionSummary>
        <AccordionDetails>
          <List dense disablePadding>
            {asFeedbackList(feedback.strengths).map((item, index) => (
              <ListItem key={index} disablePadding sx={{ py: 0.5 }}>
                <ListItemIcon sx={{ minWidth: 32 }}>
                  <Star sx={{ fontSize: 18, color: "success.main" }} />
                </ListItemIcon>
                <ListItemText primary={item} />
              </ListItem>
            ))}
          </List>
        </AccordionDetails>
      </Accordion>

      {/* ===== Weaknesses ===== */}
      <Accordion
        defaultExpanded
        elevation={0}
        sx={{
          mb: 2,
          borderRadius: "12px !important",
          border: "1px solid",
          borderColor: "divider",
          "&:before": { display: "none" },
        }}
      >
        <AccordionSummary expandIcon={<ExpandMore />}>
          <TrendingDown color="error" sx={{ mr: 1.5 }} />
          <Typography fontWeight={600}>
            Areas for Improvement ({asFeedbackList(feedback.weaknesses).length})
          </Typography>
        </AccordionSummary>
        <AccordionDetails>
          <List dense disablePadding>
            {asFeedbackList(feedback.weaknesses).map((item, index) => (
              <ListItem key={index} disablePadding sx={{ py: 0.5 }}>
                <ListItemIcon sx={{ minWidth: 32 }}>
                  <TrendingDown sx={{ fontSize: 18, color: "error.main" }} />
                </ListItemIcon>
                <ListItemText primary={item} />
              </ListItem>
            ))}
          </List>
        </AccordionDetails>
      </Accordion>

      {/* ===== Recommendations ===== */}
      <Accordion
        defaultExpanded
        elevation={0}
        sx={{
          mb: 3,
          borderRadius: "12px !important",
          border: "1px solid",
          borderColor: "divider",
          "&:before": { display: "none" },
        }}
      >
        <AccordionSummary expandIcon={<ExpandMore />}>
          <Lightbulb color="warning" sx={{ mr: 1.5 }} />
          <Typography fontWeight={600}>
            Recommendations ({asFeedbackList(feedback.recommendations).length})
          </Typography>
        </AccordionSummary>
        <AccordionDetails>
          <List dense disablePadding>
            {asFeedbackList(feedback.recommendations).map((item, index) => (
              <ListItem key={index} disablePadding sx={{ py: 0.5 }}>
                <ListItemIcon sx={{ minWidth: 32 }}>
                  <Lightbulb sx={{ fontSize: 18, color: "warning.main" }} />
                </ListItemIcon>
                <ListItemText primary={item} />
              </ListItem>
            ))}
          </List>
        </AccordionDetails>
      </Accordion>

      {/* ===== Detailed Analysis (if available) ===== */}
      {feedback.detailedAnalysis && (
        <Card
          elevation={0}
          sx={{
            mb: 3,
            borderRadius: 3,
            border: "1px solid",
            borderColor: "divider",
          }}
        >
          <CardContent sx={{ p: 3 }}>
            <Typography variant="subtitle1" fontWeight={600} gutterBottom>
              Detailed Analysis
            </Typography>
            <Divider sx={{ mb: 2 }} />
            <Typography
              variant="body2"
              sx={{ whiteSpace: "pre-line", lineHeight: 1.7 }}
            >
              {feedback.detailedAnalysis}
            </Typography>
          </CardContent>
        </Card>
      )}

      {/* ===== Action Buttons ===== */}
      <Box
        sx={{
          display: "flex",
          gap: 2,
          justifyContent: "center",
          flexWrap: "wrap",
        }}
      >
        <Button
          variant="outlined"
          startIcon={<Visibility />}
          onClick={() => navigate(`/interview/${interviewId}/feedback`)}
        >
          View Detailed Feedback
        </Button>
        <Button
          variant="contained"
          startIcon={<PlayArrow />}
          onClick={() => navigate("/interview/start")}
        >
          Start Another Interview
        </Button>
        <Button
          variant="outlined"
          startIcon={<Home />}
          onClick={() => navigate("/dashboard")}
        >
          Go to Dashboard
        </Button>
        <Button variant="outlined" onClick={() => navigate("/history")}>
          View Interview History
        </Button>
      </Box>
    </Container>
  );
};

export default InterviewComplete;
