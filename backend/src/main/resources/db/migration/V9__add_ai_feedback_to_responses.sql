-- Add ai_feedback column to responses table to store per-question expected answers and feedback
ALTER TABLE responses ADD COLUMN ai_feedback TEXT NULL;
