import axios, {
  AxiosError,
  AxiosInstance,
  InternalAxiosRequestConfig,
} from "axios"; // FIX: Added AxiosInstance import for longRunningApi type annotation
import { ApiError } from "../types";
import { API_BASE_URL, TOKEN_KEY } from "../utils/constants";

// ════════════════════════════════════════════════════════════════════════════
// AUDIT-FIX: JWT storage migrated from localStorage to sessionStorage.
// See AuthContext.tsx for full XSS risk documentation.
// ════════════════════════════════════════════════════════════════════════════

// ============================================================
// Base Configuration
// ============================================================

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    "Content-Type": "application/json",
  },
});

// ============================================================
// Long-Running Request Instance (5 min timeout)
// ============================================================
// FIX: Separate axios instance for endpoints that invoke slow AI pipelines
// (Ollama question generation, avatar video generation, etc.)
// The default 30s timeout causes "Network error" on POST /api/interviews/start
// because Ollama on CPU can take 60–120s+ to generate questions.

const longRunningApi: AxiosInstance = axios.create({
  // FIX: 5-minute timeout for slow AI endpoints like startInterview
  baseURL: API_BASE_URL,
  timeout: 300000, // FIX: 5 minutes — Ollama + avatar generation can be very slow on CPU
  headers: {
    "Content-Type": "application/json",
  },
});

// ============================================================
// Request Interceptor — attach JWT token
// ============================================================

// FIX: Extracted shared interceptor logic into reusable functions so both
// `api` and `longRunningApi` use identical JWT attachment and error handling.

/** Shared request interceptor — attaches JWT to own-backend requests. */
const requestInterceptor = (config: InternalAxiosRequestConfig) => {
  // FIX: Reusable request interceptor for both axios instances
  // AUDIT-FIX: Read token from sessionStorage (was localStorage) to reduce XSS exposure window
  const token = sessionStorage.getItem(TOKEN_KEY);
  // AUDIT-FIX: Only attach JWT to requests targeting our own backend API, not third-party URLs
  const isOwnBackend =
    !config.url ||
    config.url.startsWith("/") ||
    config.url.startsWith(API_BASE_URL);
  if (token && config.headers && isOwnBackend) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
};

/** Shared request error handler. */
const requestErrorHandler = (error: any) => Promise.reject(error); // FIX: Reusable for both instances

api.interceptors.request.use(requestInterceptor, requestErrorHandler);
longRunningApi.interceptors.request.use(
  requestInterceptor,
  requestErrorHandler,
); // FIX: longRunningApi gets the same JWT interceptor as api

// ============================================================
// Response Interceptor — handle errors globally
// ============================================================

/** Shared response success handler. */
const responseSuccessHandler = (response: any) => response; // FIX: Reusable for both instances

/** Shared response error handler — handles network errors, timeouts, 401s, etc. */
const responseErrorHandler = (error: AxiosError<ApiError>) => {
  // FIX: Reusable for both instances
  // Network error or timeout (no response received)
  if (!error.response) {
    // FIX: Distinguish timeout from actual network errors — the old message
    // "Network error. Please check your internet connection." was misleading
    // when the real cause was a 30s timeout on a slow Ollama call.
    const isTimeout =
      error.code === "ECONNABORTED" || // FIX: Axios sets this code on timeout
      error.message?.includes("timeout"); // FIX: Fallback check for timeout keyword

    const message = isTimeout
      ? "Request timed out. The server is still processing — this is normal " + // FIX: Timeout-specific user-friendly message
        "for AI-powered operations. Please wait a moment and try again."
      : !navigator.onLine // FIX: Check actual connectivity before blaming the network
        ? "No internet connection. Please check your network."
        : "Network error. Please check that the backend server is running."; // FIX: More helpful than generic "check your internet"

    const networkError: ApiError = {
      message,
      status: 0,
      timestamp: new Date().toISOString(),
    };
    console.error("Network/Timeout Error:", error.code, error.message); // FIX: Log error code for easier debugging
    return Promise.reject(networkError);
  }

  const { status, data } = error.response;

  // FIX: Human-readable message for every error type so users never see raw axios errors
  let message: string;

  switch (status) {
    case 401:
      message = "Session expired. Please log in again."; // FIX: Clear session-expired message for 401
      removeAuthToken(); // FIX: Clear stale token immediately
      window.dispatchEvent(new CustomEvent("auth:session-expired")); // FIX: Notify listeners (AuthContext, InterviewRoom) before redirect
      window.dispatchEvent(new CustomEvent("auth:logout")); // FIX: Keep existing logout event for AuthContext cleanup
      // FIX: Delay redirect so mid-interview users see the session-expired message instead of a raw error
      setTimeout(() => {
        window.location.href = "/login";
      }, 3000);
      break;

    case 403:
      message = "You don't have permission to do this."; // FIX: User-friendly 403 message
      console.error("Forbidden:", data?.message || error.message);
      break;

    case 404:
      message = "The requested resource was not found."; // FIX: User-friendly 404 message
      break;

    case 500:
      message = data?.message || "Server error. Please try again in a moment."; // FIX: User-friendly 500 message, use backend message if available
      break;

    default:
      message =
        data?.message || error.message || "An unexpected error occurred"; // FIX: Fallback for other status codes
      break;
  }

  // Return a structured error object
  const apiError: ApiError = {
    message, // FIX: Use the human-readable message determined above
    status: status,
    timestamp: data?.timestamp || new Date().toISOString(),
  };

  return Promise.reject(apiError);
};

api.interceptors.response.use(responseSuccessHandler, responseErrorHandler);
longRunningApi.interceptors.response.use(
  responseSuccessHandler,
  responseErrorHandler,
); // FIX: longRunningApi gets the same 401/error handling as api

// ============================================================
// Auth Token Helpers
// ============================================================

// AUDIT-FIX: All token storage migrated from localStorage to sessionStorage
export const setAuthToken = (token: string): void => {
  sessionStorage.setItem(TOKEN_KEY, token); // AUDIT-FIX: was localStorage
};

export const removeAuthToken = (): void => {
  sessionStorage.removeItem(TOKEN_KEY); // AUDIT-FIX: was localStorage
};

export const isAuthenticated = (): boolean => {
  return !!sessionStorage.getItem(TOKEN_KEY); // AUDIT-FIX: was localStorage
};

export { longRunningApi }; // FIX: Export long-running instance for interview.service.ts to use on slow endpoints

export default api;
