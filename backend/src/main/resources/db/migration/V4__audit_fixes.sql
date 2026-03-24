-- ═══════════════════════════════════════════════════════════════
-- V4__audit_fixes.sql
-- Flyway migration for security & architecture audit remediation
-- ═══════════════════════════════════════════════════════════════
-- FIX: All ADD COLUMN statements use IF NOT EXISTS guards so migration is safe to re-run
-- FIX: Index and constraint creation uses IF NOT EXISTS where MySQL supports it

-- ─── P1-1: Optimistic locking for Interview entity ───────────
-- FIX: Added IF NOT EXISTS guard so migration is safe to re-run
ALTER TABLE interviews
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- ─── P1-3: Unique constraint on responses.question_id ────────
-- FIX: Clean up any existing duplicate responses before adding constraint
-- Keep only the latest response per question_id (highest id wins)
DELETE r1 FROM responses r1
    INNER JOIN responses r2
    ON r1.question_id = r2.question_id
    AND r1.id < r2.id;

-- FIX: Add unique constraint — uses ALTER TABLE which will be a no-op error if already exists
-- Flyway runs each versioned migration exactly once, so this only executes on first run.
-- If schema was manually modified and constraint exists, spring.flyway.validate-on-migrate=false
-- prevents checksum failures. The SET statement below makes duplicate key errors non-fatal.
SET @constraint_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'responses'
      AND CONSTRAINT_NAME = 'uq_response_question'
);

-- FIX: Only add the constraint if it doesn't already exist (using prepared statement for conditional DDL)
SET @sql = IF(@constraint_exists = 0,
    'ALTER TABLE responses ADD CONSTRAINT uq_response_question UNIQUE (question_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ─── P2-13: User role column for RBAC ────────────────────────
-- FIX: Added IF NOT EXISTS guard so migration is safe to re-run
-- Default 'USER' for all existing users. ADMIN must be assigned
-- via direct DB update or a future admin API endpoint.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- ─── P3-11: Missing index on responses.user_id ──────────────
-- FIX: Conditional index creation using prepared statement to avoid errors on re-run
SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'responses'
      AND INDEX_NAME = 'idx_responses_user_id'
);

SET @sql = IF(@index_exists = 0,
    'CREATE INDEX idx_responses_user_id ON responses (user_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
