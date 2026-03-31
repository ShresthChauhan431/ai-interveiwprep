import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import ReactPlayer from 'react-player';
import {
    Accordion,
    AccordionDetails,
    AccordionSummary,
    Alert,
    Box,
    Button,
    Card,
    CardContent,
    Chip,
    CircularProgress,
    Container,
    Divider,
    List,
    ListItem,
    ListItemIcon,
    ListItemText,
    Tab,
    Tabs,
    Typography,
} from '@mui/material';
import {
    ArrowBack,
    Download,
    ExpandMore,
    Lightbulb,
    PlayArrow,
    QuestionAnswer,
    Star,
    TrendingDown,
    TrendingUp,
    Videocam,
} from '@mui/icons-material';
import { InterviewDTO, InterviewQuestion, Feedback, asFeedbackList } from '../../types';
import { interviewService } from '../../services/interview.service';

// Cast ReactPlayer to any to suppress "url" prop type error
const Player = ReactPlayer as any;

// ============================================================
// Props
// ============================================================

interface InterviewReviewProps {
    interviewId: number;
}

// ============================================================
// Helpers
// ============================================================

const getScoreColor = (score: number): string => {
    if (score >= 80) return '#4caf50';
    if (score >= 60) return '#ff9800';
    return '#f44336';
};

// ============================================================
// Tab Panel
// ============================================================

interface TabPanelProps {
    children: React.ReactNode;
    value: number;
    index: number;
}

const TabPanel: React.FC<TabPanelProps> = ({ children, value, index }) => (
    <Box role="tabpanel" hidden={value !== index} sx={{ pt: 3 }}>
        {value === index && children}
    </Box>
);

// ============================================================
// InterviewReview Component
// ============================================================

