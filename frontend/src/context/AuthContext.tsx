import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { User } from '../types';
import authService from '../services/auth.service';
import { LoginRequest, RegisterRequest } from '../types';

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
    login: async () => { },
    register: async () => { },
    logout: () => { },
    updateUser: async () => { },
    refreshUser: async () => { },
});

// ============================================================
// useAuth hook
// ============================================================

export const useAuth = () => {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error('useAuth must be used within an AuthProvider');
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
    useEffect(() => {
        const token = authService.getStoredToken();
        if (token) {
            authService.getCurrentUser()
                .then((profile) => setUser(profile))
                .catch(() => setUser(null))
                .finally(() => setIsLoading(false));
        } else {
            setIsLoading(false);
        }
    }, []);

    const logout = useCallback(() => {
        authService.logout();
        setUser(null);
    }, []);

    // Listen for 401 auto-logout events dispatched by API interceptor
    useEffect(() => {
        const handleForceLogout = () => logout();
        window.addEventListener('auth:logout', handleForceLogout);
        return () => window.removeEventListener('auth:logout', handleForceLogout);
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
        // Save token so subsequent API calls are authenticated
        if (response.token) {
            localStorage.setItem('auth_token', response.token);
            const { setAuthToken } = await import('../services/api.service');
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
        [user, isLoading, login, register, logout, updateUser, refreshUser]
    );

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};
