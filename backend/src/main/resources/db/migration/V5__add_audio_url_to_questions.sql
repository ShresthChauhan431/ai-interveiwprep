-- ═══════════════════════════════════════════════════════════════
-- V5__add_audio_url_to_questions.sql
-- FIX: Add audio_url and tts_generated columns with IF NOT EXISTS guards so migration is safe to re-run
-- ═══════════════════════════════════════════════════════════════

-- FIX: Add audio_url column for ElevenLabs TTS audio (replaces D-ID avatar videos) — guarded with IF NOT EXISTS
ALTER TABLE questions ADD COLUMN IF NOT EXISTS audio_url VARCHAR(500);

-- FIX: Add tts_generated flag to track whether TTS audio has been generated for this question — guarded with IF NOT EXISTS
ALTER TABLE questions ADD COLUMN IF NOT EXISTS tts_generated BOOLEAN DEFAULT FALSE;
