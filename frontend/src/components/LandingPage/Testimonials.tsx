
import React from 'react';
import { Box, Container, Typography, Avatar, Paper, Rating, useTheme } from '@mui/material';
import FormatQuoteIcon from '@mui/icons-material/FormatQuote';

const testimonials = [
  {
    name: "Shresth Chauhan",
    role: "Software Engineer @SVG",
    avatar: "https://www.behance.net/gallery/121802423/Letter-S-Monogram-Logo",
    rating: 5,
    quote: "This platform completely changed my interview game. I went from being nervous and rambling to concise and confident. Landed the job!",
  },
  {
    name: "Dhwanit Vyas",
    role: "Product Manager @ StartUp",
    avatar: "https://www.behance.net/gallery/121802423/Letter-S-Monogram-Logo",
    rating: 5,
    quote: "The AI feedback is incredible accurately. It caught behavioral cues I didn't even know I had. Highly recommended for any serious job seeker.",
  },
  {
    name: "Mehak Bansal",
    role: "UX Designer @ CreativeAgency",
    avatar: "https://www.behance.net/gallery/121802423/Letter-S-Monogram-Logo",
    rating: 4.5,
    quote: "I love the role-specific questions. It felt like a real interview simulation. The video recording playback was an eye-opener.",
  },
];

const Testimonials: React.FC = () => {
    const theme = useTheme();

    return (
        <Box sx={{ py: 12, bgcolor: 'background.default' }}>
            <Container maxWidth="lg">
                <Typography
                    variant="h3"
                    align="center"
                    gutterBottom
                    sx={{ fontWeight: 700, mb: 6 }}
                >
                    Success Stories
                </Typography>

                <Box sx={{ display: 'flex', flexWrap: 'wrap', mx: -2 }}>
                    {testimonials.map((testimonial, index) => (
                        <Box
                            key={index}
                            sx={{
                                p: 2,
                                width: { xs: '100%', md: '33.33%' },
                                display: 'flex'
                            }}
                        >
                            <Paper
                                elevation={0}
                                sx={{
                                    p: 4,
                                    width: '100%',
                                    borderRadius: 4,
                                    bgcolor: 'background.paper',
                                    border: `1px solid ${theme.palette.divider}`,
                                    display: 'flex',
                                    flexDirection: 'column',
                                    position: 'relative',
                                }}
                            >
                                <FormatQuoteIcon
                                    sx={{
                                        position: 'absolute',
                                        top: 20,
                                        right: 20,
                                        fontSize: 40,
                                        color: theme.palette.divider,
                                        opacity: 0.5,
                                    }}
                                />

                                <Rating value={testimonial.rating} readOnly precision={0.5} sx={{ mb: 2 }} />

                                <Typography variant="body1" sx={{ mb: 3, flex: 1, fontStyle: 'italic', lineHeight: 1.6 }}>
                                    "{testimonial.quote}"
                                </Typography>

                                <Box sx={{ display: 'flex', alignItems: 'center', mt: 'auto' }}>
                                    <Avatar
                                        src={testimonial.avatar}
                                        alt={testimonial.name}
                                        sx={{ mr: 2, width: 48, height: 48 }}
                                    />
                                    <Box>
                                        <Typography variant="subtitle1" fontWeight={700}>
                                            {testimonial.name}
                                        </Typography>
                                        <Typography variant="caption" color="text.secondary">
                                            {testimonial.role}
                                        </Typography>
                                    </Box>
                                </Box>
                            </Paper>
                        </Box>
                    ))}
                </Box>
            </Container>
        </Box>
    );
};

export default Testimonials;
