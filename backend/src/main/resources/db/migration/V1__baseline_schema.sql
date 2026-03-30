-- ═══════════════════════════════════════════════════════════════
-- V1__baseline_schema.sql
-- Flyway baseline migration for AI Interview Preparation Platform
-- ═══════════════════════════════════════════════════════════════
-- This migration captures the complete schema previously managed
-- by Hibernate ddl-auto=update. It uses CREATE TABLE IF NOT EXISTS
-- so it is safe to run against both:
--   (a) A fresh database (tables created from scratch)
--   (b) An existing database adopted via flyway.baseline-on-migrate
--
-- Column sizes and types are derived from the JPA entity annotations.
-- Where no explicit @Column(length=...) was specified, Hibernate's
-- default of VARCHAR(255) is used. URL columns are widened to
-- VARCHAR(2048) to accommodate S3 presigned URLs which can exceed
-- 255 characters.
-- ═══════════════════════════════════════════════════════════════

-- ─── Users ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    password        VARCHAR(255)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL,
    updated_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_email ON users (email);

-- ─── Resumes ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS resumes (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    file_name       VARCHAR(255)    NOT NULL,
    file_url        VARCHAR(2048)   NOT NULL,
    extracted_text  TEXT,
    uploaded_at     DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_resumes_user FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_resumes_user_id ON resumes (user_id);

-- ─── Job Roles (lookup table) ────────────────────────────────
CREATE TABLE IF NOT EXISTS job_roles (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    title       VARCHAR(100)    NOT NULL,
    description TEXT,
    category    VARCHAR(50),
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─── Interviews ──────────────────────────────────────────────
-- status values: CREATED, GENERATING_VIDEOS, IN_PROGRESS,
--                PROCESSING, COMPLETED, FAILED
-- type values:   TEXT, VIDEO
CREATE TABLE IF NOT EXISTS interviews (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    resume_id       BIGINT          NOT NULL,
    job_role_id     BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    type            VARCHAR(10)     NOT NULL,
    overall_score   INT,
    started_at      DATETIME(6)     NOT NULL,
    completed_at    DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_interviews_user FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_interviews_resume FOREIGN KEY (resume_id)
        REFERENCES resumes (id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    CONSTRAINT fk_interviews_job_role FOREIGN KEY (job_role_id)
        REFERENCES job_roles (id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_user_id ON interviews (user_id);
CREATE INDEX idx_status ON interviews (status);

-- ─── Questions ───────────────────────────────────────────────
-- avatar_video_url is VARCHAR(2048) because S3 presigned URLs
-- routinely exceed 255 characters. In the P1 migration this
-- column will be replaced with avatar_video_s3_key VARCHAR(512).
CREATE TABLE IF NOT EXISTS questions (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    interview_id        BIGINT          NOT NULL,
    question_text       TEXT            NOT NULL,
    question_number     INT             NOT NULL,
    category            VARCHAR(50),
    difficulty          VARCHAR(20),
    avatar_video_url    VARCHAR(2048),
    created_at          DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_questions_interview FOREIGN KEY (interview_id)
        REFERENCES interviews (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_questions_interview_id ON questions (interview_id);

-- ─── Responses ───────────────────────────────────────────────
-- video_url is VARCHAR(2048) for the same presigned URL reason.
CREATE TABLE IF NOT EXISTS responses (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    question_id                 BIGINT          NOT NULL,
    interview_id                BIGINT          NOT NULL,
    user_id                     BIGINT          NOT NULL,
    video_url                   VARCHAR(2048)   NOT NULL,
    transcription               TEXT,
    transcription_confidence    DOUBLE,
    video_duration              INT,
    responded_at                 DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_responses_question FOREIGN KEY (question_id)
        REFERENCES questions (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_responses_interview FOREIGN KEY (interview_id)
        REFERENCES interviews (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_responses_user FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_responses_interview_id ON responses (interview_id);

-- ─── Feedbacks ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS feedbacks (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    interview_id        BIGINT          NOT NULL UNIQUE,
    user_id             BIGINT          NOT NULL,
    overall_score       INT,
    strengths           TEXT,
    weaknesses          TEXT,
    recommendations     TEXT,
    detailed_analysis   TEXT,
    generated_at        DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_feedbacks_interview FOREIGN KEY (interview_id)
        REFERENCES interviews (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    CONSTRAINT fk_feedbacks_user FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_feedbacks_interview_id ON feedbacks (interview_id);

-- ─── TTS Audio Cache ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS tts_audio_cache (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    cache_key       VARCHAR(64)     NOT NULL UNIQUE,
    s3_key          VARCHAR(512)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_tts_cache_key UNIQUE (cache_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_tts_cache_key ON tts_audio_cache (cache_key);

-- ─── SSE Emitters ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sse_emitters (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    interview_id    BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    created_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_sse_interview_id ON sse_emitters (interview_id);
CREATE INDEX idx_sse_user_id ON sse_emitters (user_id);
