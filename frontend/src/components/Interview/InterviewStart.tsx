import React, { useCallback, useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Container,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Typography,
} from "@mui/material";
import {
  CloudUpload,
  Description,
  PlayArrow,
  CheckCircle,
  Videocam,
  LightbulbOutlined,
} from "@mui/icons-material";
import { JobRole, Resume } from "../../types";
import { interviewService } from "../../services/interview.service";
import { videoService } from "../../services/video.service";
import { useMediaPermissions } from "../../hooks/useMediaPermissions";
import api from "../../services/api.service";

// ============================================================
// Props
// ============================================================

interface InterviewStartProps {
  onStart: (interviewId: number) => void;
}

// ============================================================
// InterviewStart Component
// ============================================================

// Difficulty level options
type DifficultyLevel = "AUTO" | "EASY" | "MEDIUM" | "HARD";

const InterviewStart: React.FC<InterviewStartProps> = ({ onStart }) => {
  const [selectedJobRole, setSelectedJobRole] = useState<number | null>(null);
  const [numQuestions, setNumQuestions] = useState<number>(0); // 0 means "Default"
  const [difficulty, setDifficulty] = useState<DifficultyLevel>("AUTO"); // Difficulty selection
  const [resumeId, setResumeId] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [hasResume, setHasResume] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [jobRoles, setJobRoles] = useState<JobRole[]>([]);
  const [resume, setResume] = useState<Resume | null>(null);
  const [isLoadingData, setIsLoadingData] = useState(true);
  const [isUploading, setIsUploading] = useState(false);

  const { hasPermission, checkPermissions } = useMediaPermissions();

  // ============================================================
  // Fetch job roles and resume on mount
  // ============================================================

  useEffect(() => {
    const fetchData = async () => {
      setIsLoadingData(true);
      try {
        // Fetch job roles
        const rolesResponse = await api.get<JobRole[]>("/api/job-roles");
        setJobRoles(rolesResponse.data);

        // Check for existing resume
        try {
          const userResume = await videoService.getMyResume();
          setResume(userResume);
          setResumeId(userResume.id);
          setHasResume(true);
        } catch {
          // No resume uploaded yet — that's fine
          setHasResume(false);
        }
      } catch (err: any) {
        setError("Failed to load interview setup data.");
      } finally {
        setIsLoadingData(false);
      }
    };

    fetchData();
  }, []);

  // ============================================================
  // Resume upload
  // ============================================================

  const handleResumeUpload = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (!file) return;

      setIsUploading(true);
      setError(null);

      try {
        const uploaded = await videoService.uploadResume(file);
        setResume(uploaded);
        setResumeId(uploaded.id);
        setHasResume(true);
      } catch (err: any) {
        setError(err.message || "Failed to upload resume.");
      } finally {
        setIsUploading(false);
      }
    },
    [],
  );

  // ============================================================
  // Start interview
  // ============================================================

  const handleStart = useCallback(async () => {
    if (!resumeId || !selectedJobRole) return;

    // Check camera permissions first
    if (!hasPermission) {
      await checkPermissions();
      if (!hasPermission) {
        setError(
          "Camera and microphone access is required for video interviews.",
        );
        return;
      }
    }

    setIsLoading(true);
    setError(null);

    try {
      const result = await interviewService.startInterview(
        resumeId,
        selectedJobRole,
        numQuestions === 0 ? undefined : numQuestions,
        difficulty === "AUTO" ? undefined : difficulty,
      );
      onStart(result.interviewId); // FIX: Navigate to interview session on success
    } catch (err: any) {
      // FIX: Distinguish timeout, offline, and server errors with actionable messages instead of generic "Failed to start interview"
      const msg = err?.message || "";
      if (
        err?.status === 0 && // FIX: status 0 means no HTTP response was received (timeout or network)
        (msg.includes("timeout") || msg.includes("timed out"))
      ) {
        setError(
          "Taking longer than expected. Ollama may be loading " +
            "the AI model for the first time (can take 2-3 minutes). Please wait " +
            "and try again, or check if Ollama is running: " +
            'run "ollama serve" in your terminal.',
        ); // FIX: Timeout-specific error with Ollama troubleshooting hint
      } else if (!navigator.onLine) {
        setError(
          "No internet connection. Please check your network and try again.",
        ); // FIX: Detect actual offline state instead of misleading "network error"
      } else {
        setError(
          msg ||
            "Failed to start interview. Please ensure the backend server " +
              "and Ollama are running.",
        ); // FIX: Fallback message hints at backend/Ollama as likely root cause
      }
    } finally {
      setIsLoading(false);
    }
  }, [
    resumeId,
    selectedJobRole,
    difficulty,
    hasPermission,
    checkPermissions,
    onStart,
    numQuestions,
  ]);

  // ============================================================
  // Validations
  // ============================================================

  const canStart = hasResume && selectedJobRole !== null && !isLoading;

  // ============================================================
  // Loading state
  // ============================================================

  if (isLoadingData) {
    return (
      <Container maxWidth="sm" sx={{ py: 8, textAlign: "center" }}>
        <CircularProgress size={48} sx={{ mb: 2 }} />
        <Typography color="text.secondary">
          Loading interview setup...
        </Typography>
      </Container>
    );
  }

  // ============================================================
  // Render
  // ============================================================

  return (
    <Container maxWidth="sm" sx={{ py: 4 }}>
      <Typography variant="h4" fontWeight={700} gutterBottom>
        Start New Interview
      </Typography>
      <Typography color="text.secondary" sx={{ mb: 3 }}>
        Upload your resume, select a job role, and begin your AI-powered
        practice interview.
      </Typography>

      {/* Error Alert */}
      {error && (
        <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 3 }}>
          {error}
        </Alert>
      )}

      {/* ===== Section 1: Resume Status Card ===== */}
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
            Resume
          </Typography>

          {hasResume && resume ? (
            <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
              <CheckCircle color="success" />
              <Box sx={{ flex: 1 }}>
                <Typography variant="body2" fontWeight={500}>
                  {resume.fileName}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Uploaded {new Date(resume.uploadedAt).toLocaleDateString()}
                </Typography>
              </Box>
              <Button component="label" size="small" variant="text">
                Replace
                <input
                  type="file"
                  hidden
                  accept=".pdf,.doc,.docx"
                  onChange={handleResumeUpload}
                />
              </Button>
            </Box>
          ) : (
            <Box sx={{ textAlign: "center", py: 2 }}>
              <Description
                sx={{ fontSize: 40, color: "text.disabled", mb: 1 }}
              />
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                Upload your resume so the AI can tailor questions to your
                experience.
              </Typography>
              <Button
                component="label"
                variant="outlined"
                startIcon={
                  isUploading ? <CircularProgress size={18} /> : <CloudUpload />
                }
                disabled={isUploading}
              >
                {isUploading ? "Uploading..." : "Upload Resume"}
                <input
                  type="file"
                  hidden
                  accept=".pdf,.doc,.docx"
                  onChange={handleResumeUpload}
                />
              </Button>
            </Box>
          )}
        </CardContent>
      </Card>

      {/* ===== Section 2: Job Role Selector ===== */}
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
            Job Role
          </Typography>
          <FormControl fullWidth size="small">
            <InputLabel id="job-role-label">Select a job role</InputLabel>
            <Select
              labelId="job-role-label"
              value={selectedJobRole ?? ""}
              label="Select a job role"
              onChange={(e) => setSelectedJobRole(e.target.value as number)}
            >
              {jobRoles.map((role) => (
                <MenuItem key={role.id} value={role.id}>
                  <Box>
                    <Typography variant="body2" fontWeight={500}>
                      {role.title}
                    </Typography>
                    {role.description && (
                      <Typography variant="caption" color="text.secondary">
                        {role.description}
                      </Typography>
                    )}
                  </Box>
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </CardContent>
      </Card>

      {/* ===== Section 2.5: Number of Questions ===== */}
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
            Number of Questions
          </Typography>
          <FormControl fullWidth size="small">
            <InputLabel id="num-questions-label">
              Select number of questions
            </InputLabel>
            <Select
              labelId="num-questions-label"
              value={numQuestions}
              label="Select number of questions"
              onChange={(e) => setNumQuestions(e.target.value as number)}
            >
              <MenuItem value={0}>Default (AI Decides)</MenuItem>
              {[3, 5, 7, 10].map((num) => (
                <MenuItem key={num} value={num}>
                  {num} Questions
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </CardContent>
      </Card>

      {/* ===== Section 2.6: Difficulty Level ===== */}
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
            Difficulty Level
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Choose the difficulty of interview questions
          </Typography>
          <FormControl fullWidth size="small">
            <InputLabel id="difficulty-label">Select difficulty</InputLabel>
            <Select
              labelId="difficulty-label"
              value={difficulty}
              label="Select difficulty"
              onChange={(e) => setDifficulty(e.target.value as DifficultyLevel)}
            >
              <MenuItem value="AUTO">
                <Box>
                  <Typography variant="body2" fontWeight={500}>
                    Auto (Adaptive)
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    AI adjusts difficulty based on your responses
                  </Typography>
                </Box>
              </MenuItem>
              <MenuItem value="EASY">
                <Box>
                  <Typography variant="body2" fontWeight={500}>
                    Easy
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Foundational questions for beginners
                  </Typography>
                </Box>
              </MenuItem>
              <MenuItem value="MEDIUM">
                <Box>
                  <Typography variant="body2" fontWeight={500}>
                    Medium
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Standard interview-level questions
                  </Typography>
                </Box>
              </MenuItem>
              <MenuItem value="HARD">
                <Box>
                  <Typography variant="body2" fontWeight={500}>
                    Hard
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Challenging questions for senior roles
                  </Typography>
                </Box>
              </MenuItem>
            </Select>
          </FormControl>
        </CardContent>
      </Card>

      {/* ===== Section 3: Interview Type (future toggle) ===== */}
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
            Interview Type
          </Typography>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
            <Videocam color="primary" />
            <Box>
              <Typography variant="body2" fontWeight={500}>
                Video Interview
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Record video responses to AI-generated questions
              </Typography>
            </Box>
          </Box>
        </CardContent>
      </Card>

      {/* ===== Section 4: Tips & Instructions ===== */}
      <Alert
        icon={<LightbulbOutlined />}
        severity="info"
        sx={{ mb: 3, borderRadius: 2 }}
      >
        <Typography variant="subtitle2" fontWeight={600} gutterBottom>
          Interview Tips
        </Typography>
        <Typography variant="body2" component="ul" sx={{ pl: 2, m: 0 }}>
          <li>Find a quiet, well-lit space with a neutral background.</li>
          <li>Test your camera and microphone before starting.</li>
          <li>Speak clearly and maintain eye contact with the camera.</li>
          <li>Each question has a maximum response time of 3 minutes.</li>
          <li>You can re-record your answer before submitting.</li>
        </Typography>
      </Alert>

      {/* Camera permission status */}
      {hasPermission === false && (
        <Alert severity="warning" sx={{ mb: 3, borderRadius: 2 }}>
          Camera access is required. Please allow access when prompted.
        </Alert>
      )}

      {/* ===== Start Button ===== */}
      <Button
        variant="contained"
        size="large"
        fullWidth
        startIcon={
          isLoading ? (
            <CircularProgress size={20} color="inherit" />
          ) : (
            <PlayArrow />
          )
        }
        onClick={handleStart}
        disabled={!canStart}
        sx={{ py: 1.5, fontSize: "1rem", borderRadius: 2 }}
      >
        {isLoading
          ? "Generating your interview questions..."
          : "Start Interview"}
      </Button>
      {/* FIX: Show informational message during loading so user knows what's happening */}
      {isLoading && (
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{ mt: 2, textAlign: "center" }}
        >
          This may take 1-2 minutes while the AI generates personalized
          questions...
        </Typography>
      )}
    </Container>
  );
};

export default InterviewStart;
