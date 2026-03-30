// ============================================================
// User & Auth Types
// ============================================================

export interface User {
  id: number;
  name: string;
  email: string;
  createdAt: string;
}

export interface AuthResponse {
  token: string;
  userId: number;
  name: string;
  email: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  name: string;
  email: string;
  password: string;
}

export interface UpdateProfileRequest {
  firstName?: string;
  lastName?: string;
}

// ============================================================
// Interview Types
// ============================================================

/**
 * Interview lifecycle status.
 *
 * State machine:
 *   CREATED ──► GENERATING_VIDEOS ──► IN_PROGRESS ──► PROCESSING ──► COMPLETED
 *                      │                    │               │
 *                      └────────────────────┴───────────────┴──────► FAILED
 *
 * - CREATED:            Interview entity saved, questions persisted, no async work started.
 * - GENERATING_VIDEOS:  Avatar videos are being generated asynchronously (TTS → D-ID).
 *                        Frontend should poll GET /api/interviews/{id} for readiness.
 * - IN_PROGRESS:        All avatar videos ready (or timed out with text-only fallback).
 *                        User can watch questions and record answers.
 * - PROCESSING:         User completed the interview. AI feedback is being generated.
 * - COMPLETED:          Feedback generated and available for review.
 * - FAILED:             Unrecoverable error — user should retry or contact support.
 */
export type InterviewStatus =
  | "CREATED"
  | "GENERATING_VIDEOS"
  | "IN_PROGRESS"
  | "PROCESSING"
  | "COMPLETED"
  | "FAILED"
  | "DISQUALIFIED"; // FIX: Added DISQUALIFIED status for proctoring termination (Issue 3)

export interface Interview {
  id: number;
  userId: number;
  jobRole: string;
  jobRoleTitle?: string;
  status: InterviewStatus;
  type: "TEXT" | "VIDEO";
  overallScore?: number;
  startedAt: string;
  completedAt?: string;
  questions?: InterviewQuestion[];
}

export interface InterviewQuestion {
  questionId: number;
  questionNumber: number;
  questionText: string;
  category: string;
  difficulty: string;
  avatarVideoUrl: string | null;
  audioUrl?: string | null; // FIX: ElevenLabs TTS audio URL replacing D-ID avatar video
  answered: boolean;
  responseVideoUrl?: string | null;
  responseTranscription?: string | null;
}

export interface Question {
  id: number;
  questionText: string;
  questionNumber: number;
  category: string;
  difficulty: string;
  avatarVideoUrl: string;
}

export interface Response {
  id: number;
  questionId: number;
  videoUrl: string;
  transcription?: string;
  respondedAt: string;
}

export interface StartInterviewRequest {
  resumeId: number;
  jobRoleId: number;
  numQuestions?: number;
}

export interface InterviewStartResponse {
  interview: Interview;
  questions: Question[];
}

export interface SubmitResponseResult {
  message: string;
  interviewId: number;
  questionId: number;
}

// ============================================================
// Presigned URL Types (P1 — direct-to-S3 upload)
// ============================================================

/**
 * Response from GET /api/interviews/{interviewId}/upload-url.
 *
 * Contains a short-lived presigned PUT URL that the frontend uses
 * to upload video responses directly to S3, bypassing the backend
 * server entirely.
 */
export interface PresignedUrlResponse {
  /** Presigned PUT URL for direct S3 upload */
  uploadUrl: string;
  /** S3 object key — must be sent back to /confirm-upload */
  s3Key: string;
  /** URL validity in seconds (default: 900 = 15 minutes) */
  expiresInSeconds: number;
}

/**
 * Request body for POST /api/interviews/{interviewId}/confirm-upload.
 *
 * Sent by the frontend after successfully uploading a video to S3
 * using the presigned PUT URL. The backend verifies the S3 object
 * exists, creates the Response entity, and triggers transcription.
 */
export interface ConfirmUploadRequest {
  /** The question being answered */
  questionId: number;
  /** The S3 key returned from /upload-url */
  s3Key: string;
  /** MIME type of the uploaded video */
  contentType?: string;
  /** Duration of the recorded video in seconds */
  videoDuration?: number;
}

