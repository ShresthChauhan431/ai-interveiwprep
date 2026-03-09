-- ═══════════════════════════════════════════════════════════════
-- V4__audit_fixes.sql
-- Flyway migration for security & architecture audit remediation
-- ═══════════════════════════════════════════════════════════════
-- This migration addresses the following audit findings:
--
--   P1-1:  Add `version` column to `interviews` for JPA @Version
--          optimistic locking. Prevents race conditions between the
--          AvatarPipelineListener and InterviewRecoveryTask when
--          both attempt to mutate interview status concurrently.
--
--   P1-3:  Add UNIQUE constraint on `responses.question_id`.
--          Prevents duplicate response rows caused by network
--          retries racing past the application-level check in
--          InterviewService.confirmUpload().
--
--   P2-13: Add `role` column to `users` for role-based access
--          control (RBAC). The SecurityConfig references
--          `.hasRole("ADMIN")` for actuator endpoints, but
--          previously no user could ever have any role because
--          JwtAuthenticationFilter created auth tokens with
--          Collections.emptyList(). Now the role is persisted,
--          included as a JWT claim, and used to populate
--          GrantedAuthority in the filter.
--
--   P3-11: Add index on `responses.user_id`. While no current
--          query filters by this column alone, it's a foreign key
--          and the JPA entity has a @ManyToOne to User. Any future
--          query joining responses by user would otherwise be a
--          full table scan.
-- ═══════════════════════════════════════════════════════════════

-- ─── P1-1: Optimistic locking for Interview entity ───────────
-- The @Version annotation in Interview.java requires a `version`
-- column. JPA increments this value on every UPDATE and includes
-- `WHERE version = ?` in the UPDATE statement. If a concurrent
-- transaction has already incremented the version, the update
-- affects 0 rows and JPA throws OptimisticLockException.
--
-- Default 0 for existing rows so they can be updated immediately.
ALTER TABLE interviews
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- ─── P1-3: Unique constraint on responses.question_id ────────
-- Prevents duplicate response rows for the same question.
-- Before adding the constraint, clean up any existing duplicates
-- by keeping only the latest response per question_id.
-- (In practice there should be none, but this is defensive.)
DELETE r1 FROM responses r1
    INNER JOIN responses r2
    ON r1.question_id = r2.question_id
    AND r1.id < r2.id;

ALTER TABLE responses
    ADD CONSTRAINT uq_response_question UNIQUE (question_id);

-- ─── P2-13: User role column for RBAC ────────────────────────
-- Default 'USER' for all existing users. ADMIN must be assigned
-- via direct DB update or a future admin API endpoint.
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- ─── P3-11: Missing index on responses.user_id ──────────────
-- Foreign key column without an index. Any JOIN or WHERE on
-- user_id would scan the full responses table.
CREATE INDEX idx_responses_user_id ON responses (user_id);
