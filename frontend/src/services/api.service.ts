import axios, { AxiosError, InternalAxiosRequestConfig } from "axios";
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
// Request Interceptor — attach JWT token
// ============================================================

api.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
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
  },
  (error) => Promise.reject(error),
);

// ============================================================
// Response Interceptor — handle errors globally
// ============================================================

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    // Network error (no response received)
    if (!error.response) {
      const networkError: ApiError = {
        message: "Network error. Please check your internet connection.",
        status: 0,
        timestamp: new Date().toISOString(),
      };
      console.error("Network Error:", error.message);
      return Promise.reject(networkError);
    }

    const { status, data } = error.response;

    switch (status) {
      case 401:
        // Unauthorized — clear token, notify auth context, redirect to login
        removeAuthToken();
        window.dispatchEvent(new CustomEvent("auth:logout"));
        window.location.href = "/login";
        break;

      case 403:
        // Forbidden — user doesn't have permission
        console.error(
          "Forbidden: You do not have permission to access this resource.",
        );
        break;

      default:
        break;
    }

    // Return a structured error object
    const apiError: ApiError = {
      message: data?.message || error.message || "An unexpected error occurred",
      status: status,
      timestamp: data?.timestamp || new Date().toISOString(),
    };

    return Promise.reject(apiError);
  },
);

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

export default api;
