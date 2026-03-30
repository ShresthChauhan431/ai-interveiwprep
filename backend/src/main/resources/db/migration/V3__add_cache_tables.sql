-- ============================================================
-- V3: Cache tables for avatar video and TTS audio
-- ============================================================
-- These tables persist cache entries across application restarts.
-- The in-memory Caffeine cache sits in front of these tables;
-- on Caffeine miss, the DB is checked before calling external APIs.
--
-- cache_key: SHA-256 hex (64 chars) of normalized input + config
-- s3_key:    S3 object key where the cached artifact is stored

CREATE TABLE IF NOT EXISTS avatar_video_cache (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    cache_key  VARCHAR(64)  NOT NULL,
    s3_key     VARCHAR(512) NOT NULL,
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_avatar_cache_key UNIQUE (cache_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tts_audio_cache (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    cache_key  VARCHAR(64)  NOT NULL,
    s3_key     VARCHAR(512) NOT NULL,
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_tts_cache_key UNIQUE (cache_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
