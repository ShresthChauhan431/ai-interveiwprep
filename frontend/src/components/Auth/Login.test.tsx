import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '@testing-library/jest-dom';
import Login from './Login';
import { AuthContext } from '../../context/AuthContext';

// ============================================================
// Mocks
// ============================================================

const mockNavigate = jest.fn();

// Mock only useNavigate; keep the real MemoryRouter, Link, etc.
jest.mock('react-router-dom', () => {
    const actual = jest.requireActual('react-router-dom');
    return {
        ...actual,
        useNavigate: () => mockNavigate,
    };
});

// Re-import MemoryRouter AFTER the mock is set up
const { MemoryRouter, Link: RouterLink } = jest.requireActual('react-router-dom');

// ── Auth context helpers ────────────────────────────────────

const mockLogin = jest.fn();

const defaultAuthValue = {
    user: null,
    isAuthenticated: false,
    isLoading: false,
    login: mockLogin,
    register: jest.fn(),
    logout: jest.fn(),
    updateUser: jest.fn(),
    refreshUser: jest.fn(),
};

const renderLogin = (authOverrides = {}) =>
    render(
        <AuthContext.Provider value={{ ...defaultAuthValue, ...authOverrides }}>
            <MemoryRouter>
                <Login />
            </MemoryRouter>
        </AuthContext.Provider>
    );

// ============================================================
// Tests
// ============================================================

describe('Login Component', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    // ──────────────────────────────────────────────────────────
    // Rendering
    // ──────────────────────────────────────────────────────────

    describe('Rendering', () => {
        test('renders the login form with email and password fields', () => {
            renderLogin();

            expect(screen.getByLabelText(/email address/i)).toBeInTheDocument();
            expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
            expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
        });

        test('renders "Welcome Back" heading', () => {
            renderLogin();
            expect(screen.getByText(/welcome back/i)).toBeInTheDocument();
        });

        test('renders sign up link', () => {
            renderLogin();
            expect(screen.getByText(/sign up/i)).toBeInTheDocument();
        });

        test('renders remember me checkbox', () => {
            renderLogin();
            expect(screen.getByText(/remember me/i)).toBeInTheDocument();
        });

        test('renders forgot password link', () => {
            renderLogin();
            expect(screen.getByText(/forgot password/i)).toBeInTheDocument();
        });
    });

    // ──────────────────────────────────────────────────────────
    // Form Validation
    // ──────────────────────────────────────────────────────────

    describe('Form Validation', () => {
        test('shows error when submitting empty form', async () => {
            renderLogin();

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/email is required/i)).toBeInTheDocument();
            });
            await waitFor(() => {
                expect(screen.getByText(/password is required/i)).toBeInTheDocument();
            });
        });

        test('shows error for invalid email format', async () => {
            renderLogin();

            const emailInput = screen.getByLabelText(/email address/i);
            await userEvent.type(emailInput, 'not-an-email');

            const passwordInput = screen.getByLabelText(/password/i);
            await userEvent.type(passwordInput, 'Password123');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/enter a valid email address/i)).toBeInTheDocument();
            });
        });

        test('shows error when password is too short', async () => {
            renderLogin();

            const emailInput = screen.getByLabelText(/email address/i);
            await userEvent.type(emailInput, 'test@example.com');

            const passwordInput = screen.getByLabelText(/password/i);
            await userEvent.type(passwordInput, '12345');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/password must be at least 6 characters/i)).toBeInTheDocument();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Successful Login
    // ──────────────────────────────────────────────────────────

    describe('Successful Login', () => {
        test('calls login and navigates to dashboard on success', async () => {
            mockLogin.mockResolvedValueOnce(undefined);
            renderLogin();

            const emailInput = screen.getByLabelText(/email address/i);
            await userEvent.type(emailInput, 'john@example.com');

            const passwordInput = screen.getByLabelText(/password/i);
            await userEvent.type(passwordInput, 'Password123');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(mockLogin).toHaveBeenCalledWith({
                    email: 'john@example.com',
                    password: 'Password123',
                });
            });

            await waitFor(() => {
                expect(mockNavigate).toHaveBeenCalledWith('/dashboard');
            });
        });

        test('disables submit button while loading', async () => {
            // Make login hang (never resolve)
            mockLogin.mockReturnValueOnce(new Promise(() => { }));
            renderLogin();

            const emailInput = screen.getByLabelText(/email address/i);
            await userEvent.type(emailInput, 'john@example.com');

            const passwordInput = screen.getByLabelText(/password/i);
            await userEvent.type(passwordInput, 'Password123');

            // Get a reference to the submit button before clicking
            const submitButton = screen.getByRole('button', { name: /sign in/i });
            fireEvent.click(submitButton);

            await waitFor(() => {
                // The button should be disabled while loading
                expect(submitButton).toBeDisabled();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Error Handling
    // ──────────────────────────────────────────────────────────

    describe('Error Handling', () => {
        test('shows "Invalid email or password" for 401 error', async () => {
            mockLogin.mockRejectedValueOnce({ status: 401, message: 'Unauthorized' });
            renderLogin();

            await userEvent.type(screen.getByLabelText(/email address/i), 'john@example.com');
            await userEvent.type(screen.getByLabelText(/password/i), 'WrongPassword');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/invalid email or password/i)).toBeInTheDocument();
            });
        });

        test('shows network error message for status 0', async () => {
            mockLogin.mockRejectedValueOnce({ status: 0, message: 'Network Error' });
            renderLogin();

            await userEvent.type(screen.getByLabelText(/email address/i), 'john@example.com');
            await userEvent.type(screen.getByLabelText(/password/i), 'Password123');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/network error/i)).toBeInTheDocument();
            });
        });

        test('shows generic error message for other errors', async () => {
            mockLogin.mockRejectedValueOnce({ status: 500, message: 'Server down' });
            renderLogin();

            await userEvent.type(screen.getByLabelText(/email address/i), 'john@example.com');
            await userEvent.type(screen.getByLabelText(/password/i), 'Password123');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/server down/i)).toBeInTheDocument();
            });
        });

        test('error alert can be dismissed', async () => {
            mockLogin.mockRejectedValueOnce({ status: 401, message: 'Unauthorized' });
            renderLogin();

            await userEvent.type(screen.getByLabelText(/email address/i), 'john@example.com');
            await userEvent.type(screen.getByLabelText(/password/i), 'Password123');

            fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

            await waitFor(() => {
                expect(screen.getByText(/invalid email or password/i)).toBeInTheDocument();
            });

            // Click the close button on the alert
            const closeButton = screen.getByRole('button', { name: /close/i });
            fireEvent.click(closeButton);

            await waitFor(() => {
                expect(screen.queryByText(/invalid email or password/i)).not.toBeInTheDocument();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Password Visibility Toggle
    // ──────────────────────────────────────────────────────────

    describe('Password Visibility Toggle', () => {
        test('toggles password visibility when eye icon is clicked', () => {
            renderLogin();

            const passwordInput = screen.getByLabelText(/password/i);
            expect(passwordInput).toHaveAttribute('type', 'password');

            // Click the visibility toggle (the icon button inside the input)
            const toggleButton = passwordInput
                .closest('.MuiInputBase-root')
                ?.querySelector('button');
            expect(toggleButton).toBeTruthy();

            fireEvent.click(toggleButton!);
            expect(passwordInput).toHaveAttribute('type', 'text');

            fireEvent.click(toggleButton!);
            expect(passwordInput).toHaveAttribute('type', 'password');
        });
    });
});
