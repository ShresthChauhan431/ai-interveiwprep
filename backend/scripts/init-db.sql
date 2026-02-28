-- ═══════════════════════════════════════════════════════════════
-- AI Interview Platform — MySQL database initialization
-- ═══════════════════════════════════════════════════════════════
-- Run as MySQL root (or admin) before first backend startup.
-- Adjust DB_NAME, DB_USER, DB_PASSWORD to match your .env.
--
-- Example: mysql -u root -p < backend/scripts/init-db.sql
-- ═══════════════════════════════════════════════════════════════

CREATE DATABASE IF NOT EXISTS interview_platform
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Create application user (match DB_USER/DB_PASSWORD in .env)
CREATE USER IF NOT EXISTS 'user'@'localhost' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON interview_platform.* TO 'user'@'localhost';
FLUSH PRIVILEGES;

-- Flyway will create and migrate tables on first backend run.
