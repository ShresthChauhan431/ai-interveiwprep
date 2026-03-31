-- ═══════════════════════════════════════════════════════════════
-- V7__responses_indexes_and_constraints.sql
-- Add missing indexes and constraints for data integrity and performance
-- ═══════════════════════════════════════════════════════════════

-- ─── Index on responses.user_id ─────────────────────────────
-- Performance improvement for queries fetching all responses by user
-- Idempotent: skip if already exists
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'responses' 
                AND INDEX_NAME = 'idx_responses_user_id');
SET @sqlstmt := IF(@exist > 0, 'SELECT ''Index already exists.''',
                   'CREATE INDEX idx_responses_user_id ON responses (user_id)');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ─── Unique constraint on responses.question_id ───────────────
-- Each question should have exactly one response (one video submission)
-- This enforces the business logic that a candidate cannot submit multiple videos for the same question
-- Idempotent: skip if already exists
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'responses'
                AND CONSTRAINT_NAME = 'uq_responses_question_id');
SET @sqlstmt := IF(@exist > 0, 'SELECT ''Constraint already exists.''',
                   'ALTER TABLE responses ADD CONSTRAINT uq_responses_question_id UNIQUE (question_id)');
PREPARE stmt FROM @sqlstmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
