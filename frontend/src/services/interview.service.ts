import api from "./api.service";
import axios from "axios";
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
      const response = await api.post<InterviewDTO>("/api/interviews/start", {
        resumeId,
        jobRoleId,
        numQuestions,
      });
      return response.data;
    } catch (error: any) {
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
   * avatarVideoUrl which will be null until the avatar is ready.
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
    } catch (error: any) {
      // Provide a more helpful error message for S3 upload failures
      if (axios.isAxiosError(error)) {
        const status = error.response?.status;
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
      const response = await api.get<Feedback>(
        `/api/interviews/${interviewId}/feedback`,
      );

      // Backend returns 202 when still processing
      if ((response as any).status === 202) {
        throw new Error(
          "Feedback is still being generated. Please try again shortly.",
        );
      }

      return response.data;
    } catch (error: any) {
      if (
        error?.status === 202 ||
        error?.message?.includes("still being generated")
      ) {
        throw new Error(
          "Feedback is still being generated. Please try again shortly.",
        );
      }
      throw error?.message ? error : new Error("Failed to fetch feedback.");
    }
  },

  // ════════════════════════════════════════════════════════════════
  // 8. Delete Interview
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
