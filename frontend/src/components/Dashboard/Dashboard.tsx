import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Alert,
    Box,
    Button,
    Card,
    CardContent,
    Chip,
    Container,
    FormControl,
    IconButton,
    InputLabel,
    MenuItem,
    Select,
    Skeleton,
    Typography,
    Grid,
} from '@mui/material';
import {
    Add,
    Assessment,
    CalendarToday,
    CheckCircle,
    CloudUpload,
    Delete,
    Description,
    PlayArrow,
    Visibility,
} from '@mui/icons-material';

import { InterviewDTO } from '../../types';
import { useAuth } from '../../hooks/useAuth';
import { interviewService } from '../../services/interview.service';
import { videoService } from '../../services/video.service';

// Cast Grid to any to suppress "xs" prop errors while using standard Grid v1
// as per user instructions to avoid "item" prop but use { Grid }.
const GridAny = Grid as any;

// ============================================================
// Status color/label helpers
// ============================================================

const getStatusChip = (status: string) => {
    switch (status) {
        case 'COMPLETED':
            return <Chip label="Completed" color="success" size="small" />;
        case 'IN_PROGRESS':
            return <Chip label="In Progress" color="warning" size="small" />;
        case 'PROCESSING':
            return <Chip label="Processing" color="info" size="small" />;
        default:
            return <Chip label={status} size="small" />;
    }
};

const getScoreColor = (score?: number): string => {
    if (!score) return 'text.secondary';
    if (score >= 80) return 'success.main';
    if (score >= 60) return 'warning.main';
    return 'error.main';
};

// ============================================================
// Dashboard Component
// ============================================================

