import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Box, Typography, Paper, Chip, Button } from '@mui/material';
import VolumeUpIcon from '@mui/icons-material/VolumeUp';
import VolumeOffIcon from '@mui/icons-material/VolumeOff';
import MicIcon from '@mui/icons-material/Mic';

import api from '../../services/api.service';

const RobotIcon: React.FC<{ size?: number }> = ({ size = 100 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 120 120"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <rect x="30" y="20" width="60" height="50" rx="8" fill="#4A90D9" />
    <circle cx="45" cy="40" r="8" fill="#00FF88" />
    <circle cx="75" cy="40" r="8" fill="#00FF88" />
    <rect x="45" y="52" width="30" height="8" rx="4" fill="#1a1a2e" />
    <rect x="57" y="8" width="6" height="15" fill="#4A90D9" />
    <circle cx="60" cy="8" r="5" fill="#FF6B6B" />
    <rect x="20" y="35" width="12" height="20" rx="4" fill="#3A7BC8" />
    <rect x="88" y="35" width="12" height="20" rx="4" fill="#3A7BC8" />
    <rect x="50" y="70" width="20" height="8" fill="#2D5A8A" />
    <rect x="25" y="78" width="70" height="35" rx="6" fill="#4A90D9" />
    <rect x="35" y="85" width="50" height="20" rx="4" fill="#3A7BC8" />
    <circle cx="50" cy="95" r="5" fill="#00FF88" />
    <circle cx="70" cy="95" r="5" fill="#FFD93D" />
    <circle cx="60" cy="105" r="4" fill="#FF6B6B" />
  </svg>
);

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
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const animationRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const synthRef = useRef<SpeechSynthesisUtterance | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [audioFailed, setAudioFailed] = useState(false);
  const [useBrowserTTS, setUseBrowserTTS] = useState(false);
  const [waveValues, setWaveValues] = useState<number[]>(Array(14).fill(4));

  const startWave = () => {
    animationRef.current = setInterval(() => {
      setWaveValues(Array(14).fill(0).map(() => Math.floor(Math.random() * 28) + 4));
    }, 100);
  };

  const stopWave = () => {
    if (animationRef.current) clearInterval(animationRef.current);
    setWaveValues(Array(14).fill(4));
  };

  const stopBrowserTTS = useCallback(() => {
    if (window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
    synthRef.current = null;
  }, []);

  const speakWithBrowserTTS = useCallback((text: string) => {
    stopBrowserTTS();
    
    if (!window.speechSynthesis) {
      console.warn("Browser speech synthesis not supported");
      onAudioComplete?.();
      return;
    }

    const utterance = new SpeechSynthesisUtterance(text);
    utterance.rate = 0.9;
    utterance.pitch = 1;
    utterance.lang = 'en-US';
    
    const voices = window.speechSynthesis.getVoices();
    const englishVoice = voices.find(v => v.lang.startsWith('en') && v.name.includes('Female')) 
      || voices.find(v => v.lang.startsWith('en'));
    if (englishVoice) {
      utterance.voice = englishVoice;
    }

    utterance.onstart = () => {
      setIsPlaying(true);
      startWave();
    };

    utterance.onend = () => {
      setIsPlaying(false);
      stopWave();
      onAudioComplete?.();
    };

    utterance.onerror = (e) => {
      console.error("Browser TTS error:", e);
      setIsPlaying(false);
      setAudioFailed(true);
      stopWave();
      onAudioComplete?.();
    };

    synthRef.current = utterance;
    window.speechSynthesis.speak(utterance);
  }, [onAudioComplete, stopBrowserTTS]);

  useEffect(() => {
    setAudioFailed(false);
    setIsPlaying(false);
    setUseBrowserTTS(false);
    stopWave();
    stopBrowserTTS();

    if (!audioUrl) {
      const t = setTimeout(() => speakWithBrowserTTS(questionText), 500);
      return () => {
        clearTimeout(t);
        stopBrowserTTS();
      };
    }

    let objectUrl = '';
    let audio: HTMLAudioElement | null = null;
    let timeoutId: ReturnType<typeof setTimeout>;

    const fetchAndPlayAudio = async () => {
      try {
        const response = await api.get(audioUrl, { responseType: 'blob' });

        objectUrl = URL.createObjectURL(response.data);
        audio = new Audio(objectUrl);
        audioRef.current = audio;

        audio.onplay = () => { setIsPlaying(true); startWave(); };
        audio.onended = () => { setIsPlaying(false); stopWave(); onAudioComplete?.(); };
        audio.onerror = () => {
          setIsPlaying(false);
          setAudioFailed(true);
          stopWave();
          speakWithBrowserTTS(questionText);
        };

        timeoutId = setTimeout(() => {
          audio?.play().catch(() => {
            setAudioFailed(true);
            speakWithBrowserTTS(questionText);
          });
        }, 400);

      } catch (error) {
        console.error("Failed to fetch audio file:", error);
        setAudioFailed(true);
        speakWithBrowserTTS(questionText);
      }
    };

    fetchAndPlayAudio();

    return () => {
      clearTimeout(timeoutId);
      if (audio) {
        audio.pause();
        audio.src = '';
      }
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
      stopBrowserTTS();
      stopWave();
    };
  }, [audioUrl, questionText, onAudioComplete, speakWithBrowserTTS, stopBrowserTTS]);

  const handleManualPlay = () => {
    if (useBrowserTTS) {
      speakWithBrowserTTS(questionText);
    } else if (audioRef.current) {
      audioRef.current.play().catch(() => {
        setAudioFailed(true);
        speakWithBrowserTTS(questionText);
      });
    }
  };

  const getStatusText = () => {
    if (isPlaying) {
      return useBrowserTTS ? 'Speaking via browser TTS...' : 'Interviewer is speaking...';
    }
    if (audioFailed) {
      return 'Audio unavailable — using browser TTS';
    }
    if (audioUrl) {
      return 'Click play to hear the question';
    }
    return 'Click play to hear the question';
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
      {/* Question counter chip — shows current position in interview */}
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

      {/* Robot Avatar - Always displayed */}
      <Box sx={{ mb: 1 }}>
        <RobotIcon size={100} />
      </Box>

      {/* Animated waveform — visual indicator of TTS audio playback */}
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

      {/* Status row — shows current audio playback state to user */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        {isPlaying
          ? <VolumeUpIcon sx={{ color: '#4fc3f7', fontSize: 20 }} />
          : <VolumeOffIcon sx={{ color: 'rgba(255,255,255,0.3)', fontSize: 20 }} />
        }
        <Typography variant="caption" sx={{
          color: isPlaying ? '#4fc3f7' : 'rgba(255,255,255,0.4)'
        }}>
          {getStatusText()}
        </Typography>
      </Box>

      {/* Manual play button */}
      {(!isPlaying) && (
        <Button size="small" variant="outlined"
          onClick={handleManualPlay}
          startIcon={<VolumeUpIcon />}
          sx={{
            color: 'white', borderColor: 'rgba(255,255,255,0.3)',
            '&:hover': { borderColor: '#4fc3f7', color: '#4fc3f7' }
          }}>
          {audioFailed ? 'Play with Browser TTS' : 'Play Question Audio'}
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
