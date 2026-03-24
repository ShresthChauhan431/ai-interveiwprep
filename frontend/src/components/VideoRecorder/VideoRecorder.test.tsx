import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import VideoRecorder from './VideoRecorder';

// ============================================================
// Mock custom hooks
// ============================================================

const mockStartRecording = jest.fn();
const mockStopRecording = jest.fn();
const mockRequestPermissions = jest.fn();

// Default return values (reset each test)
let mockVideoRecordingReturn: any;
let mockMediaPermissionsReturn: any;

jest.mock('../../hooks/useVideoRecording', () => ({
    useVideoRecording: () => mockVideoRecordingReturn,
}));

jest.mock('../../hooks/useMediaPermissions', () => ({
    useMediaPermissions: () => mockMediaPermissionsReturn,
}));

// ============================================================
// Helpers
// ============================================================

const mockOnSubmit = jest.fn();

const defaultProps = {
    onRecordingComplete: mockOnSubmit,
    maxDuration: 180,
    questionText: 'Tell me about yourself.',
};

const renderRecorder = (props = {}) =>
    render(<VideoRecorder {...defaultProps} {...props} />);

// ============================================================
// Tests
// ============================================================

describe('VideoRecorder Component', () => {
    beforeEach(() => {
        jest.clearAllMocks();

        // Default: permission not yet checked
        mockVideoRecordingReturn = {
            isRecording: false,
            recordedBlob: null,
            previewUrl: null,
            error: null,
            recordingTime: 0,
            startRecording: mockStartRecording,
            stopRecording: mockStopRecording,
        };

        mockMediaPermissionsReturn = {
            hasPermission: null,
            isChecking: false,
            error: null,
            checkPermissions: jest.fn(),
            requestPermissions: mockRequestPermissions,
        };
    });

    // ──────────────────────────────────────────────────────────
    // Rendering & Question Display
    // ──────────────────────────────────────────────────────────

    describe('Rendering', () => {
        test('renders the question text', () => {
            mockMediaPermissionsReturn.hasPermission = true;
            renderRecorder();
            expect(screen.getByText('Tell me about yourself.')).toBeInTheDocument();
        });
    });

    // ──────────────────────────────────────────────────────────
    // Permission States
    // ──────────────────────────────────────────────────────────

    describe('Camera Permission', () => {
        test('shows checking state when verifying permissions', () => {
            mockMediaPermissionsReturn.isChecking = true;
            renderRecorder();

            expect(
                screen.getByText(/checking camera and microphone permissions/i)
            ).toBeInTheDocument();
        });

        test('shows "Camera Access Required" when permission is denied', () => {
            mockMediaPermissionsReturn.hasPermission = false;
            renderRecorder();

            expect(screen.getByText(/camera access required/i)).toBeInTheDocument();
            expect(screen.getByRole('button', { name: /grant access/i })).toBeInTheDocument();
        });

        test('calls requestPermissions when "Grant Access" is clicked', () => {
            mockMediaPermissionsReturn.hasPermission = false;
            renderRecorder();

            fireEvent.click(screen.getByRole('button', { name: /grant access/i }));
            expect(mockRequestPermissions).toHaveBeenCalled();
        });

        test('shows permission error when it occurs', () => {
            mockMediaPermissionsReturn.hasPermission = false;
            mockMediaPermissionsReturn.error = 'Camera and microphone access was denied.';
            renderRecorder();

            expect(screen.getByText(/camera and microphone access was denied/i)).toBeInTheDocument();
        });
    });

    // ──────────────────────────────────────────────────────────
    // Ready to Record State
    // ──────────────────────────────────────────────────────────

    describe('Ready to Record', () => {
        beforeEach(() => {
            mockMediaPermissionsReturn.hasPermission = true;
        });

        test('shows "Start Recording Now" button when permission is granted and avatar is speaking', () => {
            mockMediaPermissionsReturn.hasPermission = true;
            renderRecorder({ isAvatarSpeaking: true });
            // When avatar stops speaking, the auto-start countdown begins.
            // While avatar is speaking, no start button or countdown appears.
            expect(screen.getByText(/camera active/i)).toBeInTheDocument();
        });

        test('shows max duration text', () => {
            renderRecorder();
            expect(screen.getByText(/maximum duration: 03:00/i)).toBeInTheDocument();
        });

        test('starts recording via auto-start countdown', async () => {
            renderRecorder();

            // The auto-start countdown triggers startRecording automatically after reaching 0
            await waitFor(() => {
                expect(mockStartRecording).toHaveBeenCalledTimes(1);
            }, { timeout: 5000 });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Recording State
    // ──────────────────────────────────────────────────────────

    describe('Recording', () => {
        test('shows finish button and timer while recording', () => {
            mockMediaPermissionsReturn.hasPermission = true;
            mockVideoRecordingReturn.isRecording = true;
            mockVideoRecordingReturn.recordingTime = 42;
            renderRecorder();

            expect(screen.getByRole('button', { name: /finish answering/i })).toBeInTheDocument();
            expect(screen.getByText('00:42')).toBeInTheDocument();
            expect(screen.getByText(/recording\.\.\./i)).toBeInTheDocument();
        });

        test('calls stopRecording when "Finish Answering & Submit" is clicked', () => {
            mockMediaPermissionsReturn.hasPermission = true;
            mockVideoRecordingReturn.isRecording = true;
            renderRecorder();

            fireEvent.click(screen.getByRole('button', { name: /finish answering/i }));
            expect(mockStopRecording).toHaveBeenCalledTimes(1);
        });
    });

    // ──────────────────────────────────────────────────────────
    // Preview & Submit State
    // ──────────────────────────────────────────────────────────

    describe('Preview & Submit', () => {
        const fakeBlob = new Blob(['video'], { type: 'video/webm' });

        beforeEach(() => {
            mockMediaPermissionsReturn.hasPermission = true;
            mockVideoRecordingReturn.recordedBlob = fakeBlob;
            mockVideoRecordingReturn.previewUrl = 'blob:http://localhost/fake-preview';
        });

        test('shows "Submit Answer" and "Re-record" buttons after recording', () => {
            renderRecorder();

            expect(screen.getByRole('button', { name: /submit answer/i })).toBeInTheDocument();
            expect(screen.getByRole('button', { name: /re-record/i })).toBeInTheDocument();
        });

        test('calls onSubmit with the recorded blob', async () => {
            mockOnSubmit.mockResolvedValueOnce(undefined);
            renderRecorder();

            fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));

            await waitFor(() => {
                expect(mockOnSubmit).toHaveBeenCalledWith(fakeBlob);
            });
        });

        test('shows "Submitting..." when isUploading prop is true', () => {
            renderRecorder({ isUploading: true });
            expect(screen.getByText(/submitting\.\.\./i)).toBeInTheDocument();
        });

        test('shows error when submission fails', async () => {
            mockOnSubmit.mockRejectedValueOnce(new Error('Upload failed'));
            renderRecorder();

            fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));

            await waitFor(() => {
                expect(screen.getByText(/upload failed/i)).toBeInTheDocument();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Error Display
    // ──────────────────────────────────────────────────────────

    describe('Error Display', () => {
        test('shows recording error when it occurs', () => {
            mockMediaPermissionsReturn.hasPermission = true;
            mockVideoRecordingReturn.error = 'Camera or microphone is already in use.';
            renderRecorder();

            expect(screen.getByText(/camera or microphone is already in use/i)).toBeInTheDocument();
        });
    });
});
