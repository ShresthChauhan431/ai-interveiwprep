import React, { useState, useEffect, useCallback } from "react";
import {
  Box,
  Card,
  CardContent,
  Typography,
  Button,
  CircularProgress,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Chip,
  Alert,
} from "@mui/material";
import {
  ExpandMore,
  CheckCircle,
  Warning,
  Lightbulb,
  Star,
  TrendingUp,
  TrendingDown,
  Description,
} from "@mui/icons-material";
import { ResumeAnalysis } from "../../types";
import { videoService } from "../../services/video.service";

const getScoreColor = (score: number): string => {
  if (score >= 80) return "#4caf50";
  if (score >= 60) return "#ff9800";
  return "#f44336";
};

const getScoreLabel = (score: number): string => {
  if (score >= 90) return "Exceptional";
  if (score >= 70) return "Good";
  if (score >= 50) return "Needs Work";
  return "Requires Revision";
};

const ResumeAnalyzer: React.FC = () => {
  const [analysis, setAnalysis] = useState<ResumeAnalysis | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasResume, setHasResume] = useState<boolean | null>(null);

  const checkResume = useCallback(async () => {
    try {
      await videoService.getMyResume();
      setHasResume(true);
    } catch {
      setHasResume(false);
    }
  }, []);

  useEffect(() => {
    checkResume();
  }, [checkResume]);

  const handleAnalyze = async () => {
    setIsLoading(true);
    setError(null);
    try {
      const result = await videoService.analyzeResume();
      setAnalysis(result);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || "Failed to analyze resume");
    } finally {
      setIsLoading(false);
    }
  };

  if (hasResume === null || isLoading) {
    return (
      <Card
        elevation={0}
        sx={{
          borderRadius: 3,
          border: "1px solid",
          borderColor: "divider",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: 280,
        }}
      >
        <CircularProgress />
      </Card>
    );
  }

  if (hasResume === false) {
    return (
      <Card
        elevation={0}
        sx={{
          borderRadius: 3,
          border: "1px solid",
          borderColor: "divider",
          height: "100%",
        }}
      >
        <CardContent sx={{ p: 3, textAlign: "center" }}>
          <Description sx={{ fontSize: 48, color: "text.secondary", mb: 2 }} />
          <Typography variant="h6" fontWeight={600} gutterBottom>
            Resume Analyzer
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 2 }}>
            Upload a resume first to get AI-powered feedback and suggestions.
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Go to "Start Interview" to upload your resume.
          </Typography>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card
      elevation={0}
      sx={{
        borderRadius: 3,
        border: "1px solid",
        borderColor: "divider",
        height: "100%",
      }}
    >
      <CardContent sx={{ p: 3 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, mb: 2 }}>
          <Description color="primary" sx={{ fontSize: 28 }} />
          <Typography variant="h6" fontWeight={600}>
            Resume Analyzer
          </Typography>
        </Box>

        {!analysis && !error && (
          <Box sx={{ textAlign: "center", py: 3 }}>
            <Typography color="text.secondary" sx={{ mb: 2 }}>
              Get AI-powered feedback on your resume to improve your chances.
            </Typography>
            <Button
              variant="contained"
              onClick={handleAnalyze}
              startIcon={<Lightbulb />}
            >
              Analyze My Resume
            </Button>
          </Box>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {analysis && (
          <Box>
            {/* Score Display */}
            <Box sx={{ textAlign: "center", mb: 3 }}>
              <Box sx={{ position: "relative", display: "inline-flex" }}>
                <CircularProgress
                  variant="determinate"
                  value={analysis.score}
                  size={100}
                  thickness={6}
                  sx={{ color: getScoreColor(analysis.score) }}
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
                  <Typography variant="h4" fontWeight={700} sx={{ color: getScoreColor(analysis.score) }}>
                    {analysis.score}
                  </Typography>
                </Box>
              </Box>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                {getScoreLabel(analysis.score)}
              </Typography>
            </Box>

            {/* Overall Feedback */}
            <Alert severity="info" sx={{ mb: 2 }}>
              {analysis.overallFeedback}
            </Alert>

            {/* Strengths */}
            {analysis.strengths.length > 0 && (
              <Accordion defaultExpanded elevation={0} sx={{ mb: 1, border: "1px solid", borderColor: "divider", borderRadius: "8px !important", "&:before": { display: "none" } }}>
                <AccordionSummary expandIcon={<ExpandMore />}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <TrendingUp color="success" fontSize="small" />
                    <Typography fontWeight={600}>Strengths</Typography>
                    <Chip label={analysis.strengths.length} size="small" color="success" />
                  </Box>
                </AccordionSummary>
                <AccordionDetails>
                  <List dense disablePadding>
                    {analysis.strengths.map((item, index) => (
                      <ListItem key={index} disablePadding sx={{ py: 0.5 }}>
                        <ListItemIcon sx={{ minWidth: 32 }}>
                          <CheckCircle sx={{ fontSize: 18, color: "success.main" }} />
                        </ListItemIcon>
                        <ListItemText primary={item} />
                      </ListItem>
                    ))}
                  </List>
                </AccordionDetails>
              </Accordion>
            )}

            {/* Weaknesses */}
            {analysis.weaknesses.length > 0 && (
              <Accordion elevation={0} sx={{ mb: 1, border: "1px solid", borderColor: "divider", borderRadius: "8px !important", "&:before": { display: "none" } }}>
                <AccordionSummary expandIcon={<ExpandMore />}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <TrendingDown color="error" fontSize="small" />
                    <Typography fontWeight={600}>Areas for Improvement</Typography>
                    <Chip label={analysis.weaknesses.length} size="small" color="error" />
                  </Box>
                </AccordionSummary>
                <AccordionDetails>
                  <List dense disablePadding>
                    {analysis.weaknesses.map((item, index) => (
                      <ListItem key={index} disablePadding sx={{ py: 0.5 }}>
                        <ListItemIcon sx={{ minWidth: 32 }}>
                          <Warning sx={{ fontSize: 18, color: "error.main" }} />
                        </ListItemIcon>
                        <ListItemText primary={item} />
                      </ListItem>
                    ))}
                  </List>
                </AccordionDetails>
              </Accordion>
            )}

            {/* Suggestions */}
            {analysis.suggestions.length > 0 && (
              <Accordion elevation={0} sx={{ mb: 1, border: "1px solid", borderColor: "divider", borderRadius: "8px !important", "&:before": { display: "none" } }}>
                <AccordionSummary expandIcon={<ExpandMore />}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <Lightbulb color="warning" fontSize="small" />
                    <Typography fontWeight={600}>Suggestions</Typography>
                    <Chip label={analysis.suggestions.length} size="small" color="warning" />
                  </Box>
                </AccordionSummary>
                <AccordionDetails>
                  <List dense disablePadding>
                    {analysis.suggestions.map((item, index) => (
                      <ListItem key={index} disablePadding sx={{ py: 0.5 }}>
                        <ListItemIcon sx={{ minWidth: 32 }}>
                          <Star sx={{ fontSize: 18, color: "warning.main" }} />
                        </ListItemIcon>
                        <ListItemText primary={item} />
                      </ListItem>
                    ))}
                  </List>
                </AccordionDetails>
              </Accordion>
            )}

            {/* Re-analyze button */}
            <Box sx={{ textAlign: "center", mt: 2 }}>
              <Button variant="outlined" size="small" onClick={handleAnalyze}>
                Re-analyze Resume
              </Button>
            </Box>
          </Box>
        )}
      </CardContent>
    </Card>
  );
};

export default ResumeAnalyzer;
