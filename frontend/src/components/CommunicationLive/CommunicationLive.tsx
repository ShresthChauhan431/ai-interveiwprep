import React, { useEffect, useRef, useState } from "react";
import {
  Box,
  Button,
  Container,
  Typography,
  CircularProgress,
  Paper,
  Divider,
  List,
  ListItem,
  ListItemText,
  AppBar,
  Toolbar,
  Avatar,
  Tooltip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  IconButton,
  Chip,
} from "@mui/material";
import {
  Mic,
  StopCircle,
  Assessment,
  Close,
  CheckCircle,
  Warning,
  Lightbulb,
  RecordVoiceOver,
  Videocam,
  ExitToApp,
  Download,
  Delete,
  FiberManualRecord,
} from "@mui/icons-material";
import {
  useCommunicationStore,
  Feedback,
} from "../../store/communicationStore";
import { communicationService } from "../../services/communication.service";
import { useSessionRecording } from "../../hooks/useSessionRecording";
import { useNavigate } from "react-router-dom";

// Web Speech API types are declared in src/types/speech-recognition.d.ts

const CommunicationLive: React.FC = () => {
  const {
    messages,
    overallFeedback,
    isRecording,
    isAnalyzing,
    isAILoading,
    isOverallAnalyzing,
    addMessage,
    setOverallFeedback,
    setIsRecording,
    setIsAnalyzing,
    setIsAILoading,
    setIsOverallAnalyzing,
    reset,
  } = useCommunicationStore();

  const [transcript, setTranscript] = useState("");
  const [interimTranscript, setInterimTranscript] = useState("");
  const [error, setError] = useState<string | null>(null);

  // Local state to map message index to its live feedback (since we can't update store messages directly)
  const [localFeedbacks, setLocalFeedbacks] = useState<
    Record<number, Feedback>
  >({});
  const [showOverallDialog, setShowOverallDialog] = useState(false);
  const [showStopDialog, setShowStopDialog] = useState(false);

  const navigate = useNavigate();

  // Session Recording
  const {
    isRecording: isSessionRecording,
    previewUrl: sessionPreviewUrl,
    startRecording: startSessionRecording,
    stopRecording: stopSessionRecording,
  } = useSessionRecording();

  const recognitionRef = useRef<any>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const analyzeTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Initialize Speech Recognition
  useEffect(() => {
    const SpeechRecognition =
      window.SpeechRecognition || window.webkitSpeechRecognition;
    if (SpeechRecognition) {
      const recognition = new SpeechRecognition();
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = "en-US";

      recognition.onresult = (event: any) => {
        let finalTrans = "";
        let interimTrans = "";
        for (let i = event.resultIndex; i < event.results.length; ++i) {
          if (event.results[i].isFinal) {
            finalTrans += event.results[i][0].transcript;
          } else {
            interimTrans += event.results[i][0].transcript;
          }
        }
        if (finalTrans) {
          setTranscript((prev) => prev + " " + finalTrans);
        }
        setInterimTranscript(interimTrans);
      };

      recognition.onerror = (event: any) => {
        console.error("Speech recognition error", event.error);
        if (event.error !== "no-speech") {
          setError("Microphone error: " + event.error);
          setIsRecording(false);
        }
      };

      recognition.onend = () => {
        setIsRecording(false);
      };

      recognitionRef.current = recognition;
    } else {
      setError(
        "Speech recognition is not supported in this browser. Please use Chrome.",
      );
    }

    // Cleanup
    return () => {
      reset();
      if (recognitionRef.current) {
        recognitionRef.current.stop();
      }
    };
  }, [reset, setIsRecording]);

  // Initial AI Message
  useEffect(() => {
    const fetchInitialMessage = async () => {
      setIsAILoading(true);
      try {
        const msg = await communicationService.startConversation();
        
        // TTS
        if ('speechSynthesis' in window) {
          const utterance = new SpeechSynthesisUtterance(msg);
          window.speechSynthesis.speak(utterance);
        }
        
        addMessage("ai", msg);
      } catch (err) {
        setError("Failed to start conversation.");
      } finally {
        setIsAILoading(false);
      }
    };
    fetchInitialMessage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Scroll to bottom of chat
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, interimTranscript, transcript]);

  const handleStartSpeaking = () => {
    // Stop any ongoing AI speech
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    
    if (analyzeTimeoutRef.current) {
      clearTimeout(analyzeTimeoutRef.current);
      analyzeTimeoutRef.current = null;
    }
    setTranscript("");
    setInterimTranscript("");
    setError(null);
    try {
      recognitionRef.current?.start();
      setIsRecording(true);
    } catch (e) {
      console.error(e);
    }
  };

  const handleStopSpeaking = () => {
    if (analyzeTimeoutRef.current) {
      clearTimeout(analyzeTimeoutRef.current);
    }
    analyzeTimeoutRef.current = setTimeout(async () => {
      await processStopSpeaking();
    }, 1500);
  };

  const processStopSpeaking = async () => {
    try {
      recognitionRef.current?.stop();
    } catch (e) {
      console.error(e);
    }
    setIsRecording(false);

    const finalText = (transcript + " " + interimTranscript).trim();
    if (!finalText) {
      setError("No speech detected. Please try again.");
      return;
    }

    // Capture the index this message will have
    const currentUserMessageIndex = messages.length;

    // Add user message immediately for real-time feel
    addMessage("user", finalText);
    setTranscript("");
    setInterimTranscript("");

    // Analyze live in the background
    setIsAnalyzing(true);
    setIsAILoading(true);
    try {
      // Run analysis and get next message concurrently for speed
      const historyPayload = messages
        .map((m) => ({ role: m.role, content: m.content }))
        .concat([{ role: "user", content: finalText }]);

      const [feedback, nextMsg] = await Promise.all([
        communicationService.analyzeLive(finalText),
        communicationService.getNextMessage(historyPayload),
      ]);

      // Save feedback locally tied to the message index
      setLocalFeedbacks((prev) => ({
        ...prev,
        [currentUserMessageIndex]: feedback,
      }));

      // TTS
      if ('speechSynthesis' in window) {
        const utterance = new SpeechSynthesisUtterance(nextMsg);
        window.speechSynthesis.speak(utterance);
      }

      // Add AI response
      addMessage("ai", nextMsg);
    } catch (err) {
      console.error(err);
      setError("Failed to analyze or get response.");
    } finally {
      setIsAnalyzing(false);
      setIsAILoading(false);
    }
  };

  const handleEndConversation = async () => {
    if (isSessionRecording) {
      stopSessionRecording();
    }
    
    // Stop any ongoing AI speech
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    
    setIsOverallAnalyzing(true);
    try {
      const historyPayload = messages.map((m) => ({
        role: m.role,
        content: m.content,
      }));
      const overall = await communicationService.analyzeOverall(historyPayload);
      setOverallFeedback(overall);
      setShowOverallDialog(true);
    } catch (err) {
      console.error(err);
      setError("Failed to generate overall feedback.");
    } finally {
      setIsOverallAnalyzing(false);
    }
  };

  // Helper component for Hover Tooltip on User Messages
  const MessageBubble = ({ msg, index }: { msg: any; index: number }) => {
    const isUser = msg.role === "user";
    const feedback = msg.feedback || localFeedbacks[index];

    const bubbleContent = (
      <Box
        sx={{
          alignSelf: isUser ? "flex-end" : "flex-start",
          maxWidth: "75%",
          bgcolor: isUser ? "#dcf8c6" : "#ffffff", // WhatsApp colors
          color: "text.primary",
          p: 1.5,
          px: 2,
          borderRadius: isUser ? "16px 16px 0px 16px" : "16px 16px 16px 0px",
          boxShadow: "0 1px 2px rgba(0,0,0,0.1)",
          mb: 1.5,
          position: "relative",
          cursor: isUser ? "pointer" : "default",
          "&:hover": isUser ? { opacity: 0.95 } : {},
        }}
      >
        <Typography variant="body1">{msg.content}</Typography>
        {isUser &&
          !feedback &&
          isAnalyzing &&
          index === messages.length - 1 && (
            <CircularProgress
              size={12}
              sx={{
                position: "absolute",
                bottom: 4,
                right: 8,
                color: "text.secondary",
              }}
            />
          )}
      </Box>
    );

    if (!isUser || !feedback) return bubbleContent;

    const isPerfect =
      feedback.grammarIssues.length === 0 && feedback.fluencyScore >= 85;

    const tooltipContent = (
      <Box sx={{ p: 1, maxWidth: 250 }}>
        {isPerfect ? (
          <Box display="flex" alignItems="center" gap={1}>
            <CheckCircle color="success" fontSize="small" />
            <Typography variant="body2" color="success.light">
              Perfectly said!
            </Typography>
          </Box>
        ) : (
          <>
            <Typography
              variant="subtitle2"
              color="error.light"
              display="flex"
              alignItems="center"
              gap={0.5}
            >
              <Warning fontSize="small" /> Correction:
            </Typography>
            <Typography variant="body2" sx={{ mb: 1, fontWeight: 500 }}>
              {feedback.correctedSentence}
            </Typography>
            <Typography variant="subtitle2" color="warning.light">
              Grammar Issues:
            </Typography>
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {feedback.grammarIssues.map((iss: string, i: number) => (
                <li key={i}>
                  <Typography variant="caption">{iss}</Typography>
                </li>
              ))}
            </ul>
          </>
        )}
        <Divider sx={{ my: 1, borderColor: "rgba(255,255,255,0.2)" }} />
        <Typography variant="caption" color="text.secondary">
          Fluency Score: {feedback.fluencyScore}/100
        </Typography>
      </Box>
    );

    return (
      <Tooltip title={tooltipContent} placement="left" arrow>
        {bubbleContent}
      </Tooltip>
    );
  };

  return (
    <Box
      sx={{
        height: "100vh",
        display: "flex",
        flexDirection: "column",
        bgcolor: "#f0f2f5",
      }}
    >
      {/* HEADER */}
      <AppBar position="static" color="primary" elevation={1}>
        <Toolbar sx={{ display: "flex", justifyContent: "space-between" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
            <Avatar sx={{ bgcolor: "secondary.main" }}>
              <RecordVoiceOver />
            </Avatar>
            <Box>
              <Typography variant="h6" lineHeight={1.2}>
                AI Interviewer
              </Typography>
              <Typography
                variant="caption"
                color="inherit"
                sx={{ opacity: 0.8 }}
              >
                {isAILoading ? "typing..." : "Online"}
              </Typography>
            </Box>
          </Box>
          <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
            {/* Recording Indicator */}
            {isSessionRecording && (
              <Chip
                icon={<FiberManualRecord sx={{ fontSize: 12, color: "error.main", animation: "pulse 1s infinite" }} />}
                label="REC"
                size="small"
                sx={{
                  bgcolor: "rgba(255,0,0,0.1)",
                  color: "error.main",
                  fontWeight: 600,
                  "& .MuiChip-icon": { color: "error.main" },
                }}
              />
            )}
            <Button
              variant="outlined"
              color="inherit"
              onClick={isSessionRecording ? stopSessionRecording : startSessionRecording}
              startIcon={isSessionRecording ? <StopCircle /> : <Videocam />}
              sx={{ borderRadius: 8, textTransform: "none", borderColor: isSessionRecording ? "error.main" : "inherit", color: isSessionRecording ? "error.main" : "inherit" }}
            >
              {isSessionRecording ? "Rec: Stop" : "Rec: Start"}
            </Button>
            <Button
              variant="outlined"
              color="inherit"
              onClick={() => setShowStopDialog(true)}
              startIcon={<ExitToApp />}
              sx={{ borderRadius: 8, textTransform: "none" }}
            >
              Stop Practice
            </Button>
            <Button
              variant="contained"
              color="error"
              onClick={handleEndConversation}
              disabled={isOverallAnalyzing || messages.length < 2}
              startIcon={
                isOverallAnalyzing ? (
                  <CircularProgress size={16} color="inherit" />
                ) : (
                  <Assessment />
                )
              }
              sx={{ borderRadius: 8, textTransform: "none" }}
            >
              End & Get Feedback
            </Button>
          </Box>
        </Toolbar>
      </AppBar>

      {error && (
        <Box
          sx={{
            p: 1,
            bgcolor: "error.main",
            color: "white",
            textAlign: "center",
          }}
        >
          <Typography variant="body2">{error}</Typography>
        </Box>
      )}

      {/* CHAT BODY */}
      <Box
        sx={{
          flex: 1,
          overflowY: "auto",
          p: { xs: 2, md: 4 },
          display: "flex",
          flexDirection: "column",
          backgroundImage:
            'url("https://user-images.githubusercontent.com/15075759/28719144-86dc0f70-73b1-11e7-911d-60d70fcded21.png")',
          backgroundRepeat: "repeat",
          opacity: 0.95,
        }}
      >
        <Container
          maxWidth="md"
          sx={{ display: "flex", flexDirection: "column", flex: 1 }}
        >
          <Box sx={{ textAlign: "center", mb: 3 }}>
            <Typography
              variant="caption"
              sx={{
                bgcolor: "rgba(0,0,0,0.1)",
                px: 2,
                py: 0.5,
                borderRadius: 4,
              }}
            >
              Hover over your own messages to see corrections.
            </Typography>
          </Box>

          {messages.map((msg, index) => (
            <MessageBubble key={msg.id} msg={msg} index={index} />
          ))}

          {/* Live User Transcript Bubble */}
          {(isRecording || interimTranscript || transcript) && (
            <Box
              sx={{
                alignSelf: "flex-end",
                maxWidth: "75%",
                bgcolor: "#dcf8c6",
                color: "text.primary",
                p: 1.5,
                px: 2,
                borderRadius: "16px 16px 0px 16px",
                boxShadow: "0 1px 2px rgba(0,0,0,0.1)",
                mb: 1.5,
                opacity: 0.7,
              }}
            >
              <Typography variant="body1">
                {transcript}{" "}
                <span style={{ color: "#666" }}>{interimTranscript}</span>
              </Typography>
              <Box
                sx={{
                  display: "flex",
                  alignItems: "center",
                  gap: 1,
                  mt: 1,
                  opacity: 0.6,
                }}
              >
                <div
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: "50%",
                    backgroundColor: "red",
                    animation: "pulse 1s infinite",
                  }}
                />
                <Typography variant="caption">Listening...</Typography>
              </Box>
            </Box>
          )}

          {isAILoading && (
            <Box
              sx={{
                alignSelf: "flex-start",
                bgcolor: "#ffffff",
                p: 1.5,
                px: 2,
                borderRadius: "16px 16px 16px 0px",
                boxShadow: "0 1px 2px rgba(0,0,0,0.1)",
                mb: 1.5,
                display: "flex",
                alignItems: "center",
                gap: 1,
              }}
            >
              <CircularProgress size={16} />
              <Typography variant="body2" color="text.secondary">
                AI is typing...
              </Typography>
            </Box>
          )}
          <div ref={messagesEndRef} />
        </Container>
      </Box>

      {/* FOOTER INPUT AREA */}
      <Paper
        elevation={3}
        sx={{
          p: 2,
          bgcolor: "#f0f2f5",
          display: "flex",
          justifyContent: "center",
        }}
      >
        <Container
          maxWidth="sm"
          sx={{ display: "flex", justifyContent: "center" }}
        >
          {!isRecording ? (
            <Button
              variant="contained"
              color="primary"
              size="large"
              startIcon={<Mic />}
              onClick={handleStartSpeaking}
              disabled={isAnalyzing || isAILoading}
              sx={{
                px: 4,
                py: 1.5,
                borderRadius: 8,
                textTransform: "none",
                fontSize: "1.1rem",
              }}
            >
              Hold to Speak
            </Button>
          ) : (
            <Button
              variant="contained"
              color="error"
              size="large"
              startIcon={<StopCircle />}
              onClick={handleStopSpeaking}
              sx={{
                px: 4,
                py: 1.5,
                borderRadius: 8,
                textTransform: "none",
                fontSize: "1.1rem",
                animation: "pulse 1.5s infinite",
              }}
            >
              Stop & Send
            </Button>
          )}
        </Container>
      </Paper>

      {/* OVERALL FEEDBACK MODAL */}
      <Dialog
        open={showOverallDialog}
        onClose={() => setShowOverallDialog(false)}
        maxWidth="sm"
        fullWidth
        PaperProps={{ sx: { borderRadius: 3 } }}
      >
        <DialogTitle
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            bgcolor: "primary.main",
            color: "white",
          }}
        >
          <Typography variant="h6" display="flex" alignItems="center" gap={1}>
            <Assessment /> Conversation Report
          </Typography>
          <IconButton
            onClick={() => setShowOverallDialog(false)}
            size="small"
            sx={{ color: "white" }}
          >
            <Close />
          </IconButton>
        </DialogTitle>
        <DialogContent sx={{ mt: 2 }}>
          {overallFeedback ? (
            <Box display="flex" flexDirection="column" gap={3}>
              <Box textAlign="center">
                <Typography
                  variant="h3"
                  color={
                    overallFeedback.overallScore > 80
                      ? "success.main"
                      : "warning.main"
                  }
                  fontWeight={700}
                >
                  {overallFeedback.overallScore}/100
                </Typography>
                <Typography variant="subtitle1" color="text.secondary">
                  Overall Fluency Score
                </Typography>
              </Box>

              <Paper variant="outlined" sx={{ p: 2, bgcolor: "grey.50" }}>
                <Typography variant="body1" fontWeight={500} gutterBottom>
                  Summary
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {overallFeedback.overallFeedback}
                </Typography>
              </Paper>

              <Box>
                <Typography
                  variant="subtitle2"
                  color="error.main"
                  display="flex"
                  alignItems="center"
                  gap={1}
                  mb={1}
                >
                  <Warning fontSize="small" /> Weak Points
                </Typography>
                <List dense disablePadding>
                  {overallFeedback.weakPoints.map((point, idx) => (
                    <ListItem key={idx} sx={{ py: 0.5 }}>
                      <ListItemText primary={`• ${point}`} />
                    </ListItem>
                  ))}
                </List>
              </Box>

              <Box>
                <Typography
                  variant="subtitle2"
                  color="success.main"
                  display="flex"
                  alignItems="center"
                  gap={1}
                  mb={1}
                >
                  <Lightbulb fontSize="small" /> Improvement Tips
                </Typography>
                <List dense disablePadding>
                  {overallFeedback.improvementTips.map((tip, idx) => (
                    <ListItem key={idx} sx={{ py: 0.5 }}>
                      <ListItemText primary={`• ${tip}`} />
                    </ListItem>
                  ))}
                </List>
              </Box>
            </Box>
          ) : (
            <Box textAlign="center" py={4}>
              <CircularProgress />
            </Box>
          )}
        </DialogContent>
        <DialogActions sx={{ p: 2, pt: 0, justifyContent: 'space-between' }}>
          <Box sx={{ display: "flex", gap: 1 }}>
              {sessionPreviewUrl && (
                  <>
                      <Button
                          variant="outlined"
                          color="primary"
                          startIcon={<Download />}
                          onClick={() => {
                              const a = document.createElement("a");
                              a.href = sessionPreviewUrl;
                              a.download = "practice-session.webm";
                              a.click();
                          }}
                      >
                          Download Recording
                      </Button>
                      <Button
                          variant="outlined"
                          color="error"
                          startIcon={<Delete />}
                          onClick={() => {
                              // Revoke the URL to free memory
                              URL.revokeObjectURL(sessionPreviewUrl);
                              // Close dialog and reset
                              setShowOverallDialog(false);
                              reset();
                              navigate("/dashboard");
                          }}
                      >
                          Delete Recording
                      </Button>
                  </>
              )}
          </Box>
          <Button
            variant="contained"
            onClick={() => {
              setShowOverallDialog(false);
              reset();
              window.location.reload(); // Quick way to restart the practice
            }}
          >
            Start New Practice
          </Button>
        </DialogActions>
      </Dialog>

      {/* STOP PRACTICE CONFIRMATION DIALOG */}
      <Dialog
        open={showStopDialog}
        onClose={() => setShowStopDialog(false)}
        maxWidth="xs"
        fullWidth
        PaperProps={{ sx: { borderRadius: 3 } }}
      >
        <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <ExitToApp color="warning" />
          Stop Practice?
        </DialogTitle>
        <DialogContent>
          <Typography variant="body1" color="text.secondary">
            Are you sure you want to stop this practice session? You will not receive feedback on your conversation.
          </Typography>
          {isSessionRecording && (
            <Typography variant="body2" color="warning.main" sx={{ mt: 2 }}>
              Note: Your recording will be discarded.
            </Typography>
          )}
        </DialogContent>
        <DialogActions sx={{ p: 2 }}>
          <Button
            variant="outlined"
            onClick={() => setShowStopDialog(false)}
          >
            Continue Practice
          </Button>
          <Button
            variant="contained"
            color="error"
            startIcon={<ExitToApp />}
            onClick={() => {
              // Stop any ongoing processes
              if (isSessionRecording) {
                stopSessionRecording();
              }
              if ('speechSynthesis' in window) {
                window.speechSynthesis.cancel();
              }
              if (recognitionRef.current) {
                try {
                  recognitionRef.current.stop();
                } catch (e) {
                  // Ignore errors
                }
              }
              // Reset state and navigate
              reset();
              setShowStopDialog(false);
              navigate("/dashboard");
            }}
          >
            Stop & Exit
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default CommunicationLive;
