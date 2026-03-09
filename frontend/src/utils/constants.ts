// API Configuration
export const API_BASE_URL =
  process.env.REACT_APP_API_URL || "http://localhost:8081";

// Video constraints
export const MAX_VIDEO_SIZE_MB =
  Number(process.env.REACT_APP_MAX_VIDEO_SIZE_MB) || 50;
export const MAX_VIDEO_SIZE_BYTES = MAX_VIDEO_SIZE_MB * 1024 * 1024;
export const MAX_RECORDING_DURATION =
  Number(process.env.REACT_APP_MAX_RECORDING_DURATION) || 180; // seconds

// Media constraints
export const VIDEO_CONSTRAINTS: MediaStreamConstraints = {
  video: {
    width: { ideal: 1280 },
    height: { ideal: 720 },
    facingMode: "user",
  },
  audio: true,
};

// Supported video MIME types
export const SUPPORTED_VIDEO_TYPES = [
  "video/webm",
  "video/webm;codecs=vp9",
  "video/webm;codecs=vp8",
  "video/mp4",
];

// Interview
export const QUESTIONS_PER_INTERVIEW = 10;

// Local storage keys
export const TOKEN_KEY = "auth_token";
export const USER_KEY = "auth_user";

// Routes
export const ROUTES = {
  HOME: "/",
  LOGIN: "/login",
  REGISTER: "/register",
  DASHBOARD: "/dashboard",
  INTERVIEW_START: "/interview/start",
  INTERVIEW_SESSION: "/interview/:interviewId",
  INTERVIEW_FEEDBACK: "/interview/:interviewId/feedback",
  HISTORY: "/history",
  PROFILE: "/profile",
} as const;
