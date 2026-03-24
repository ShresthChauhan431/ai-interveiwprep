import React, { useEffect, useRef, useState } from 'react';
import { Box, Typography, Paper, Chip, Button } from '@mui/material';
import VolumeUpIcon from '@mui/icons-material/VolumeUp';
import VolumeOffIcon from '@mui/icons-material/VolumeOff';
import MicIcon from '@mui/icons-material/Mic';

import api from '../../services/api.service';

interface QuestionPresenterProps {
  questionText: string;
  audioUrl?: string | null;
  questionNumber: number;
  totalQuestions: number;
  onAudioComplete?: () => void;
}

const QuestionPresenter: React.FC<QuestionPresenterProps> = ({
  questionText,
  audioUrl,
  questionNumber,
  totalQuestions,
  onAudioComplete,
}) => {
  const audioRef = useRef<HTMLAudioElement | null>(null); // FIX: Ref to HTML audio element for playback control
  const animationRef = useRef<ReturnType<typeof setInterval> | null>(null); // FIX: Ref to waveform animation interval
  const [isPlaying, setIsPlaying] = useState(false); // FIX: Track audio playback state
  const [audioFailed, setAudioFailed] = useState(false); // FIX: Track audio load/play failure state
  const [waveValues, setWaveValues] = useState<number[]>(Array(14).fill(4)); // FIX: Waveform bar heights for animation

  const startWave = () => { // FIX: Start waveform animation when audio is playing
    animationRef.current = setInterval(() => {
      setWaveValues(Array(14).fill(0).map(() => Math.floor(Math.random() * 28) + 4)); // FIX: Randomize bar heights for visual effect
    }, 100);
  };

  const stopWave = () => { // FIX: Stop waveform animation and reset bars to idle state
    if (animationRef.current) clearInterval(animationRef.current);
    setWaveValues(Array(14).fill(4)); // FIX: Reset all bars to minimal height
  };

  useEffect(() => {
    setAudioFailed(false); // FIX: Reset failure state when question changes
    setIsPlaying(false); // FIX: Reset playing state when question changes
    stopWave(); // FIX: Stop any active animation when question changes

    if (!audioUrl) {
      // FIX: No audio — auto-complete after short delay so user can read the question
      const t = setTimeout(() => onAudioComplete?.(), 1500);
      return () => clearTimeout(t);
    }

    let objectUrl = '';
    let audio: HTMLAudioElement | null = null;
    let timeoutId: ReturnType<typeof setTimeout>;

    const fetchAndPlayAudio = async () => {
      try {
        // FIX: Fetch the audio file using the API service to ensure the JWT token is attached.
        // Direct <audio src={audioUrl}> fails with 401 Unauthorized because FileController requires auth.
        const response = await api.get(audioUrl, { responseType: 'blob' });

        objectUrl = URL.createObjectURL(response.data);
        audio = new Audio(objectUrl);
        audioRef.current = audio;

        audio.onplay = () => { setIsPlaying(true); startWave(); }; // FIX: Start animation when audio begins playing
        audio.onended = () => { setIsPlaying(false); stopWave(); onAudioComplete?.(); }; // FIX: Stop animation and notify parent when audio ends
        audio.onerror = () => {
          // FIX: Graceful fallback — never block interview if audio fails
          setIsPlaying(false);
          setAudioFailed(true);
          stopWave();
          onAudioComplete?.(); // FIX: Still fire completion so recorder can appear
        };

        timeoutId = setTimeout(() => { // FIX: Slight delay before autoplay to let UI mount
          audio?.play().catch(() => {
            // FIX: Autoplay blocked by browser — show manual play button instead of failing
            setAudioFailed(false);
            setIsPlaying(false);
          });
        }, 400);

      } catch (error) {
        console.error("Failed to fetch audio file:", error);
        setIsPlaying(false);
        setAudioFailed(true);
        stopWave();
        onAudioComplete?.();
      }
    };

    fetchAndPlayAudio();

    return () => { // FIX: Cleanup on unmount — stop playback and animation
      clearTimeout(timeoutId);
      if (audio) {
        audio.pause();
        audio.src = '';
      }
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
      stopWave();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audioUrl, questionText]);

  const handleManualPlay = () => { // FIX: Manual play handler for when browser blocks autoplay
    audioRef.current?.play().catch(() => setAudioFailed(true));
  };

  return (
    <Paper elevation={3} sx={{
      background: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)', // FIX: Dark gradient background matching interview theme
      borderRadius: 3, p: 3, minHeight: 260,
      display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center', gap: 2,
      border: isPlaying ? '2px solid #4fc3f7' : '2px solid rgba(255,255,255,0.08)', // FIX: Highlight border when audio is playing
      transition: 'border-color 0.3s ease',
      position: 'relative',
    }}>
      {/* FIX: Question counter chip — shows current position in interview */}
      <Chip
        label={`Question ${questionNumber} of ${totalQuestions}`}
        size="small"
        icon={<MicIcon style={{ color: 'white' }} />}
        variant="outlined"
        sx={{
          color: 'white', borderColor: 'rgba(255,255,255,0.3)',
          position: 'absolute', top: 14, left: 14
        }}
      />

      {/* FIX: Animated waveform — visual indicator of TTS audio playback */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: '3px', height: 48, mt: 2 }}>
        {waveValues.map((h, i) => (
          <Box key={i} sx={{
            width: 4, borderRadius: 2,
            height: `${h}px`, // FIX: Dynamic height from waveValues state
            backgroundColor: isPlaying ? '#4fc3f7' : 'rgba(255,255,255,0.15)', // FIX: Blue when playing, dim when idle
            transition: 'height 0.1s ease, background-color 0.3s ease',
          }} />
        ))}
      </Box>

      {/* FIX: Status row — shows current audio playback state to user */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        {isPlaying
          ? <VolumeUpIcon sx={{ color: '#4fc3f7', fontSize: 20 }} /> // FIX: Active speaker icon when playing
          : <VolumeOffIcon sx={{ color: 'rgba(255,255,255,0.3)', fontSize: 20 }} /> // FIX: Muted icon when not playing
        }
        <Typography variant="caption" sx={{
          color: isPlaying ? '#4fc3f7' : 'rgba(255,255,255,0.4)' // FIX: Text color matches playback state
        }}>
          {isPlaying
            ? 'Interviewer is speaking...' // FIX: Status text during audio playback
            : audioFailed
              ? 'Audio unavailable — read the question below' // FIX: Fallback message when audio fails
              : audioUrl
                ? 'Click play to hear the question' // FIX: Prompt when audio is available but not playing
                : 'Read the question below'} {/* FIX: Prompt when no audio URL exists */}
        </Typography>
      </Box>

      {/* FIX: Manual play button — shown when autoplay is blocked by browser policy */}
      {audioUrl && !isPlaying && !audioFailed && (
        <Button size="small" variant="outlined"
          onClick={handleManualPlay} // FIX: Trigger manual audio playback on click
          startIcon={<VolumeUpIcon />}
          sx={{
            color: 'white', borderColor: 'rgba(255,255,255,0.3)',
            '&:hover': { borderColor: '#4fc3f7', color: '#4fc3f7' }
          }}>
          Play Question Audio
        </Button>
      )}

      {/* FIX: Question text displayed prominently below the waveform */}
      <Typography variant="body1" sx={{
        color: 'rgba(255,255,255,0.9)',
        textAlign: 'center',
        maxWidth: 500,
        lineHeight: 1.6,
        fontWeight: 500,
        mt: 1,
      }}>
        {questionText}
      </Typography>

      {/* FIX: Pulse animation keyframes for potential future use */}
      <style>{`@keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}`}</style>
    </Paper>
  );
};

export default QuestionPresenter; // FIX: Export QuestionPresenter as replacement for AvatarPlayer
