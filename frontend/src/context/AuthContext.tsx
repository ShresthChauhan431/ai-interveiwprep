// ════════════════════════════════════════════════════════════════════════════
// AUDIT-FIX: XSS Risk Documentation — JWT Storage
// ════════════════════════════════════════════════════════════════════════════
// The JWT token was previously stored in `localStorage`, which is accessible
// to ANY JavaScript running on the page — including injected scripts from XSS
// attacks. An attacker who achieves XSS can exfiltrate the token and use it
// from any device until it expires.
//
// Migration applied: localStorage → sessionStorage
//   - sessionStorage is still accessible to JS (not immune to XSS), but it is
//     scoped to the browser tab and cleared when the tab is closed, reducing
//     the window of exposure.
//   - This is a *minimum* improvement. The ideal fix is to store the JWT in an
//     httpOnly + Secure + SameSite=Strict cookie, which is completely invisible
//     to JavaScript. That requires backend changes (Set-Cookie header on login,
//     cookie-based auth filter) and is flagged as MANUAL ACTION REQUIRED.
//
// ⚠️ MANUAL ACTION REQUIRED: Migrate JWT storage to httpOnly cookies.
//    This requires:
//    1. Backend: Return JWT via Set-Cookie header (httpOnly, Secure, SameSite=Strict)
//    2. Backend: Read JWT from cookie in JwtAuthenticationFilter instead of Authorization header
//    3. Frontend: Remove all manual token handling; let the browser manage cookies
//    4. CSRF protection must be re-enabled when using cookie-based auth
// ════════════════════════════════════════════════════════════════════════════

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { User } from "../types";
import authService from "../services/auth.service";
import { LoginRequest, RegisterRequest } from "../types";
import { useInterviewStore } from "../stores/useInterviewStore";

// ============================================================
// Context Types
// ============================================================

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
  updateUser: (data: Partial<User>) => Promise<void>;
  refreshUser: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextType>({
  user: null,
  isAuthenticated: false,
  isLoading: true,
  login: async () => {},
  register: async () => {},
  logout: () => {},
  updateUser: async () => {},
  refreshUser: async () => {},
});

// ============================================================
// useAuth hook
// ============================================================

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
};

// ============================================================
// Provider
// ============================================================

interface AuthProviderProps {
  children: React.ReactNode;
}

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Initialize — check if token exists and fetch profile
  // AUDIT-FIX: Token is now read from sessionStorage (was localStorage)
  useEffect(() => {
    const token = authService.getStoredToken();
    if (token) {
      authService
        .getCurrentUser()
        .then((profile) => setUser(profile))
        .catch(() => setUser(null))
        .finally(() => setIsLoading(false));
    } else {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(() => {
    // P3-1: Reset interview store on logout to prevent stale data
    // persisting in memory for the next user on a shared device.
    useInterviewStore.getState().reset();
    authService.logout();
    setUser(null);
  }, []);

  // Listen for 401 auto-logout events dispatched by API interceptor
  useEffect(() => {
    const handleForceLogout = () => logout();
    window.addEventListener("auth:logout", handleForceLogout);
    return () => window.removeEventListener("auth:logout", handleForceLogout);
  }, [logout]);

  const login = useCallback(async (data: LoginRequest) => {
    const response = await authService.login(data);
    setUser({
      id: response.userId,
      name: response.name,
      email: response.email,
      createdAt: new Date().toISOString(),
    });
  }, []);

  const register = useCallback(async (data: RegisterRequest) => {
    const response = await authService.register(data);
    // AUDIT-FIX: Save token to sessionStorage (was localStorage) — see XSS risk note at top of file
    if (response.token) {
      sessionStorage.setItem("auth_token", response.token);
      const { setAuthToken } = await import("../services/api.service");
      setAuthToken(response.token);
    }
    setUser({
      id: response.userId,
      name: response.name,
      email: response.email,
      createdAt: new Date().toISOString(),
    });
  }, []);

  const updateUser = useCallback(async (data: Partial<User>) => {
    const updated = await authService.updateProfile(data);
    setUser(updated);
  }, []);

  const refreshUser = useCallback(async () => {
    try {
      const profile = await authService.getCurrentUser();
      setUser(profile);
    } catch {
      logout();
    }
  }, [logout]);

  const value = useMemo(
    () => ({
      user,
      isAuthenticated: !!user,
      isLoading,
      login,
      register,
      logout,
      updateUser,
      refreshUser,
    }),
    [user, isLoading, login, register, logout, updateUser, refreshUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};