const InterviewReview: React.FC<InterviewReviewProps> = ({ interviewId }) => {
    const navigate = useNavigate();

    const [interview, setInterview] = useState<InterviewDTO | null>(null);
    const [questions, setQuestions] = useState<InterviewQuestion[]>([]);
    const [feedback, setFeedback] = useState<Feedback | null>(null);
    const [selectedQuestion, setSelectedQuestion] = useState<number | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [activeTab, setActiveTab] = useState(0);
    
    // SESSION: State for full session recording URL
    const [sessionRecordingUrl, setSessionRecordingUrl] = useState<string | null>(null);

    // ============================================================
    // Fetch data on mount
    // ============================================================

    useEffect(() => {
        const fetchData = async () => {
            setIsLoading(true);
            try {
                const data = await interviewService.getInterview(interviewId);
                setInterview(data);
                setQuestions(data.questions ?? []);

                if (data.questions && data.questions.length > 0) {
                    setSelectedQuestion(data.questions[0].questionId);
                }

                // Fetch feedback if completed
                if (data.status === 'COMPLETED') {
                    try {
                        const fb = await interviewService.getFeedback(interviewId);
                        setFeedback(fb);
                    } catch {
                        // Feedback may not be ready yet
                    }
                }
                
                // SESSION: Check for session recording in sessionStorage
                const storedSessionUrl = sessionStorage.getItem(`session-recording-${interviewId}`);
                if (storedSessionUrl) {
                    setSessionRecordingUrl(storedSessionUrl);
                }
            } catch (err: any) {
                setError(err.message || 'Failed to load interview data.');
            } finally {
                setIsLoading(false);
            }
        };

        fetchData();
    }, [interviewId]);

    // ============================================================
    // Helpers
    // ============================================================

    const handleDownloadFeedback = useCallback(() => {
        if (!feedback || !interview) return;

        const content = [
            `Interview Review — ${interview.jobRoleTitle ?? 'Interview'}`,
            `Date: ${new Date(interview.startedAt ?? '').toLocaleDateString()}`,
            `Score: ${feedback.overallScore}/100`,
            '',
            '=== STRENGTHS ===',
            ...asFeedbackList(feedback.strengths).map((s) => `• ${s}`),
            '',
            '=== AREAS FOR IMPROVEMENT ===',
            ...asFeedbackList(feedback.weaknesses).map((w) => `• ${w}`),
            '',
            '=== RECOMMENDATIONS ===',
            ...asFeedbackList(feedback.recommendations).map((r) => `• ${r}`),
            '',
            '=== DETAILED ANALYSIS ===',
            feedback.detailedAnalysis,
        ].join('\n');

        const blob = new Blob([content], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `interview-review-${interviewId}.txt`;
        a.click();
        URL.revokeObjectURL(url);
    }, [feedback, interview, interviewId]);

    // ============================================================
    // Loading
    // ============================================================

    if (isLoading) {
        return (
            <Container maxWidth="md" sx={{ py: 8, textAlign: 'center' }}>
                <CircularProgress size={48} sx={{ mb: 2 }} />
                <Typography color="text.secondary">Loading interview data...</Typography>
            </Container>
        );
    }

    if (error) {
        return (
            <Container maxWidth="md" sx={{ py: 8 }}>
                <Alert severity="error">{error}</Alert>
                <Button sx={{ mt: 2 }} startIcon={<ArrowBack />} onClick={() => navigate(-1)}>
                    Go Back
                </Button>
            </Container>
        );
    }

    if (!interview) return null;

    // ============================================================
    // Render
    // ============================================================

    return (
        <Container maxWidth="md" sx={{ py: 4 }}>
            {/* Back Button */}
            <Button startIcon={<ArrowBack />} onClick={() => navigate(-1)} sx={{ mb: 2 }}>
                Back
            </Button>

            {/* ===== Interview Header ===== */}
            <Card elevation={0} sx={{ mb: 3, borderRadius: 3, border: '1px solid', borderColor: 'divider' }}>
                <CardContent sx={{ p: 3, display: 'flex', alignItems: 'center', gap: 3, flexWrap: 'wrap' }}>
                    {/* Score circle */}
                    {interview.overallScore !== undefined && interview.overallScore !== null && (
                        <Box sx={{ position: 'relative', display: 'inline-flex' }}>
                            <CircularProgress
                                variant="determinate"
                                value={interview.overallScore}
                                size={80}
                                thickness={5}
                                sx={{ color: getScoreColor(interview.overallScore) }}
                            />
                            <Box
                                sx={{
                                    position: 'absolute', top: 0, left: 0, bottom: 0, right: 0,
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                }}
                            >
                                <Typography variant="h5" fontWeight={700} sx={{ color: getScoreColor(interview.overallScore) }}>
                                    {interview.overallScore}
                                </Typography>
                            </Box>
                        </Box>
                    )}

                    {/* Info */}
                    <Box sx={{ flex: 1 }}>
                        <Typography variant="h5" fontWeight={700}>
                            {interview.jobRoleTitle ?? 'Interview'}
                        </Typography>
                        <Typography variant="body2" color="text.secondary">
                            {new Date(interview.startedAt ?? '').toLocaleDateString()} · {questions.length} questions · {interview.type}
                        </Typography>
                    </Box>

                    {/* Status + Download */}
                    <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                        <Chip
                            label={interview.status}
                            color={interview.status === 'COMPLETED' ? 'success' : 'warning'}
                            size="small"
                        />
                        {feedback && (
                            <Button
                                size="small"
                                variant="outlined"
                                startIcon={<Download />}
                                onClick={handleDownloadFeedback}
                            >
                                Download
                            </Button>
                        )}
                    </Box>
                </CardContent>
            </Card>

            {/* ===== Tabs ===== */}
            <Box sx={{ borderBottom: 1, borderColor: 'divider' }}>
                <Tabs value={activeTab} onChange={(_, v) => setActiveTab(v)}>
                    <Tab label="Overview" />
                    <Tab label="Questions & Responses" />
                    <Tab label="Detailed Feedback" />
                    {sessionRecordingUrl && <Tab label="Full Session" icon={<Videocam sx={{ fontSize: 18 }} />} iconPosition="start" />}
                </Tabs>
            </Box>

            {/* ===== Tab 0: Overview ===== */}
            <TabPanel value={activeTab} index={0}>
                {feedback ? (
                    <Box>
                        {/* Strengths */}
                        <Card elevation={0} sx={{ mb: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                            <CardContent>
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
                                    <TrendingUp color="success" />
                                    <Typography fontWeight={600}>Strengths</Typography>
                                </Box>
                                <List dense disablePadding>
                                    {asFeedbackList(feedback.strengths).map((item, i) => (
                                        <ListItem key={i} disablePadding sx={{ py: 0.4 }}>
                                            <ListItemIcon sx={{ minWidth: 28 }}>
                                                <Star sx={{ fontSize: 16, color: 'success.main' }} />
                                            </ListItemIcon>
                                            <ListItemText primary={item} primaryTypographyProps={{ variant: 'body2' }} />
                                        </ListItem>
                                    ))}
                                </List>
                            </CardContent>
                        </Card>

                        {/* Weaknesses */}
                        <Card elevation={0} sx={{ mb: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                            <CardContent>
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
                                    <TrendingDown color="error" />
                                    <Typography fontWeight={600}>Areas for Improvement</Typography>
                                </Box>
                                <List dense disablePadding>
                                    {asFeedbackList(feedback.weaknesses).map((item, i) => (
                                        <ListItem key={i} disablePadding sx={{ py: 0.4 }}>
                                            <ListItemIcon sx={{ minWidth: 28 }}>
                                                <TrendingDown sx={{ fontSize: 16, color: 'error.main' }} />
                                            </ListItemIcon>
                                            <ListItemText primary={item} primaryTypographyProps={{ variant: 'body2' }} />
                                        </ListItem>
                                    ))}
                                </List>
                            </CardContent>
                        </Card>

                        {/* Recommendations */}
                        <Card elevation={0} sx={{ borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                            <CardContent>
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
                                    <Lightbulb color="warning" />
                                    <Typography fontWeight={600}>Recommendations</Typography>
                                </Box>
                                <List dense disablePadding>
                                    {asFeedbackList(feedback.recommendations).map((item, i) => (
                                        <ListItem key={i} disablePadding sx={{ py: 0.4 }}>
                                            <ListItemIcon sx={{ minWidth: 28 }}>
                                                <Lightbulb sx={{ fontSize: 16, color: 'warning.main' }} />
                                            </ListItemIcon>
                                            <ListItemText primary={item} primaryTypographyProps={{ variant: 'body2' }} />
                                        </ListItem>
                                    ))}
                                </List>
                            </CardContent>
                        </Card>
                    </Box>
                ) : (
                    <Alert severity="info">
                        Feedback is not available yet. It may still be processing.
                    </Alert>
                )}
            </TabPanel>

            {/* ===== Tab 1: Questions & Responses ===== */}
            <TabPanel value={activeTab} index={1}>
                {questions.map((question, index) => {
                    const hasResponse = Boolean(question.responseVideoUrl);

                    return (
                        <Accordion
                            key={question.questionId}
                            expanded={selectedQuestion === question.questionId}
                            onChange={() => setSelectedQuestion(selectedQuestion === question.questionId ? null : question.questionId)}
                            elevation={0}
                            sx={{
                                mb: 2,
                                borderRadius: '12px !important',
                                border: '1px solid',
                                borderColor: 'divider',
                                '&:before': { display: 'none' },
                            }}
                        >
                            <AccordionSummary expandIcon={<ExpandMore />}>
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                    <QuestionAnswer color="primary" sx={{ fontSize: 20 }} />
                                    <Typography fontWeight={500}>
                                        Q{index + 1}: {question.questionText.substring(0, 80)}
                                        {question.questionText.length > 80 ? '...' : ''}
                                    </Typography>
                                </Box>
                            </AccordionSummary>
                            <AccordionDetails>
                                {/* Full question */}
                                <Typography variant="body1" sx={{ mb: 2, fontWeight: 500 }}>
                                    {question.questionText}
                                </Typography>
                                <Chip label={question.category} size="small" variant="outlined" sx={{ mr: 1, mb: 2 }} />
                                <Chip
                                    label={question.difficulty}
                                    size="small"
                                    color={
                                        question.difficulty === 'EASY' ? 'success'
                                            : question.difficulty === 'HARD' ? 'error'
                                                : 'warning'
                                    }
                                    sx={{ mb: 2 }}
                                />

                                <Divider sx={{ mb: 2 }} />

                                {/* Video response */}
                                {hasResponse ? (
                                    <Box>
                                        <Typography variant="subtitle2" gutterBottom>
                                            Your Response
                                        </Typography>
                                        <Box
                                            sx={{
                                                borderRadius: 2,
                                                overflow: 'hidden',
                                                bgcolor: '#000',
                                                mb: 2,
                                            }}
                                            onContextMenu={(e) => e.preventDefault()}
                                        >
                                            <Player
                                                url={question.responseVideoUrl!}
                                                controls
                                                width="100%"
                                                height="auto"
                                            />
                                        </Box>

                                        {/* Transcription */}
                                        {question.responseTranscription && (
                                            <Card
                                                elevation={0}
                                                sx={{
                                                    bgcolor: 'grey.50',
                                                    borderRadius: 2,
                                                    border: '1px solid',
                                                    borderColor: 'divider',
                                                }}
                                            >
                                                <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
                                                    <Typography variant="caption" color="text.secondary" fontWeight={600}>
                                                        TRANSCRIPTION
                                                    </Typography>
                                                    <Typography variant="body2" sx={{ mt: 0.5, lineHeight: 1.6 }}>
                                                        {question.responseTranscription}
                                                    </Typography>
                                                </CardContent>
                                            </Card>
                                        )}
                                    </Box>
                                ) : (
                                    <Alert severity="warning" sx={{ borderRadius: 2 }}>
                                        No response recorded for this question.
                                    </Alert>
                                )}
                            </AccordionDetails>
                        </Accordion>
                    );
                })}
            </TabPanel>

            {/* ===== Tab 2: Detailed Feedback ===== */}
            <TabPanel value={activeTab} index={2}>
                {feedback?.detailedAnalysis ? (
                    <Box>
                        {/* AI Analysis Summary */}
                        <Card elevation={0} sx={{ borderRadius: 2, border: '1px solid', borderColor: 'divider', mb: 3 }}>
                            <CardContent sx={{ p: 3 }}>
                                <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                                    AI Analysis
                                </Typography>
                                <Divider sx={{ mb: 2 }} />
                                <Typography variant="body2" sx={{ whiteSpace: 'pre-line', lineHeight: 1.8 }}>
                                    {feedback.detailedAnalysis}
                                </Typography>
                            </CardContent>
                        </Card>

                        {/* Question-by-Question Analysis */}
                        {feedback.questionAnswers && feedback.questionAnswers.length > 0 && (
                            <Card elevation={0} sx={{ borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                                <CardContent sx={{ p: 3 }}>
                                    <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                                        Question-by-Question Breakdown
                                    </Typography>
                                    <Divider sx={{ mb: 2 }} />
                                    {feedback.questionAnswers.map((qa, index) => (
                                        <Box key={index} sx={{ mb: 3, pb: 3, borderBottom: index < feedback.questionAnswers!.length - 1 ? '1px solid' : 'none', borderColor: 'divider' }}>
                                            <Typography variant="subtitle2" fontWeight={600} color="primary" sx={{ mb: 1 }}>
                                                Question {index + 1}
                                            </Typography>
                                            <Typography variant="body2" sx={{ mb: 2, fontWeight: 500, bgcolor: 'grey.50', p: 1.5, borderRadius: 1 }}>
                                                {qa.questionText}
                                            </Typography>
                                            
                                            <Typography variant="caption" fontWeight={600} color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                                                YOUR ANSWER:
                                            </Typography>
                                            <Typography variant="body2" sx={{ mb: 2, lineHeight: 1.7, bgcolor: 'info.50', p: 1.5, borderRadius: 1, border: '1px solid', borderColor: 'info.200' }}>
                                                {qa.userAnswer || 'No response recorded'}
                                            </Typography>

                                            {qa.idealAnswer && (
                                                <>
                                                    <Typography variant="caption" fontWeight={600} color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                                                        SUGGESTED IDEAL ANSWER:
                                                    </Typography>
                                                    <Typography variant="body2" sx={{ lineHeight: 1.7, bgcolor: 'success.50', p: 1.5, borderRadius: 1, border: '1px solid', borderColor: 'success.200' }}>
                                                        {qa.idealAnswer}
                                                    </Typography>
                                                </>
                                            )}
                                        </Box>
                                    ))}
                                </CardContent>
                            </Card>
                        )}

                        {/* Fallback: Show questions from interview data if questionAnswers not in feedback */}
                        {(!feedback.questionAnswers || feedback.questionAnswers.length === 0) && questions.length > 0 && (
                            <Card elevation={0} sx={{ borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                                <CardContent sx={{ p: 3 }}>
                                    <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                                        Your Responses
                                    </Typography>
                                    <Divider sx={{ mb: 2 }} />
                                    {questions.map((question, index) => (
                                        <Box key={question.questionId} sx={{ mb: 3, pb: 3, borderBottom: index < questions.length - 1 ? '1px solid' : 'none', borderColor: 'divider' }}>
                                            <Typography variant="subtitle2" fontWeight={600} color="primary" sx={{ mb: 1 }}>
                                                Question {index + 1}
                                            </Typography>
                                            <Typography variant="body2" sx={{ mb: 2, fontWeight: 500, bgcolor: 'grey.50', p: 1.5, borderRadius: 1 }}>
                                                {question.questionText}
                                            </Typography>
                                            
                                            <Typography variant="caption" fontWeight={600} color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                                                YOUR ANSWER:
                                            </Typography>
                                            <Typography variant="body2" sx={{ lineHeight: 1.7, bgcolor: 'info.50', p: 1.5, borderRadius: 1, border: '1px solid', borderColor: 'info.200' }}>
                                                {question.responseTranscription || 'No response recorded'}
                                            </Typography>
                                        </Box>
                                    ))}
                                </CardContent>
                            </Card>
                        )}
                    </Box>
                ) : (
                    <Alert severity="info">
                        Detailed feedback is not available yet.
                    </Alert>
                )}

                {/* Action buttons */}
                <Box sx={{ display: 'flex', gap: 2, mt: 3 }}>
                    <Button
                        variant="contained"
                        startIcon={<PlayArrow />}
                        onClick={() => navigate('/interview/start')}
                    >
                        Start Another Interview
                    </Button>
                    {feedback && (
                        <Button
                            variant="outlined"
                            startIcon={<Download />}
                            onClick={handleDownloadFeedback}
                        >
                            Download Report
                        </Button>
                    )}
                </Box>
            </TabPanel>

            {/* ===== Tab 3: Full Session Recording ===== */}
            {sessionRecordingUrl && (
                <TabPanel value={activeTab} index={3}>
                    <Card elevation={0} sx={{ borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                        <CardContent sx={{ p: 3 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                                <Videocam color="primary" />
                                <Typography variant="h6" fontWeight={600}>
                                    Full Interview Session
                                </Typography>
                            </Box>
                            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
                                Watch your complete interview session from start to finish. This recording includes 
                                all questions asked and your responses, allowing you to review your entire performance.
                            </Typography>
                            
                            {/* Video Player */}
                            <Box
                                sx={{
                                    borderRadius: 2,
                                    overflow: 'hidden',
                                    bgcolor: '#000',
                                    mb: 3,
                                }}
                            >
                                <Player
                                    url={sessionRecordingUrl}
                                    controls
                                    width="100%"
                                    height="auto"
                                    style={{ aspectRatio: '16/9' }}
                                />
                            </Box>

                            {/* Download Button */}
                            <Box sx={{ display: 'flex', gap: 2 }}>
                                <Button
                                    variant="outlined"
                                    startIcon={<Download />}
                                    onClick={() => {
                                        const a = document.createElement('a');
                                        a.href = sessionRecordingUrl;
                                        a.download = `interview-session-${interviewId}.webm`;
                                        a.click();
                                    }}
                                >
                                    Download Session Video
                                </Button>
                            </Box>

                            <Alert severity="info" sx={{ mt: 3, borderRadius: 2 }}>
                                <Typography variant="body2">
                                    <strong>Note:</strong> This session recording is stored locally in your browser 
                                    and will be lost when you close or refresh the page. Download it now if you want to keep it.
                                </Typography>
                            </Alert>
                        </CardContent>
                    </Card>
                </TabPanel>
            )}
        </Container>
    );
};

export default InterviewReview;
