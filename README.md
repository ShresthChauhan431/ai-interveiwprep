# 🤖 AI Interview Preparation Platform

![Project Icon](project_icon.png)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Backend](https://img.shields.io/badge/Backend-Spring%20Boot%203.2-green)](https://spring.io/projects/spring-boot)
[![Frontend](https://img.shields.io/badge/Frontend-React%2019-blue)](https://react.dev/)
[![Java](https://img.shields.io/badge/Java-21+-orange)](https://openjdk.org/)

## 🚀 Overview

The **AI Interview Preparation Platform** is a full-stack application that simulates real-world technical interviews. It leverages a local LLM (Ollama/Llama 3) for intelligent question generation and feedback analysis, combined with cloud APIs for avatar video, text-to-speech, and speech-to-text — providing an immersive, end-to-end interview practice experience.

### AI & Integration Stack

| Service | Provider | Purpose |
|---------|----------|---------|
| **Question Generation & Feedback** | [Ollama](https://ollama.com/) (Llama 3) | Context-aware question generation from resumes; structured interview feedback |
| **Avatar Videos** | [D-ID](https://www.d-id.com/) | Lifelike AI avatar that "asks" each question on camera |
| **Text-to-Speech** | [ElevenLabs](https://elevenlabs.io/) | Natural-sounding voice audio for avatar videos |
| **Speech-to-Text** | [AssemblyAI](https://www.assemblyai.com/) | Transcription of candidate video responses for AI analysis |

## ✨ Key Features

- **🧠 Intelligent Questioning** — Tailored questions based on job roles and parsed resume content via Llama 3.
- **🗣️ Realistic AI Avatars** — Interactive interviews with lifelike D-ID avatars speaking with ElevenLabs voices.
- **🎥 Video Response Recording** — In-browser video/audio recording with auto-start countdown, camera preview, and progress tracking.
- **📊 Comprehensive Feedback** — AI-generated performance analytics with scores, strengths, weaknesses, and recommendations.
- **📄 Resume Parsing** — Automatic text extraction from PDF (Apache PDFBox) and DOCX (Apache POI) resumes.
- **📈 Interview History** — Dashboard to review past interviews, watch recordings, and track growth over time.
- **🔐 JWT Authentication** — Secure user registration and login with token-based auth.
- **⚡ Resilient Architecture** — Circuit breakers, retry policies (Resilience4j), and rate limiting (Bucket4j) for external API calls.
- **🔄 Recovery System** — Scheduled task automatically recovers stuck interviews (e.g., avatar generation timeouts).

## 🛠️ Technology Stack

### Backend

| Category | Technology |
|----------|-----------|
| **Framework** | Spring Boot 3.2.2 (Java 21, Virtual Threads enabled) |
| **Database** | MySQL 8.0 with Hibernate/JPA |
| **Migrations** | Flyway (baseline-on-migrate, versioned SQL scripts) |
| **Security** | Spring Security + JWT (jjwt 0.11.5) |
| **Caching** | Caffeine (in-memory cache for avatar videos & TTS audio) |
| **Resilience** | Resilience4j (circuit breaker + retry for external APIs) |
| **Rate Limiting** | Bucket4j (token-bucket per-user limiting on expensive endpoints) |
| **File Storage** | Local filesystem (configurable path, no AWS dependency) |
| **Real-time** | Server-Sent Events (SSE) for avatar generation progress |
| **Document Parsing** | Apache PDFBox 2.0, Apache POI 5.2 |
| **Testing** | JUnit 5, Mockito, H2 (in-memory test DB), Spring Security Test |

### Frontend

| Category | Technology |
|----------|-----------|
| **Library** | React 19 with TypeScript |
| **UI Framework** | Material-UI (MUI) v7 with custom light/dark theme |
| **State Management** | Zustand (interview store) + React Context (auth) |
| **Routing** | React Router v7 |
| **HTTP Client** | Axios with interceptors (JWT auto-attach, 401 redirect) |
| **Forms** | React Hook Form |
| **Video** | MediaRecorder API (recording), React Player (playback) |

## 📁 Project Structure

```
ai-interveiwprep/
├── backend/
│   ├── src/main/java/com/interview/platform/
│   │   ├── config/          # Security, CORS, JWT, Resilience4j, Cache, Rate Limiting
│   │   ├── controller/      # Auth, Interview, Resume, JobRole, File controllers
│   │   ├── dto/             # Request/response DTOs
│   │   ├── event/           # Spring events (avatar pipeline)
│   │   ├── exception/       # Global exception handler + custom exceptions
│   │   ├── model/           # JPA entities (User, Interview, Question, Response, etc.)
│   │   ├── repository/      # Spring Data JPA repositories
│   │   ├── service/         # Business logic (Interview, Ollama, TTS, Avatar, STT, etc.)
│   │   └── task/            # Scheduled tasks (interview recovery)
│   ├── src/main/resources/
│   │   ├── db/migration/    # Flyway SQL migrations (V1–V3)
│   │   ├── application.properties
│   │   └── application-dev.properties
│   ├── scripts/init-db.sql  # MySQL database initialization
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── AIAvatar/    # Avatar video player
│   │   │   ├── Auth/        # Login, Register
│   │   │   ├── Common/      # Navigation, LoadingSpinner, ErrorMessage, etc.
│   │   │   ├── Dashboard/   # User dashboard
│   │   │   ├── Interview/   # InterviewStart, InterviewRoom, InterviewComplete, etc.
│   │   │   ├── LandingPage/ # Hero, Features, FAQ, Pricing, Testimonials, etc.
│   │   │   └── VideoRecorder/ # Camera preview, MediaRecorder integration
│   │   ├── context/         # AuthContext (JWT + user state)
│   │   ├── hooks/           # useAuth, useVideoRecording, useMediaPermissions, etc.
│   │   ├── services/        # API service, auth service, interview service, video service
│   │   ├── stores/          # Zustand stores (useInterviewStore)
│   │   ├── types/           # TypeScript interfaces and types
│   │   ├── utils/           # Constants, validators
│   │   └── theme.ts         # MUI theme with light/dark palettes
│   ├── package.json
│   └── tsconfig.json
├── docker-compose.yml        # MySQL 8.0 container
├── setup.sh                  # Automated first-time setup
├── start.sh                  # Start all services (Ollama, backend, frontend)
├── stop.sh                   # Stop all services
├── configure-api-keys.sh     # Interactive API key configuration helper
├── SETUP_GUIDE.md            # Detailed step-by-step setup instructions
├── QUICK_START.md            # Quick reference commands
├── DEPLOYMENT.md             # Production deployment guide
└── FIXES_APPLIED.md          # Changelog of fixes and improvements
```

## 🚀 Getting Started

### Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| **Java** | 21+ | Backend runtime (virtual threads) |
| **Node.js** | 18+ | Frontend build & dev server |
| **MySQL** | 8.0 | Relational database |
| **Ollama** | Latest | Local LLM for question generation & feedback |
| **Maven** | 3.8+ | Backend build (or use included `./mvnw` wrapper) |

### Quick Setup (Automated)

```bash
# 1. Clone the repository
git clone https://github.com/RavenRepo/ai-interveiwprep.git
cd ai-interveiwprep

# 2. Run the automated setup (checks prerequisites, sets up DB, installs deps)
./setup.sh

# 3. Configure your API keys interactively
./configure-api-keys.sh

# 4. Start all services (Ollama, Backend, Frontend)
./start.sh

# 5. Open the app
open http://localhost:3002
```

### Manual Setup

#### 1. Database

Using Docker Compose (recommended):

```bash
docker-compose up -d
```

Or manually create the database:

```bash
mysql -u root -p < backend/scripts/init-db.sql
```

#### 2. Ollama (Local LLM)

```bash
# Install (macOS)
brew install ollama

# Pull the Llama 3 model
ollama pull llama3

# Start the server
ollama serve
```

#### 3. Backend

```bash
cd backend

# Create .env from template and fill in your credentials
cp .env.example .env
# Edit .env → set DB_USER, DB_PASSWORD, API keys, JWT_SECRET

# Run (loads .env automatically)
./mvnw spring-boot:run
```

The backend starts on **http://localhost:8081**. Flyway automatically runs database migrations on first startup.

#### 4. Frontend

```bash
cd frontend
npm install
npm start
```

The frontend starts on **http://localhost:3002**.

### Service URLs

| Service | URL | Health Check |
|---------|-----|-------------|
| **Frontend** | http://localhost:3002 | — |
| **Backend API** | http://localhost:8081 | `GET /actuator/health` |
| **Ollama** | http://localhost:11434 | `GET /api/tags` |
| **MySQL** | localhost:3306 | — |

## 🔑 API Keys Required

The following external API keys must be configured in `backend/.env`:

| API | Environment Variable | Get Key From |
|-----|---------------------|-------------|
| **ElevenLabs** (TTS) | `ELEVENLABS_API_KEY` | https://elevenlabs.io/ → Profile → API Keys |
| **D-ID** (Avatar) | `DID_API_KEY` | https://www.d-id.com/ → API Keys |
| **AssemblyAI** (STT) | `ASSEMBLYAI_API_KEY` | https://www.assemblyai.com/ → Dashboard → API Keys |

> **Note:** Ollama runs locally — no API key needed. Question generation and feedback analysis are completely free and private.

## 🔄 Interview Flow

```
1. Register/Login
       │
2. Upload Resume (PDF/DOCX)
       │  ← Text extraction (PDFBox / POI)
       │
3. Select Job Role + Start Interview
       │  ← Ollama generates tailored questions
       │  ← ElevenLabs converts questions to speech
       │  ← D-ID generates avatar videos
       │  ← SSE streams progress to frontend
       │
4. Interview Session (IN_PROGRESS)
       │  ← Watch avatar ask question
       │  ← Record video response (MediaRecorder)
       │  ← Upload video → trigger transcription (AssemblyAI)
       │
5. Complete Interview
       │  ← Ollama analyzes transcriptions
       │  ← Generates scores, strengths, weaknesses, recommendations
       │
6. Review Feedback & History
```

### Interview Status State Machine

```
CREATED → GENERATING_VIDEOS → IN_PROGRESS → PROCESSING → COMPLETED
              │                    │              │
              └────────────────────┴──────────────┴──→ FAILED
```

## 🧪 Running Tests

### Backend

```bash
cd backend
./mvnw test
```

Tests use H2 in-memory database — no MySQL required. Test coverage includes:
- `InterviewServiceTest` — interview lifecycle and business logic
- `UserServiceTest` — registration and authentication
- `VideoStorageServiceTest` — file storage operations
- `CachedAvatarServiceTest` — caching behavior
- `AvatarPipelineListenerTest` — event-driven avatar generation
- `InterviewRecoveryTaskTest` — stuck interview recovery

### Frontend

```bash
cd frontend
npm test
```

## 📜 Helper Scripts

| Script | Description |
|--------|-------------|
| `./setup.sh` | First-time setup: checks prerequisites, installs Ollama, creates DB, compiles backend, installs frontend deps |
| `./start.sh` | Starts Ollama, backend, and frontend; waits for readiness; opens browser |
| `./stop.sh` | Stops all running services by port (8081, 3002); optionally stops Ollama |
| `./configure-api-keys.sh` | Interactive wizard to set ElevenLabs, D-ID, AssemblyAI keys and MySQL password |

## ⚙️ Configuration Reference

### Backend (`backend/.env`)

```bash
# Database
DB_HOST=localhost
DB_PORT=3306
DB_NAME=interview_platform
DB_USER=user
DB_PASSWORD=password

# JWT
JWT_SECRET=<generate with: openssl rand -base64 64>
JWT_EXPIRATION=3600000

# External APIs
ELEVENLABS_API_KEY=your_key_here
DID_API_KEY=your_key_here
ASSEMBLYAI_API_KEY=your_key_here

# Ollama
OLLAMA_API_URL=http://localhost:11434/api/chat
OLLAMA_MODEL=llama3

# Server
SERVER_PORT=8081
CORS_ALLOWED_ORIGINS=http://localhost:3002

# Storage
STORAGE_PATH=./uploads

# Rate Limiting
RATE_LIMIT_CAPACITY=5
RATE_LIMIT_REFILL_TOKENS=5
RATE_LIMIT_REFILL_SECONDS=60
```

### Frontend

The frontend reads `REACT_APP_API_URL` from environment (defaults to `http://localhost:8080`). For local development with the default backend port:

```bash
# frontend/.env
REACT_APP_API_URL=http://localhost:8081
```

## 🔒 Security

- **JWT Authentication** — Stateless token-based auth with configurable expiration.
- **No Hardcoded Secrets** — All secrets via environment variables; backend fails fast if required vars are missing.
- **Rate Limiting** — Token-bucket rate limiting on expensive endpoints (`POST /api/interviews/start`, `/complete`, `/api/resumes/upload`) to prevent API cost abuse.
- **Circuit Breakers** — Resilience4j protects against cascading failures when external APIs (D-ID, ElevenLabs, AssemblyAI) are degraded.
- **CORS** — Configurable allowed origins; no wildcard in production.
- **Actuator Lockdown** — Only `/actuator/health` exposed publicly; detailed info requires ADMIN role.
- **Flyway Migrations** — Schema managed by versioned SQL migrations; `ddl-auto=validate` in production.

## 🐛 Known Limitations

1. **Avatar Generation Time** — D-ID can take 30–60 seconds per video. A progress bar shows real-time status via SSE. Interviews fall back to text-only mode after 15 minutes.
2. **Transcription Accuracy** — Depends on audio quality. AssemblyAI provides confidence scores. Feedback gracefully handles missing transcriptions.
3. **API Costs** — D-ID (~$0.05/video), ElevenLabs, and AssemblyAI have usage-based pricing. Rate limiting prevents runaway costs.
4. **Local Storage** — Files are stored on the local filesystem by default (no S3). For production, configure external storage or migrate to presigned S3 URLs.

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [SETUP_GUIDE.md](SETUP_GUIDE.md) | Detailed step-by-step setup with troubleshooting |
| [QUICK_START.md](QUICK_START.md) | Quick reference commands and checklists |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Production deployment guide (env vars, HTTPS, CORS, logging) |
| [FIXES_APPLIED.md](FIXES_APPLIED.md) | Changelog of all fixes and improvements applied |

## 🤝 Contributing

We welcome contributions! Please feel free to fork the repository and submit a Pull Request.

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License.