/**
 * Response from POST /api/interviews/{interviewId}/confirm-upload.
 */
export interface ConfirmUploadResponse {
  message: string;
  interviewId: number;
  questionId: number;
  s3Key: string;
}

// ============================================================
// Hybrid Interview Types (Dynamic Question Generation)
// ============================================================

/**
 * Response from POST /api/interviews/{interviewId}/questions/{questionId}/answer.
 *
 * In hybrid interview mode, this response contains the next question
 * (either pre-generated or dynamically generated) or indicates that
 * the interview is complete.
 */
export interface NextQuestionResponse {
  /** ID of the next question, or null if interview is complete */
  nextQuestionId: number | null;
  /** Text of the next question */
  nextQuestionText: string | null;
  /** 1-based question number */
  nextQuestionNumber: number | null;
  /** URL to TTS audio for the next question */
  nextQuestionAudioUrl: string | null;
  /** Question category (TECHNICAL, BEHAVIORAL, GENERAL) */
  nextQuestionCategory: string | null;
  /** Question difficulty (EASY, MEDIUM, HARD) */
  nextQuestionDifficulty: string | null;
  /** How the question was generated: PRE_GENERATED or DYNAMIC */
  generationMode: "PRE_GENERATED" | "DYNAMIC" | null;
  /** Total number of questions in the interview */
  totalQuestions: number;
  /** True if this was the last question and interview is now complete */
  interviewComplete: boolean;
  /** Optional message (e.g., "Interview complete! Generating feedback...") */
  message?: string;
}

/**
 * Request body for POST /api/interviews/{interviewId}/questions/{questionId}/answer.
 */
export interface AnswerSubmissionRequest {
  /** Transcribed text of the user's spoken answer */
  answerTranscript: string;
  /** Optional URL to the recorded video */
  answerVideoUrl?: string;
  /** Optional duration of the answer in seconds */
  durationSeconds?: number;
}

// ============================================================
// Interview DTO (matches backend InterviewDTO)
// ============================================================

/**
 * Full interview DTO returned by GET /api/interviews/{id}.
 *
 * Used by the polling hook to track avatar video generation
 * progress. The `status` field indicates the current state,
 * and `questions` contains per-question avatar readiness.
 */
export interface InterviewDTO {
  interviewId: number;
  status: InterviewStatus;
  type: string;
  overallScore?: number;
  jobRoleTitle?: string;
  startedAt?: string;
  completedAt?: string;
  questions?: InterviewQuestion[];
}

// ============================================================
// Feedback Types
// ============================================================

export interface Feedback {
  id: number;
  interviewId: number;
  overallScore: number;
  strengths: string[];
  weaknesses: string[];
  recommendations: string[];
  detailedAnalysis: string;
  questionAnswers?: QuestionAnswer[];
}

export interface QuestionAnswer {
  questionText: string;
  userAnswer: string;
  idealAnswer?: string;
}

export interface InterviewFeedback {
  status: "COMPLETED" | "PROCESSING" | "NOT_FOUND";
  overallScore?: number;
  strengths?: string[];
  weaknesses?: string[];
  recommendations?: string[];
  detailedAnalysis?: string;
  generatedAt?: string;
  message?: string;
}

// ============================================================
// Resume Types
// ============================================================

export interface Resume {
  id: number;
  fileName: string;
  fileUrl: string;
  extractedText: string;
  uploadedAt: string;
}

export interface ResumeAnalysis {
  score: number;
  strengths: string[];
  weaknesses: string[];
  suggestions: string[];
  overallFeedback: string;
  resumeFileName: string;
}

// ============================================================
// Job Role Types
// ============================================================

export interface JobRole {
  id: number;
  title: string;
  description: string;
  category: string;
  active: boolean;
}

// ============================================================
// API Error Type
// ============================================================

export interface ApiError {
  message: string;
  status: number;
  timestamp: string;
}

// ============================================================
// Helpers for API response normalization
// ============================================================

/** Normalize feedback list field (backend may return string or string[]). */
export function asFeedbackList(v: string | string[] | undefined): string[] {
  if (Array.isArray(v)) return v;
  if (typeof v === "string") return v.trim() ? [v] : [];
  return [];
}
