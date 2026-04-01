-- V10: Add per-question score column to responses table
-- Supports strict per-question scoring in feedback generation

ALTER TABLE responses ADD COLUMN score INTEGER COMMENT 'Per-question score (0-100) from AI evaluation. 0 indicates no answer or completely irrelevant response.';

-- Add index for querying responses by score (analytics, filtering)
CREATE INDEX idx_responses_score ON responses(score);
