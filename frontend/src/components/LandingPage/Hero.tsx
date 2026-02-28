
import React from 'react';
import { Box, Container, Typography, Button, Stack, useTheme, Avatar, AvatarGroup, Paper } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import RocketLaunchIcon from '@mui/icons-material/RocketLaunch';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';

const Hero: React.FC = () => {
    const theme = useTheme();
    const navigate = useNavigate();

    return (
        <Box
            sx={{
                width: '100%',
                bgcolor: 'background.default',
                pt: { xs: 8, md: 16 },
                pb: { xs: 8, md: 16 },
                overflow: 'hidden',
                position: 'relative',
            }}
        >
            {/* Background Gradient Orbs */}
            <Box
                sx={{
                    position: 'absolute',
                    top: '-20%',
                    right: '-10%',
                    width: '600px',
                    height: '600px',
                    borderRadius: '50%',
                    background: `radial-gradient(circle, ${theme.palette.primary.main}20 0%, transparent 70%)`,
                    filter: 'blur(60px)',
                    zIndex: 0,
                    animation: 'float 10s ease-in-out infinite',
                    '@keyframes float': {
                        '0%': { transform: 'translate(0, 0)' },
                        '50%': { transform: 'translate(-20px, 20px)' },
                        '100%': { transform: 'translate(0, 0)' },
                    },
                }}
            />
            <Box
                sx={{
                    position: 'absolute',
                    bottom: '-10%',
                    left: '-10%',
                    width: '500px',
                    height: '500px',
                    borderRadius: '50%',
                    background: `radial-gradient(circle, ${theme.palette.secondary.main}15 0%, transparent 70%)`,
                    filter: 'blur(60px)',
                    zIndex: 0,
                }}
            />

            <Container maxWidth="lg" sx={{ position: 'relative', zIndex: 1 }}>
                <Stack
                    direction={{ xs: 'column', md: 'row' }}
                    spacing={{ xs: 8, md: 4 }}
                    alignItems="center"
                    justifyContent="space-between"
                >
                    {/* Text Content */}
                    <Box sx={{ flex: 1, textAlign: { xs: 'center', md: 'left' } }}>
                        <Box
                            sx={{
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: 1,
                                px: 2,
                                py: 0.5,
                                mb: 3,
                                borderRadius: 50,
                                bgcolor: theme.palette.mode === 'light' ? 'rgba(25, 118, 210, 0.1)' : 'rgba(25, 118, 210, 0.2)',
                                color: 'primary.main',
                                fontWeight: 600,
                                fontSize: '0.875rem'
                            }}
                        >
                            <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'primary.main' }} />
                            New: AI Voice Analysis
                        </Box>

                        <Typography
                            variant="h1"
                            component="h1"
                            sx={{
                                fontSize: { xs: '3rem', sm: '3.5rem', md: '4.5rem' },
                                fontWeight: 800,
                                lineHeight: 1.1,
                                mb: 3,
                                letterSpacing: '-0.02em',
                            }}
                        >
                            Master Your Interview <br />
                            <Box
                                component="span"
                                sx={{
                                    background: `linear-gradient(135deg, ${theme.palette.primary.main} 0%, ${theme.palette.secondary.main} 100%)`,
                                    backgroundClip: 'text',
                                    textFillColor: 'transparent',
                                    color: 'transparent',
                                }}
                            >
                                With Confidence
                            </Box>
                        </Typography>
                        <Typography
                            variant="h5"
                            color="text.secondary"
                            sx={{ mb: 5, maxWidth: 600, mx: { xs: 'auto', md: 0 }, lineHeight: 1.6, fontSize: { xs: '1.1rem', md: '1.25rem' } }}
                        >
                            Get realistic practice, instant AI feedback, and personalized coaching to ace your next job interview.
                        </Typography>

                        <Stack
                            direction={{ xs: 'column', sm: 'row' }}
                            spacing={2}
                            justifyContent={{ xs: 'center', md: 'flex-start' }}
                            sx={{ mb: 6 }}
                        >
                            <Button
                                variant="contained"
                                size="large"
                                startIcon={<RocketLaunchIcon />}
                                onClick={() => navigate('/register')}
                                sx={{
                                    px: 4,
                                    py: 1.8,
                                    fontSize: '1.1rem',
                                    borderRadius: '12px',
                                    boxShadow: `0 8px 20px ${theme.palette.primary.main}40`,
                                    '&:hover': {
                                        transform: 'translateY(-2px)',
                                        boxShadow: `0 12px 24px ${theme.palette.primary.main}60`,
                                    },
                                    transition: 'all 0.2s',
                                }}
                            >
                                Start Practicing Free
                            </Button>
                            <Button
                                variant="text"
                                size="large"
                                startIcon={<PlayCircleOutlineIcon />}
                                onClick={() => navigate('/login')}
                                sx={{
                                    px: 4,
                                    fontSize: '1.1rem',
                                    borderRadius: '12px',
                                    color: 'text.primary',
                                }}
                            >
                                View Demo
                            </Button>
                        </Stack>

                        {/* Social Proof / Trust */}
                        <Stack direction="row" spacing={2} alignItems="center" justifyContent={{ xs: 'center', md: 'flex-start' }}>
                            <AvatarGroup max={4} sx={{ '& .MuiAvatar-root': { width: 32, height: 32, fontSize: '0.8rem' } }}>
                                <Avatar alt="Remy Sharp" src="https://i.pravatar.cc/150?u=1" />
                                <Avatar alt="Travis Howard" src="https://i.pravatar.cc/150?u=2" />
                                <Avatar alt="Cindy Baker" src="https://i.pravatar.cc/150?u=3" />
                                <Avatar alt="Agnes Walker" src="https://i.pravatar.cc/150?u=4" />
                            </AvatarGroup>
                            <Box>
                                <Stack direction="row" spacing={0.5} alignItems="center">
                                    {[1, 2, 3, 4, 5].map((i) => (
                                        <Typography key={i} component="span" sx={{ color: '#FAAF00', fontSize: '1.2rem', lineHeight: 1 }}>★</Typography>
                                    ))}
                                </Stack>
                                <Typography variant="caption" color="text.secondary">
                                    Trusted by 10,000+ candidates
                                </Typography>
                            </Box>
                        </Stack>
                    </Box>

                    {/* Visual/Image Placeholder - Glassmorphism Card */}
                    <Box
                        sx={{
                            flex: 1,
                            position: 'relative',
                            display: 'flex',
                            width: '100%',
                            justifyContent: 'center',
                            perspective: '1000px',
                        }}
                    >
                        <Box
                            sx={{
                                width: '100%',
                                maxWidth: 540,
                                position: 'relative',
                                transform: 'rotateY(-10deg) rotateX(5deg)',
                                transition: 'transform 0.5s ease',
                                '&:hover': {
                                    transform: 'rotateY(0deg) rotateX(0deg)',
                                },
                            }}
                        >
                            {/* Main Card */}
                            <Paper
                                elevation={20}
                                sx={{
                                    p: 3,
                                    borderRadius: 4,
                                    overflow: 'hidden',
                                    background: theme.palette.mode === 'light' ? 'rgba(255,255,255,0.8)' : 'rgba(30,30,30,0.8)',
                                    backdropFilter: 'blur(20px)',
                                    border: `1px solid ${theme.palette.divider}`,
                                }}
                            >
                                {/* Browser-like Header */}
                                <Stack direction="row" spacing={1} sx={{ mb: 3 }}>
                                    <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#FF5F57' }} />
                                    <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#FFBD2E' }} />
                                    <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: '#28C840' }} />
                                </Stack>

                                {/* Mock Content */}
                                <Stack direction="row" spacing={4} alignItems="flex-start">
                                    {/* Video/Avatar Placeholder */}
                                    <Box sx={{ width: 120, height: 160, borderRadius: 2, bgcolor: 'grey.200', position: 'relative', overflow: 'hidden' }}>
                                        <Box
                                            component="img"
                                            src="https://i.pravatar.cc/300?u=mock"
                                            alt="Candidate"
                                            sx={{ width: '100%', height: '100%', objectFit: 'cover' }}
                                        />
                                        <Box sx={{ position: 'absolute', bottom: 8, left: 8, width: 20, height: 20, bgcolor: 'error.main', borderRadius: '50%', border: '2px solid white' }} />
                                    </Box>

                                    {/* Analysis Content */}
                                    <Box sx={{ flex: 1 }}>
                                        <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                                            AI Analysis
                                        </Typography>
                                        <Typography variant="h6" fontWeight={700} gutterBottom>
                                            Strong Communication
                                        </Typography>

                                        <Stack spacing={2} sx={{ mt: 2 }}>
                                            <Box>
                                                <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
                                                    <Typography variant="caption" fontWeight={600}>Clarity</Typography>
                                                    <Typography variant="caption" fontWeight={600} color="success.main">92%</Typography>
                                                </Stack>
                                                <Box sx={{ height: 6, borderRadius: 3, bgcolor: 'grey.100', overflow: 'hidden' }}>
                                                    <Box sx={{ width: '92%', height: '100%', bgcolor: 'success.main' }} />
                                                </Box>
                                            </Box>
                                            <Box>
                                                <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
                                                    <Typography variant="caption" fontWeight={600}>Confidence</Typography>
                                                    <Typography variant="caption" fontWeight={600} color="primary.main">88%</Typography>
                                                </Stack>
                                                <Box sx={{ height: 6, borderRadius: 3, bgcolor: 'grey.100', overflow: 'hidden' }}>
                                                    <Box sx={{ width: '88%', height: '100%', bgcolor: 'primary.main' }} />
                                                </Box>
                                            </Box>
                                            <Box>
                                                <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
                                                    <Typography variant="caption" fontWeight={600}>Pace</Typography>
                                                    <Typography variant="caption" fontWeight={600} color="warning.main">Good</Typography>
                                                </Stack>
                                                <Box sx={{ height: 6, borderRadius: 3, bgcolor: 'grey.100', overflow: 'hidden' }}>
                                                    <Box sx={{ width: '75%', height: '100%', bgcolor: 'warning.main' }} />
                                                </Box>
                                            </Box>
                                        </Stack>
                                    </Box>
                                </Stack>

                                {/* Feedback Chip */}
                                <Box sx={{ mt: 3, p: 2, bgcolor: 'primary.50', borderRadius: 2, display: 'flex', alignItems: 'flex-start', gap: 1 }}>
                                    <CheckCircleIcon color="primary" sx={{ fontSize: 20 }} />
                                    <Typography variant="caption" color="text.primary">
                                        "Excellent use of STAR method. Try to be specific about your personal contribution in the next response."
                                    </Typography>
                                </Box>
                            </Paper>

                            {/* Floating Badge */}
                            <Paper
                                elevation={10}
                                sx={{
                                    position: 'absolute',
                                    top: 40,
                                    right: -30,
                                    p: 1.5,
                                    borderRadius: 3,
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: 1.5,
                                    animation: 'float 6s ease-in-out infinite',
                                    animationDelay: '1s',
                                    '@keyframes float': {
                                        '0%': { transform: 'translateY(0)' },
                                        '50%': { transform: 'translateY(-10px)' },
                                        '100%': { transform: 'translateY(0)' },
                                    },
                                }}
                            >
                                <Box sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: 'error.main', boxShadow: '0 0 0 4px rgba(211, 47, 47, 0.2)' }} />
                                <Typography variant="subtitle2" fontWeight={700}>Recoding</Typography>
                            </Paper>
                        </Box>
                    </Box>
                </Stack>
            </Container>
        </Box>
    );
};

export default Hero;
