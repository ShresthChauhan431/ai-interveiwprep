import React, { useEffect, useState } from 'react';
import { Box, Container, Typography, CircularProgress, Alert } from '@mui/material';
import InterviewHistory from './InterviewHistory';
import { interviewService } from '../../services/interview.service';
import { InterviewDTO } from '../../types';

const InterviewHistoryPage: React.FC = () => {
    const [interviews, setInterviews] = useState<InterviewDTO[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const fetchHistory = async () => {
            try {
                const history = await interviewService.getInterviewHistory();
                setInterviews(history);
            } catch (err: any) {
                setError(err.message || 'Failed to load history.');
            } finally {
                setIsLoading(false);
            }
        };
        fetchHistory();
    }, []);

    if (isLoading) {
        return (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
                <CircularProgress />
            </Box>
        );
    }

    return (
        <Container maxWidth="lg" sx={{ py: 4 }}>
            <Typography variant="h4" fontWeight={700} gutterBottom>
                Interview History
            </Typography>
            <Typography color="text.secondary" sx={{ mb: 4 }}>
                Review past interviews and track your performance.
            </Typography>
            {error && <Alert severity="error" sx={{ mb: 3 }}>{error}</Alert>}
            <InterviewHistory interviews={interviews} />
        </Container>
    );
};

export default InterviewHistoryPage;
