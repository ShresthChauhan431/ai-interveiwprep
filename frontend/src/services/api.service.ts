import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { ApiError } from '../types';

// ============================================================
// Base Configuration
// ============================================================

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080';
const TOKEN_KEY = 'auth_token';

const api = axios.create({
    baseURL: API_BASE_URL,
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json',
    },
});

// ============================================================
// Request Interceptor — attach JWT token
// ============================================================

api.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
        const token = localStorage.getItem(TOKEN_KEY);
        if (token && config.headers) {
            config.headers.Authorization = `Bearer ${token}`;
        }
        return config;
    },
    (error) => Promise.reject(error)
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
                message: 'Network error. Please check your internet connection.',
                status: 0,
                timestamp: new Date().toISOString(),
            };
            console.error('Network Error:', error.message);
            return Promise.reject(networkError);
        }

        const { status, data } = error.response;

        switch (status) {
            case 401:
                // Unauthorized — clear token, notify auth context, redirect to login
                removeAuthToken();
                window.dispatchEvent(new CustomEvent('auth:logout'));
                window.location.href = '/login';
                break;

            case 403:
                // Forbidden — user doesn't have permission
                console.error('Forbidden: You do not have permission to access this resource.');
                break;

            default:
                break;
        }

        // Return a structured error object
        const apiError: ApiError = {
            message: data?.message || error.message || 'An unexpected error occurred',
            status: status,
            timestamp: data?.timestamp || new Date().toISOString(),
        };

        return Promise.reject(apiError);
    }
);

// ============================================================
// Auth Token Helpers
// ============================================================

export const setAuthToken = (token: string): void => {
    localStorage.setItem(TOKEN_KEY, token);
};

export const removeAuthToken = (): void => {
    localStorage.removeItem(TOKEN_KEY);
};

export const isAuthenticated = (): boolean => {
    return !!localStorage.getItem(TOKEN_KEY);
};

export default api;
