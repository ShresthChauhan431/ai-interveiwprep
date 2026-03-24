import api, { longRunningApi } from "./api.service"; // FIX: Import longRunningApi for slow AI endpoints that exceed the default 30s timeout
import axios from "axios";
import { API_BASE_URL } from "../utils/constants"; // FIX: Import API_BASE_URL to detect when upload URL targets our own backend vs S3
import {
  InterviewDTO,
  PresignedUrlResponse,
  ConfirmUploadRequest,
  ConfirmUploadResponse,
  Response as InterviewResponse,
  Feedback,
} from "../types";

// ============================================================
// Interview Service
// ============================================================
//
// P1 Changes:
// - Added presigned URL upload flow (getUploadUrl → uploadToS3 → confirmUpload)
//   for direct-to-S3 video uploads that bypass the backend server.
// - Legacy submitVideoResponse (multipart) is retained for backward
//   compatibility but the presigned flow is preferred.
// - Added getInterviewDTO for polling during GENERATING_VIDEOS state.
// ============================================================

export const interviewService = {
  // ════════════════════════════════════════════════════════════════
  // 1. Start Interview
  // ════════════════════════════════════════════════════════════════

  /**
   * Start a new interview session.
   * POST /api/interviews/start
   *
   * P1: Returns the interview in GENERATING_VIDEOS status.
   * The frontend should poll GET /api/interviews/{id} until
   * the status transitions to IN_PROGRESS.
   */
  async startInterview(
    resumeId: number,
    jobRoleId: number,
    numQuestions?: number,
  ): Promise<InterviewDTO> {
    try {
      const response = await longRunningApi.post<InterviewDTO>(
        "/api/interviews/start",
        {
          // FIX: Use longRunningApi (5 min timeout) instead of api (30s timeout) — Ollama question generation on CPU can take 60–120s+
          resumeId,
          jobRoleId,
          numQuestions,
        },
      );
      return response.data;
    } catch (error: any) {
      // FIX: Provide actionable error messages that distinguish timeout from network failure
      if (
        error?.status === 0 &&
        (error?.message?.includes("timeout") ||
          error?.message?.includes("timed out"))
      ) {
        throw new Error(
          "Interview setup is taking longer than expected. This is normal for " +
          "the first interview — AI is generating your questions and avatar " +
          "videos. Please wait and try again, or check if Ollama is running: " +
          'run "ollama serve" in your terminal.',
        );
      }
      throw error?.message ? error : new Error("Failed to start interview.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 2. Get Interview (for polling)
  // ════════════════════════════════════════════════════════════════

  /**
   * Fetch full interview data including questions and avatar readiness.
   * GET /api/interviews/{interviewId}
   *
   * P1: Used by useInterviewPolling hook to track avatar video
   * generation progress. The response includes per-question
   * avatarVideoUrl kept for backward compat; audioUrl is now the primary field. // FIX: avatarVideoUrl kept for backward compat; audioUrl is now the primary field
   */
  async getInterview(interviewId: number): Promise<InterviewDTO> {
    try {
      const response = await api.get<InterviewDTO>(
        `/api/interviews/${interviewId}`,
      );
      return response.data;
    } catch (error: any) {
      throw error?.message
        ? error
        : new Error("Failed to fetch interview details.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 3. Presigned URL Upload Flow (P1 — preferred)
  // ════════════════════════════════════════════════════════════════
  //
  // Flow:
  //   1. getUploadUrl()   → GET  /api/interviews/{id}/upload-url
  //   2. uploadToS3()     → PUT  <presigned S3 URL>  (direct to S3)
  //   3. confirmUpload()  → POST /api/interviews/{id}/confirm-upload
  //
  // This flow uploads video files directly to S3, bypassing the
  // backend server entirely. The backend only generates the
  // presigned URL and confirms the upload afterward.
  // ════════════════════════════════════════════════════════════════

  /**
   * Step 1: Get a presigned PUT URL for direct-to-S3 upload.
   *
   * The backend generates a short-lived (15 min) presigned PUT URL
   * scoped to a specific S3 key for this question's video response.
   *
   * @param interviewId - The interview ID
   * @param questionId  - The question being answered
   * @param contentType - MIME type of the video (default: video/webm)
   * @returns Presigned URL, S3 key, and expiry duration
   */
  async getUploadUrl(
    interviewId: number,
    questionId: number,
    contentType: string = "video/webm",
  ): Promise<PresignedUrlResponse> {
    try {
      const response = await api.get<PresignedUrlResponse>(
        `/api/interviews/${interviewId}/upload-url`,
        {
          params: { questionId, contentType },
        },
      );
      return response.data;
    } catch (error: any) {
      throw error?.message ? error : new Error("Failed to get upload URL.");
    }
  },

  /**
   * Step 2: Upload a video blob directly to S3 using the presigned PUT URL.
   *
   * IMPORTANT: This request goes directly to S3, NOT to our backend.
   * - No Authorization header (S3 presigned URL has its own auth)
   * - Content-Type must match what was specified when generating the URL
   * - Uses a raw axios instance (not our `api` instance which adds auth headers)
   *
   * @param uploadUrl   - The presigned PUT URL from getUploadUrl()
   * @param videoBlob   - The recorded video Blob
   * @param contentType - MIME type (must match the presigned URL's content type)
   * @param onProgress  - Optional progress callback (0-100)
   */
  async uploadToS3(
    uploadUrl: string,
    videoBlob: Blob,
    contentType: string = "video/webm",
    onProgress?: (progress: number) => void,
  ): Promise<void> {
    try {
      // FIX: Detect whether the upload URL targets our own backend (local storage) or actual S3
      // Local storage URLs look like http://localhost:8081/api/files/upload-raw/...
      // S3 presigned URLs look like https://s3.amazonaws.com/... with query-string auth
      const isOwnBackend =
        uploadUrl.startsWith(API_BASE_URL) || // FIX: Matches configured backend base URL
        uploadUrl.includes("/api/files/"); // FIX: Fallback detection for local file upload endpoints

      if (isOwnBackend) {
        // FIX: Use authenticated `api` instance for our own backend — it needs the JWT header
        // The local FileController requires authentication (SecurityConfig: /api/files/** = authenticated)
        // FIX: Strip the base URL prefix so axios doesn't double it (api instance already has baseURL configured)
        const relativeUrl = uploadUrl.startsWith(API_BASE_URL)
          ? uploadUrl.substring(API_BASE_URL.length) // FIX: e.g. "http://localhost:8081/api/files/..." → "/api/files/..."
          : uploadUrl;
        await api.put(relativeUrl, videoBlob, {
          headers: {
            "Content-Type": contentType, // FIX: Set correct content type for the upload
          },
          transformRequest: [(data: Blob) => data], // FIX: Don't transform the blob body
          onUploadProgress: (progressEvent: {
            loaded: number;
            total?: number;
          }) => {
            if (onProgress && progressEvent.total) {
              const percent = Math.round(
                (progressEvent.loaded * 100) / progressEvent.total,
              );
              onProgress(percent);
            }
          },
          timeout: 120000, // FIX: 2 minute timeout for local uploads
        });
      } else {
        // Use raw axios — NOT the `api` instance which adds Authorization headers.
        // S3 presigned URLs include their own authentication via query parameters.
        // Sending an Authorization header would cause S3 to reject the request
        // with "Only one auth mechanism allowed".
        await axios.put(uploadUrl, videoBlob, {
          headers: {
            "Content-Type": contentType,
          },
          // Disable any default transformRequest that might modify the body
          transformRequest: [(data: Blob) => data],
          // Track upload progress for UI
          onUploadProgress: (progressEvent: {
            loaded: number;
            total?: number;
          }) => {
            if (onProgress && progressEvent.total) {
              const percent = Math.round(
                (progressEvent.loaded * 100) / progressEvent.total,
              );
              onProgress(percent);
            }
          },
          // No timeout — large videos may take a while on slow connections
          timeout: 0,
        });
      }
    } catch (error: any) {
      // Provide a more helpful error message for upload failures
      if (axios.isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          throw new Error(
            "Session expired. Please log in again and retry.", // FIX: Clear message for auth failure on upload
          );
        }
        if (status === 403) {
          throw new Error(
            "Upload URL expired or invalid. Please try recording again.",
          );
        }
        if (status === 400) {
          throw new Error(
            "Upload rejected by storage. Content type may be incorrect.",
          );
        }
      }
      throw error?.message
        ? error
        : new Error("Failed to upload video to storage.");
    }
  },

  /**
   * Step 3: Confirm the upload with the backend.
   *
   * Called after successfully uploading to S3. The backend:
   * 1. Verifies the S3 object exists (HEAD request)
   * 2. Creates the Response entity with the S3 key
   * 3. Triggers async transcription via AssemblyAI
   *
   * @param interviewId   - The interview ID
   * @param request       - Confirm upload request with questionId, s3Key, etc.
   * @returns Confirmation response
   */
  async confirmUpload(
    interviewId: number,
    request: ConfirmUploadRequest,
  ): Promise<ConfirmUploadResponse> {
    try {
      const response = await api.post<ConfirmUploadResponse>(
        `/api/interviews/${interviewId}/confirm-upload`,
        request,
      );
      return response.data;
    } catch (error: any) {
      throw error?.message ? error : new Error("Failed to confirm upload.");
    }
  },

  /**
   * Complete presigned upload flow: getUploadUrl → uploadToS3 → confirmUpload.
   *
   * Convenience method that orchestrates the full 3-step presigned upload
   * flow. Use this instead of calling the individual methods separately
   * unless you need fine-grained control over each step.
   *
   * @param interviewId   - The interview ID
   * @param questionId    - The question being answered
   * @param videoBlob     - The recorded video Blob
   * @param onProgress    - Optional progress callback (0-100)
   * @param videoDuration - Optional duration of the recording in seconds
   * @returns Confirmation response from the backend
   */
  async submitVideoPresigned(
    interviewId: number,
    questionId: number,
    videoBlob: Blob,
    onProgress?: (progress: number) => void,
    videoDuration?: number,
  ): Promise<ConfirmUploadResponse> {
    const contentType = videoBlob.type || "video/webm";

    // Step 1: Get presigned URL
    const { uploadUrl, s3Key } = await interviewService.getUploadUrl(
      interviewId,
      questionId,
      contentType,
    );

    // Step 2: Upload directly to S3
    await interviewService.uploadToS3(
      uploadUrl,
      videoBlob,
      contentType,
      onProgress,
    );

    // Step 3: Confirm upload with backend
    const confirmResponse = await interviewService.confirmUpload(interviewId, {
      questionId,
      s3Key,
      contentType,
      videoDuration,
    });

    return confirmResponse;
  },

  // ════════════════════════════════════════════════════════════════
  // 4. Legacy Video Response (multipart — backward compatibility)
  // ════════════════════════════════════════════════════════════════

  /**
   * Submit a video response via multipart upload (legacy endpoint).
   * POST /api/interviews/{interviewId}/response
   *
   * @deprecated Use submitVideoPresigned() instead. This method proxies
   * the entire video file through the backend server, consuming bandwidth
   * and memory. The presigned URL flow uploads directly to S3.
   *
   * Retained for backward compatibility with older frontend versions.
   */
  async submitVideoResponse(
    videoBlob: Blob,
    interviewId: number,
    questionId: number,
    onProgress?: (progress: number) => void,
  ): Promise<InterviewResponse> {
    try {
      const formData = new FormData();
      formData.append("video", videoBlob, `response_${questionId}.webm`);

      const response = await api.post<InterviewResponse>(
        `/api/interviews/${interviewId}/response?questionId=${questionId}`,
        formData,
        {
          headers: { "Content-Type": "multipart/form-data" },
          timeout: 120000, // 2 min for large uploads
          onUploadProgress: (progressEvent: {
            loaded: number;
            total?: number;
          }) => {
            if (onProgress && progressEvent.total) {
              const percent = Math.round(
                (progressEvent.loaded * 100) / progressEvent.total,
              );
              onProgress(percent);
            }
          },
        },
      );
      return response.data;
    } catch (error: any) {
      throw error?.message
        ? error
        : new Error("Failed to upload video response.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 5. Complete Interview
  // ════════════════════════════════════════════════════════════════

  /**
   * Mark interview as complete and trigger feedback generation.
   * POST /api/interviews/{interviewId}/complete
   *
   * Transitions the interview from IN_PROGRESS → PROCESSING.
   * The backend generates AI feedback asynchronously.
   */
  async completeInterview(interviewId: number): Promise<void> {
    try {
      await api.post(`/api/interviews/${interviewId}/complete`);
    } catch (error: any) {
      throw error?.message ? error : new Error("Failed to complete interview.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 6. Interview History
  // ════════════════════════════════════════════════════════════════

  /**
   * Get paginated interview history for the current user.
   * GET /api/interviews/history
   */
  async getInterviewHistory(): Promise<InterviewDTO[]> {
    try {
      const response = await api.get<InterviewDTO[]>("/api/interviews/history");
      return response.data;
    } catch (error: any) {
      throw error?.message
        ? error
        : new Error("Failed to fetch interview history.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 7. Feedback
  // ════════════════════════════════════════════════════════════════

  /**
   * Get feedback for a completed interview.
   * GET /api/interviews/{interviewId}/feedback
   *
   * Returns 202 Accepted when feedback is still being generated
   * (interview in PROCESSING state).
   */
  async getFeedback(interviewId: number): Promise<Feedback> {
    try {
      const response = await api.get<any>( // FIX: Use any since backend returns different shapes for COMPLETED vs PROCESSING
        `/api/interviews/${interviewId}/feedback`,
      );

      // FIX: Backend returns 202 when still processing
      if ((response as any).status === 202) {
        throw new Error(
          "Feedback is still being generated. Please try again shortly.",
        );
      }

      const data = response.data;

      // FIX: Check for FAILED status from backend and return it so UI can stop polling
      if (data.status === "FAILED") {
        return data as Feedback;
      }

      // FIX: Check for PROCESSING status returned as 200 (edge case)
      if (data.status === "PROCESSING") {
        throw new Error(
          "Feedback is still being generated. Please try again shortly.",
        );
      }

      // FIX: Parse JSON string arrays from backend — strengths/weaknesses/recommendations may be JSON strings or arrays
      const parseList = (val: any): string[] => {
        if (Array.isArray(val)) return val; // FIX: Already an array, use as-is
        if (typeof val === "string") {
          try {
            const parsed = JSON.parse(val); // FIX: Try parsing JSON string like '["item1","item2"]'
            if (Array.isArray(parsed)) return parsed;
          } catch {
            // FIX: Not valid JSON — treat as single-item list
          }
          return val.trim() ? [val] : [];
        }
        return [];
      };

      // FIX: Normalize backend response into Feedback type with properly parsed arrays
      const feedback: Feedback = {
        id: data.id || 0,
        interviewId: interviewId,
        overallScore: data.overallScore ?? 0,
        strengths: parseList(data.strengths), // FIX: Parse JSON string or array
        weaknesses: parseList(data.weaknesses), // FIX: Parse JSON string or array
        recommendations: parseList(data.recommendations), // FIX: Parse JSON string or array
        detailedAnalysis: data.detailedAnalysis || "",
      };

      return feedback;
    } catch (error: any) {
      if (
        error?.status === 202 ||
        error?.status === "FAILED" ||
        error?.message?.includes("still being generated")
      ) {
        throw error; // FIX: Re-throw processing/failed errors as-is for caller to handle
      }
      throw error?.message ? error : new Error("Failed to fetch feedback.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 8. Terminate Interview (Proctoring Disqualification)
  // ════════════════════════════════════════════════════════════════

  /**
   * Terminate an interview due to proctoring violations.
   * POST /api/interviews/{interviewId}/terminate
   *
   * Transitions the interview from IN_PROGRESS → DISQUALIFIED.
   * Called by the proctoring system when the candidate exceeds
   * the maximum number of violations.
   *
   * @param interviewId - The interview to terminate
   * @param reason      - Human-readable reason for termination
   */
  async terminateInterview(interviewId: number, reason: string): Promise<void> {
    try {
      await api.post(`/api/interviews/${interviewId}/terminate`, { reason }); // FIX: POST to terminate endpoint for proctoring disqualification
    } catch (error: any) {
      // FIX: Log but don't throw — frontend should navigate to disqualified page even if backend call fails
      console.error("Failed to terminate interview on backend:", error);
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 9. Delete Interview
  // ════════════════════════════════════════════════════════════════

  /**
   * Delete an interview.
   * DELETE /api/interviews/{interviewId}
   */
  async deleteInterview(interviewId: number): Promise<void> {
    try {
      await api.delete(`/api/interviews/${interviewId}`);
    } catch (error: any) {
      throw error?.message ? error : new Error("Failed to delete interview.");
    }
  },
};

export default interviewService;
