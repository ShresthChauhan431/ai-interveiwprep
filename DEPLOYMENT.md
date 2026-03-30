# Production Deployment Guide

This document covers production readiness: build, environment, secrets, database, CORS, and logging.

## Prerequisites

- **Java 21+** (backend)
- **Node.js 18+** and npm (frontend)
- **MySQL 8.0** (database)
- **Maven** (or use `./mvnw` in backend)

## 1. Database

### Initialize database (once)

```bash
# From project root; adjust user/password if needed
mysql -u root -p < backend/scripts/init-db.sql
```

Or manually:

- Create database: `interview_platform` (utf8mb4).
- Create a dedicated user and grant full access to that database.
- Set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` in backend environment (see below).

Flyway runs on backend startup and applies migrations under `backend/src/main/resources/db/migration/`. Do **not** set `JPA_DDL_AUTO=update` in production; keep it as `validate` (default).

## 2. Backend configuration

### Environment variables (no defaults for secrets)

Required (backend fails to start if missing):

- `DB_USER`, `DB_PASSWORD` — MySQL credentials
- `JWT_SECRET` — e.g. `openssl rand -base64 64`
- `ELEVENLABS_API_KEY`, `DID_API_KEY` (if fallback used), `ASSEMBLYAI_API_KEY` — API keys

Optional (with defaults):

- `DB_HOST=localhost`, `DB_PORT=3306`, `DB_NAME=interview_platform`
- `SERVER_PORT=8080`
- `CORS_ALLOWED_ORIGINS=http://localhost:3000` — **must be set in production** to your frontend origin(s), e.g. `https://your-app.com`
- `STORAGE_PATH=./uploads`, `APP_BASE_URL=http://localhost:8080` — in production set `APP_BASE_URL` to the public backend URL
- `JWT_EXPIRATION=3600000` (ms)
- `JPA_DDL_AUTO=validate` (never `update` in production)

### Running the backend locally (development)

From `backend/`:

```bash
set -a && source .env && set +a && ./mvnw spring-boot:run
```

For production, inject the variables above via your orchestrator (e.g. Docker env, Kubernetes secrets, or a secure env file) and **do not** commit `.env` or any file containing secrets.

## 3. Frontend configuration

### Development

- `frontend/.env`: `REACT_APP_API_URL=http://localhost:8080` (or your backend URL).

### Production build

1. Set `REACT_APP_API_URL` to your **production backend URL** (e.g. `https://api.your-app.com`). No trailing slash.
2. Build:

   ```bash
   cd frontend
   npm run build
   ```

3. Serve the `build/` folder with HTTPS (e.g. Nginx, CDN, or static host). Ensure the same backend URL is used at runtime (env is baked into the build).

Example production env file (do not commit real values):

```bash
# frontend/.env.production (or CI/CD secret)
REACT_APP_API_URL=https://api.your-app.com
REACT_APP_MAX_VIDEO_SIZE_MB=50
REACT_APP_MAX_RECORDING_DURATION=180
```

## 4. CORS

- Backend reads `CORS_ALLOWED_ORIGINS` (comma-separated). In production set it to your frontend origin(s), e.g. `https://your-app.com`.
- Allowed methods: GET, POST, PUT, DELETE, PATCH, OPTIONS. Credentials allowed. No wildcard origin in production.

## 5. Secrets

- **Never** commit `.env` or any file containing API keys or `JWT_SECRET`.
- Use a secrets manager or CI/CD secrets in production; inject as environment variables.
- Rotate `JWT_SECRET` if it was ever committed or shared; existing tokens will be invalidated.

## 6. Logging

- Default: `logging.level.root=INFO`, `logging.level.com.interview.platform=INFO`.
- For troubleshooting: set `LOG_HIBERNATE_SQL=DEBUG` temporarily (do not leave on in production).
- In production, send logs to your aggregator (e.g. JSON logging and a log pipeline).

## 7. Health and readiness

- Backend exposes `/actuator/health` (public). Use it for load balancer health checks.
- Other actuator endpoints are restricted to `ADMIN` role and `show-details=when_authorized`.

## 8. Checklist before go-live

- [ ] Database created and migrated (Flyway); `JPA_DDL_AUTO=validate`.
- [ ] All required env vars set in production (DB, JWT, API keys).
- [ ] `CORS_ALLOWED_ORIGINS` set to production frontend origin(s).
- [ ] `APP_BASE_URL` set to production backend URL (for file URLs).
- [ ] Frontend built with correct `REACT_APP_API_URL`.
- [ ] HTTPS for frontend and backend; no secrets in client code or repo.
- [ ] Logging level and retention suitable for production.
