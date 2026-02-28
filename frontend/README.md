# AI Interview Preparation Platform — Frontend

React TypeScript frontend for the AI-powered interview preparation platform.

## Prerequisites

- Node.js 18+
- npm 9+
- Backend running on `http://localhost:8080`

## Quick Start

```bash
# Install dependencies
npm install

# Start development server
npm start
```

The app runs at [http://localhost:3000](http://localhost:3002).

## Environment Variables

Copy `.env` and update values as needed:

| Variable | Default | Description |
|----------|---------|-------------|
| `REACT_APP_API_URL` | `http://localhost:8080` | Backend API base URL |
| `REACT_APP_MAX_VIDEO_SIZE_MB` | `50` | Max video upload size (MB) |
| `REACT_APP_MAX_RECORDING_DURATION` | `180` | Max recording duration (seconds) |

## Project Structure

```
src/
├── components/          # React components
│   ├── Auth/            # Login, Register
│   ├── Dashboard/       # Main dashboard
│   ├── Interview/       # Interview session
│   ├── AIAvatar/        # AI avatar video player
│   ├── VideoRecorder/   # Video recording
│   └── Common/          # Shared UI components
├── services/            # API service layer
│   ├── api.service.ts   # Axios instance + interceptors
│   ├── auth.service.ts  # Authentication APIs
│   ├── interview.service.ts  # Interview APIs
│   └── video.service.ts # Video/resume APIs
├── hooks/               # Custom React hooks
│   ├── useAuth.ts       # Auth context hook
│   ├── useVideoRecording.ts  # MediaRecorder hook
│   └── useMediaPermissions.ts  # Camera/mic permissions
├── types/               # TypeScript type definitions
│   └── index.ts
├── utils/               # Utilities
│   ├── constants.ts     # App-wide constants
│   └── validators.ts    # Form validation
├── context/             # React contexts
│   └── AuthContext.tsx   # Auth state management
└── App.tsx              # Root component with routing
```

## Tech Stack

- **React 19** with TypeScript
- **Material UI** (MUI v6) for components
- **React Router v7** for routing
- **Axios** for HTTP requests
- **React Hook Form** for form management
- **React Player** for video playback

## Available Scripts

| Command | Description |
|---------|-------------|
| `npm start` | Start dev server on port 3000 |
| `npm run build` | Production build |
| `npm test` | Run tests |

## API Integration

All API calls go through the centralized Axios instance in `api.service.ts`, which:
- Auto-attaches JWT tokens from localStorage
- Redirects to `/login` on 401 responses
- Has 30s default timeout (2min for video uploads)
