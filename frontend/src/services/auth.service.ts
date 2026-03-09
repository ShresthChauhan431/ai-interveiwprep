import api, { setAuthToken, removeAuthToken } from "./api.service";
import { User, AuthResponse, LoginRequest, RegisterRequest } from "../types";

// ============================================================
// Auth Service
// ============================================================

import { TOKEN_KEY } from "../utils/constants";

export const authService = {
  /**
   * Register a new user.
   * POST /api/auth/register
   */
  async register(data: RegisterRequest): Promise<AuthResponse> {
    try {
      const response = await api.post<AuthResponse>("/api/auth/register", data);
      return response.data;
    } catch (error: any) {
      throw error?.message
        ? error
        : new Error("Registration failed. Please try again.");
    }
  },

  /**
   * Login and store token.
   * POST /api/auth/login
   */
  async login(data: LoginRequest): Promise<AuthResponse> {
    try {
      const response = await api.post<AuthResponse>("/api/auth/login", data);
      const authData = response.data;

      // AUDIT-FIX: Save token to sessionStorage (was localStorage) — reduces XSS token exfiltration window
      sessionStorage.setItem(TOKEN_KEY, authData.token);
      setAuthToken(authData.token);

      return authData;
    } catch (error: any) {
      throw error?.message
        ? error
        : new Error("Login failed. Please check your credentials.");
    }
  },

  /**
   * Logout — clear token and redirect.
   */
  logout(): void {
    sessionStorage.removeItem(TOKEN_KEY); // AUDIT-FIX: migrated from localStorage to sessionStorage
    removeAuthToken();
    window.location.href = "/login";
  },

  /**
   * Get currently authenticated user profile.
   * GET /api/auth/profile
   */
  async getCurrentUser(): Promise<User> {
    try {
      const response = await api.get<User>("/api/auth/profile");
      return response.data;
    } catch (error: any) {
      throw error?.message ? error : new Error("Failed to fetch user profile.");
    }
  },

  /**
   * Update user profile.
   * PUT /api/auth/profile
   */
  async updateProfile(data: Partial<User>): Promise<User> {
    try {
      const response = await api.put<User>("/api/auth/profile", data);
      return response.data;
    } catch (error: any) {
      throw error?.message ? error : new Error("Failed to update profile.");
    }
  },

  /**
   * Get stored token from localStorage.
   */
  getStoredToken(): string | null {
    return sessionStorage.getItem(TOKEN_KEY); // AUDIT-FIX: migrated from localStorage to sessionStorage
  },
};

export default authService;