const Dashboard: React.FC = () => {
    const navigate = useNavigate();
    const { user } = useAuth();

    const [interviews, setInterviews] = useState<InterviewDTO[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [hasResume, setHasResume] = useState(false);
    const [isUploading, setIsUploading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [statusFilter, setStatusFilter] = useState<string>('ALL');

    // ============================================================
    // Fetch data on mount
    // ============================================================

    useEffect(() => {
        const fetchData = async () => {
            setIsLoading(true);
            try {
                const history = await interviewService.getInterviewHistory();
                setInterviews(history);

                try {
                    await videoService.getMyResume();
                    setHasResume(true);
                } catch {
                    setHasResume(false);
                }
            } catch (err: any) {
                setError(err.message || 'Failed to load dashboard data.');
            } finally {
                setIsLoading(false);
            }
        };

        fetchData();
    }, []);

    // ============================================================
    // Delete Interview
    // ============================================================

    const handleDeleteInterview = useCallback(async (e: React.MouseEvent, interviewId: number) => {
        e.stopPropagation();
        if (!window.confirm('Are you sure you want to delete this interview and all its data?')) {
            return;
        }

        try {
            await interviewService.deleteInterview(interviewId);
            // After successful deletion, refresh the list
            const history = await interviewService.getInterviewHistory();
            setInterviews(history);
        } catch (err: any) {
            setError(err.message || 'Failed to delete interview.');
        }
    }, []);

    // ============================================================
    // Quick stats
    // ============================================================

    const stats = useMemo(() => {
        const completed = interviews.filter((i) => i.status === 'COMPLETED');
        const scored = completed.filter((i) => i.overallScore !== undefined && i.overallScore !== null);
        const avgScore = scored.length > 0
            ? Math.round(scored.reduce((sum, i) => sum + (i.overallScore || 0), 0) / scored.length)
            : null;
        const lastInterview = interviews.length > 0
            ? new Date(interviews[0].startedAt ?? '').toLocaleDateString()
            : null;

        return {
            totalCompleted: completed.length,
            averageScore: avgScore,
            lastInterviewDate: lastInterview,
        };
    }, [interviews]);

    // ============================================================
    // Filtered interviews
    // ============================================================

    const filteredInterviews = useMemo(() => {
        if (statusFilter === 'ALL') return interviews;
        return interviews.filter((i) => i.status === statusFilter);
    }, [interviews, statusFilter]);

    // ============================================================
    // Resume upload
    // ============================================================

    const handleResumeUpload = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;

        setIsUploading(true);
        try {
            await videoService.uploadResume(file);
            setHasResume(true);
        } catch (err: any) {
            setError(err.message || 'Failed to upload resume.');
        } finally {
            setIsUploading(false);
        }
    }, []);

    // ============================================================
    // Render: Loading skeletons
    // ============================================================

    if (isLoading) {
        return (
            <Container maxWidth="lg" sx={{ py: 4 }}>
                <Skeleton variant="text" width={300} height={48} sx={{ mb: 1 }} />
                <Skeleton variant="text" width={200} height={24} sx={{ mb: 3 }} />
                <GridAny container spacing={3} sx={{ mb: 4 }}>
                    {[1, 2, 3].map((i) => (
                        <GridAny xs={12} sm={4} key={i}>
                            <Skeleton variant="rounded" height={120} sx={{ borderRadius: 3 }} />
                        </GridAny>
                    ))}
                </GridAny>
                {[1, 2, 3].map((i) => (
                    <Skeleton key={i} variant="rounded" height={80} sx={{ mb: 2, borderRadius: 2 }} />
                ))}
            </Container>
        );
    }

    // ============================================================
    // Render: Dashboard
    // ============================================================

    return (
        <Container maxWidth="lg" sx={{ py: 4 }}>
            {/* Welcome Header */}
            <Box sx={{ mb: 4 }}>
                <Typography variant="h4" fontWeight={700}>
                    Welcome back, {user?.name || 'User'} 👋
                </Typography>
                <Typography color="text.secondary">
                    Track your progress and practice for your next interview.
                </Typography>
            </Box>

            {error && (
                <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 3 }}>
                    {error}
                </Alert>
            )}

            {/* ===== Quick Stats ===== */}
            <GridAny container spacing={3} sx={{ mb: 4 }}>
                <GridAny xs={12} sm={4}>
                    <Card elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider', height: '100%' }}>
                        <CardContent sx={{ p: 3 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5 }}>
                                <CheckCircle color="success" />
                                <Typography variant="body2" color="text.secondary">Completed</Typography>
                            </Box>
                            <Typography variant="h3" fontWeight={700}>
                                {stats.totalCompleted}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                                interviews completed
                            </Typography>
                        </CardContent>
                    </Card>
                </GridAny>

                <GridAny xs={12} sm={4}>
                    <Card elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider', height: '100%' }}>
                        <CardContent sx={{ p: 3 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5 }}>
                                <Assessment color="primary" />
                                <Typography variant="body2" color="text.secondary">Avg. Score</Typography>
                            </Box>
                            <Typography variant="h3" fontWeight={700} sx={{ color: getScoreColor(stats.averageScore ?? undefined) }}>
                                {stats.averageScore !== null ? stats.averageScore : '—'}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                                out of 100
                            </Typography>
                        </CardContent>
                    </Card>
                </GridAny>

                <GridAny xs={12} sm={4}>
                    <Card elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider', height: '100%' }}>
                        <CardContent sx={{ p: 3 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5 }}>
                                <CalendarToday color="info" />
                                <Typography variant="body2" color="text.secondary">Last Interview</Typography>
                            </Box>
                            <Typography variant="h5" fontWeight={700}>
                                {stats.lastInterviewDate || '—'}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                                most recent session
                            </Typography>
                        </CardContent>
                    </Card>
                </GridAny>
            </GridAny>

            {/* ===== Action Row: Resume + Start ===== */}
            <GridAny container spacing={3} sx={{ mb: 4 }}>
                {/* Resume Card */}
                <GridAny xs={12} sm={6}>
                    <Card elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider', height: '100%' }}>
                        <CardContent sx={{ p: 3 }}>
                            <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                                Resume
                            </Typography>
                            {hasResume ? (
                                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                    <Description color="success" />
                                    <Typography variant="body2" sx={{ flex: 1 }}>Resume uploaded</Typography>
                                    <Button component="label" size="small" variant="text">
                                        Replace
                                        <input type="file" hidden accept=".pdf,.doc,.docx" onChange={handleResumeUpload} />
                                    </Button>
                                </Box>
                            ) : (
                                <Box sx={{ textAlign: 'center', py: 1 }}>
                                    <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                                        Upload your resume to get personalized questions.
                                    </Typography>
                                    <Button
                                        component="label"
                                        variant="outlined"
                                        size="small"
                                        startIcon={isUploading ? <Skeleton variant="circular" width={18} height={18} /> : <CloudUpload />}
                                        disabled={isUploading}
                                    >
                                        {isUploading ? 'Uploading...' : 'Upload Resume'}
                                        <input type="file" hidden accept=".pdf,.doc,.docx" onChange={handleResumeUpload} />
                                    </Button>
                                </Box>
                            )}
                        </CardContent>
                    </Card>
                </GridAny>

                {/* Start Interview Card */}
                <GridAny xs={12} sm={6}>
                    <Card
                        elevation={0}
                        sx={{
                            borderRadius: 3,
                            border: '1px solid',
                            borderColor: 'primary.main',
                            bgcolor: 'primary.main',
                            color: '#fff',
                            height: '100%',
                            cursor: 'pointer',
                            transition: 'opacity 0.2s',
                            '&:hover': { opacity: 0.9 },
                        }}
                        onClick={() => navigate('/interview/start')}
                    >
                        <CardContent sx={{ p: 3, display: 'flex', alignItems: 'center', gap: 2 }}>
                            <Add sx={{ fontSize: 40 }} />
                            <Box>
                                <Typography variant="h6" fontWeight={700}>
                                    Start New Interview
                                </Typography>
                                <Typography variant="body2" sx={{ opacity: 0.85 }}>
                                    Practice with AI-generated questions tailored to your resume and target role.
                                </Typography>
                            </Box>
                        </CardContent>
                    </Card>
                </GridAny>
            </GridAny>

            {/* ===== Recent Interviews ===== */}
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                <Typography variant="h6" fontWeight={600}>
                    Interview History
                </Typography>
                <FormControl size="small" sx={{ minWidth: 140 }}>
                    <InputLabel>Status</InputLabel>
                    <Select
                        value={statusFilter}
                        label="Status"
                        onChange={(e) => setStatusFilter(e.target.value)}
                    >
                        <MenuItem value="ALL">All</MenuItem>
                        <MenuItem value="COMPLETED">Completed</MenuItem>
                        <MenuItem value="IN_PROGRESS">In Progress</MenuItem>
                        <MenuItem value="PROCESSING">Processing</MenuItem>
                    </Select>
                </FormControl>
            </Box>

            {/* Empty state */}
            {filteredInterviews.length === 0 && (
                <Card elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider' }}>
                    <CardContent sx={{ py: 6, textAlign: 'center' }}>
                        <PlayArrow sx={{ fontSize: 48, color: 'text.disabled', mb: 1 }} />
                        <Typography variant="h6" color="text.secondary" gutterBottom>
                            No interviews yet
                        </Typography>
                        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                            Start your first practice interview to see your history here.
                        </Typography>
                        <Button variant="contained" startIcon={<Add />} onClick={() => navigate('/interview/new')}>
                            Start Interview
                        </Button>
                    </CardContent>
                </Card>
            )}

            {/* Interview cards */}
            {filteredInterviews.map((interview) => (
                <Card
                    key={interview.interviewId}
                    elevation={0}
                    sx={{
                        mb: 2,
                        borderRadius: 2,
                        border: '1px solid',
                        borderColor: 'divider',
                        cursor: 'pointer',
                        transition: 'border-color 0.2s',
                        '&:hover': { borderColor: 'primary.main' },
                    }}
                    onClick={() => navigate(`/interview/${interview.interviewId}/review`)}
                >
                    <CardContent sx={{ p: 2.5, display: 'flex', alignItems: 'center', gap: 2 }}>
                        {/* Score */}
                        <Box
                            sx={{
                                width: 48,
                                height: 48,
                                borderRadius: '50%',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                bgcolor: interview.overallScore
                                    ? `${getScoreColor(interview.overallScore)}` : 'grey.200',
                                color: interview.overallScore ? '#fff' : 'text.secondary',
                                fontWeight: 700,
                                fontSize: '0.875rem',
                            }}
                        >
                            {interview.overallScore ?? '—'}
                        </Box>

                        {/* Info */}
                        <Box sx={{ flex: 1 }}>
                            <Typography variant="body1" fontWeight={600}>
                                {interview.jobRoleTitle ?? 'Interview'}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                                {new Date(interview.startedAt ?? '').toLocaleDateString()} · {interview.type}
                            </Typography>
                        </Box>

                        {/* Status */}
                        {getStatusChip(interview.status)}

                        {/* Action */}
                        <Box sx={{ display: 'flex', alignItems: 'center', ml: 1 }}>
                            <Visibility sx={{ color: 'text.disabled', mr: 2 }} />
                            <IconButton
                                size="small"
                                color="error"
                                onClick={(e) => handleDeleteInterview(e, interview.interviewId!)}
                                title="Delete Interview"
                            >
                                <Delete fontSize="small" />
                            </IconButton>
                        </Box>
                    </CardContent>
                </Card>
            ))}
        </Container>
    );
};

export default Dashboard;
