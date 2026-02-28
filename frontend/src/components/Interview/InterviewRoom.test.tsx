import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import InterviewRoom from './InterviewRoom';
import { InterviewDTO, InterviewQuestion } from '../../types';

// ============================================================
// Mocks
// ============================================================

const mockNavigate = jest.fn();

jest.mock('react-router-dom', () => {
    const actual = jest.requireActual('react-router-dom');
    return {
        ...actual,
        useNavigate: () => mockNavigate,
    };
});

const { MemoryRouter } = jest.requireActual('react-router-dom/dist/main');

// Mock interviewService
const mockSubmitVideoResponse = jest.fn();
const mockCompleteInterview = jest.fn();

jest.mock('../../services/interview.service', () => ({
    interviewService: {
        submitVideoPresigned: (...args: any[]) => mockSubmitVideoResponse(...args),
        completeInterview: (...args: any[]) => mockCompleteInterview(...args),
        getInterview: jest.fn(),
    },
}));

// Mock child components to simplify testing the parent logic
jest.mock('../AIAvatar/AvatarPlayer', () => {
    return function MockAvatarPlayer({
        questionText,
        onVideoEnd,
    }: {
        questionText: string;
        onVideoEnd: () => void;
        videoUrl?: string;
        questionNumber?: number;
        totalQuestions?: number;
    }) {
        return (
            <div data-testid="avatar-player">
                <p>{questionText}</p>
                <button onClick={onVideoEnd} data-testid="avatar-done-btn">
                    Finish Speaking
                </button>
            </div>
        );
    };
});

jest.mock('../VideoRecorder/VideoRecorder', () => {
    return function MockVideoRecorder({
        questionText,
        onSubmit,
    }: {
        questionText: string;
        onSubmit: (blob: Blob) => Promise<void>;
    }) {
        return (
            <div data-testid="video-recorder">
                <p>{questionText}</p>
                <button
                    onClick={() => onSubmit(new Blob(['test-video'], { type: 'video/webm' }))}
                    data-testid="submit-recording-btn"
                >
                    Submit Recording
                </button>
            </div>
        );
    };
});

// ============================================================
// Test data
// ============================================================

const mockQuestions: InterviewQuestion[] = [
    {
        questionId: 1,
        questionText: 'Tell me about yourself.',
        questionNumber: 1,
        category: 'Behavioral',
        difficulty: 'Easy',
        avatarVideoUrl: 'https://example.com/avatar1.mp4',
        answered: false,
    },
    {
        questionId: 2,
        questionText: 'What is polymorphism?',
        questionNumber: 2,
        category: 'Technical',
        difficulty: 'Medium',
        avatarVideoUrl: 'https://example.com/avatar2.mp4',
        answered: false,
    },
    {
        questionId: 3,
        questionText: 'Describe a challenging project.',
        questionNumber: 3,
        category: 'Behavioral',
        difficulty: 'Hard',
        avatarVideoUrl: 'https://example.com/avatar3.mp4',
        answered: false,
    },
];

const mockInitialData: InterviewDTO = {
    interviewId: 42,
    status: 'IN_PROGRESS',
    type: 'VIDEO',
    jobRoleTitle: 'Software Engineer',
    startedAt: '2024-01-01T00:00:00Z',
    questions: mockQuestions,
};

// ============================================================
// Helper
// ============================================================

const renderRoom = (props = {}) =>
    render(
        <MemoryRouter>
            <InterviewRoom
                interviewId={42}
                initialData={mockInitialData}
                {...props}
            />
        </MemoryRouter>
    );

// ============================================================
// Tests
// ============================================================

describe('InterviewRoom Component', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    // ──────────────────────────────────────────────────────────
    // Initial Render
    // ──────────────────────────────────────────────────────────

    describe('Initial Render', () => {
        test('shows progress "Question 1 of 3"', () => {
            renderRoom();
            expect(screen.getByText('Question 1 of 3')).toBeInTheDocument();
        });

        test('shows 0% complete initially', () => {
            renderRoom();
            expect(screen.getByText('0% complete')).toBeInTheDocument();
        });

        test('renders avatar player with first question', () => {
            renderRoom();
            expect(screen.getByTestId('avatar-player')).toBeInTheDocument();
            expect(screen.getByText('Tell me about yourself.')).toBeInTheDocument();
        });

        test('does not show video recorder initially', () => {
            renderRoom();
            expect(screen.queryByTestId('video-recorder')).not.toBeInTheDocument();
        });

        test('renders "Skip to recording" button', () => {
            renderRoom();
            expect(screen.getByRole('button', { name: /skip to recording/i })).toBeInTheDocument();
        });
    });

    // ──────────────────────────────────────────────────────────
    // Question Flow: Avatar → Recorder
    // ──────────────────────────────────────────────────────────

    describe('Question Flow', () => {
        test('shows video recorder after avatar finishes speaking', () => {
            renderRoom();

            // Simulate avatar finishing
            fireEvent.click(screen.getByTestId('avatar-done-btn'));

            expect(screen.getByTestId('video-recorder')).toBeInTheDocument();
        });

        test('shows video recorder when "Skip to recording" is clicked', () => {
            renderRoom();

            fireEvent.click(screen.getByRole('button', { name: /skip to recording/i }));

            expect(screen.getByTestId('video-recorder')).toBeInTheDocument();
        });
    });

    // ──────────────────────────────────────────────────────────
    // Question Progression
    // ──────────────────────────────────────────────────────────

    describe('Question Progression', () => {
        test('advances to next question after video submission', async () => {
            mockSubmitVideoResponse.mockResolvedValueOnce({ id: 1 });
            renderRoom();

            // Skip to recording phase
            fireEvent.click(screen.getByTestId('avatar-done-btn'));

            // Submit recording
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(mockSubmitVideoResponse).toHaveBeenCalledWith(
                    expect.any(Blob),
                    42, // interviewId
                    1,  // questionId
                    expect.any(Function) // onProgress
                );
            });

            // Should now show question 2
            await waitFor(() => {
                expect(screen.getByText('Question 2 of 3')).toBeInTheDocument();
            });

            // Should revert to avatar player (not recorder)
            await waitFor(() => {
                expect(screen.getByTestId('avatar-player')).toBeInTheDocument();
                expect(screen.getByText('What is polymorphism?')).toBeInTheDocument();
            });
        });

        test('updates progress bar after each question', async () => {
            mockSubmitVideoResponse.mockResolvedValueOnce({ id: 1 });
            renderRoom();

            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(screen.getByText('33% complete')).toBeInTheDocument();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Video Submission
    // ──────────────────────────────────────────────────────────

    describe('Video Submission', () => {
        test('calls interviewService.submitVideoResponse with correct params', async () => {
            mockSubmitVideoResponse.mockResolvedValueOnce({ id: 1 });
            renderRoom();

            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(mockSubmitVideoResponse).toHaveBeenCalledTimes(1);
                expect(mockSubmitVideoResponse).toHaveBeenCalledWith(
                    expect.any(Blob),
                    42,
                    1,
                    expect.any(Function)
                );
            });
        });

        test('shows error when video upload fails', async () => {
            mockSubmitVideoResponse.mockRejectedValueOnce(new Error('Upload failed'));
            renderRoom();

            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(screen.getByText(/upload failed/i)).toBeInTheDocument();
            });
        });

        test('shows "Uploading response..." while uploading', async () => {
            mockSubmitVideoResponse.mockReturnValueOnce(new Promise(() => { }));
            renderRoom();

            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(screen.getByText(/uploading response/i)).toBeInTheDocument();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Interview Completion
    // ──────────────────────────────────────────────────────────

    describe('Interview Completion', () => {
        test('shows "Complete Interview" button on last question in recording view', async () => {
            // Advance to the last question
            mockSubmitVideoResponse
                .mockResolvedValueOnce({ id: 1 })
                .mockResolvedValueOnce({ id: 2 });

            renderRoom();

            // Q1: skip avatar → submit recording
            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            // Wait for Q2
            await waitFor(() => {
                expect(screen.getByText('Question 2 of 3')).toBeInTheDocument();
            });

            // Q2: skip avatar → submit recording
            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            // Wait for Q3
            await waitFor(() => {
                expect(screen.getByText('Question 3 of 3')).toBeInTheDocument();
            });

            // Switch to recording view
            fireEvent.click(screen.getByTestId('avatar-done-btn'));

            // The "Complete Interview" button should appear on the last question
            await waitFor(() => {
                expect(screen.getByRole('button', { name: /complete interview/i })).toBeInTheDocument();
            });
        });

        test('completes interview and navigates to feedback on last question submit', async () => {
            // Only 1 question — makes it immediately the last
            const singleQuestionData: InterviewDTO = {
                ...mockInitialData,
                questions: [mockQuestions[0]],
            };
            mockSubmitVideoResponse.mockResolvedValueOnce({ id: 1 });
            mockCompleteInterview.mockResolvedValueOnce(undefined);

            render(
                <MemoryRouter>
                    <InterviewRoom interviewId={42} initialData={singleQuestionData} />
                </MemoryRouter>
            );

            // Skip avatar → submit recording
            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(mockCompleteInterview).toHaveBeenCalledWith(42);
            });

            await waitFor(() => {
                expect(mockNavigate).toHaveBeenCalledWith('/interview/42/feedback');
            });
        });

        test('shows completing state while finishing interview', async () => {
            const singleQuestionData: InterviewDTO = {
                ...mockInitialData,
                questions: [mockQuestions[0]],
            };
            mockSubmitVideoResponse.mockResolvedValueOnce({ id: 1 });
            mockCompleteInterview.mockReturnValueOnce(new Promise(() => { })); // Never resolves

            render(
                <MemoryRouter>
                    <InterviewRoom interviewId={42} initialData={singleQuestionData} />
                </MemoryRouter>
            );

            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(screen.getByText(/completing interview/i)).toBeInTheDocument();
            });
        });

        test('shows error when interview completion fails', async () => {
            mockCompleteInterview.mockRejectedValueOnce(new Error('Server error'));
            renderRoom();

            // First, get to the last question
            mockSubmitVideoResponse
                .mockResolvedValueOnce({ id: 1 })
                .mockResolvedValueOnce({ id: 2 });

            // Q1
            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));
            await waitFor(() => expect(screen.getByText('Question 2 of 3')).toBeInTheDocument());

            // Q2
            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));
            await waitFor(() => expect(screen.getByText('Question 3 of 3')).toBeInTheDocument());

            // Q3 — click Complete Interview
            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByRole('button', { name: /complete interview/i }));

            await waitFor(() => {
                expect(screen.getByText(/server error/i)).toBeInTheDocument();
            });
        });
    });

    // ──────────────────────────────────────────────────────────
    // Error Dismissal
    // ──────────────────────────────────────────────────────────

    describe('Error Handling', () => {
        test('error alert can be dismissed', async () => {
            mockSubmitVideoResponse.mockRejectedValueOnce(new Error('Upload failed'));
            renderRoom();

            fireEvent.click(screen.getByTestId('avatar-done-btn'));
            fireEvent.click(screen.getByTestId('submit-recording-btn'));

            await waitFor(() => {
                expect(screen.getByText(/upload failed/i)).toBeInTheDocument();
            });

            const closeButton = screen.getByRole('button', { name: /close/i });
            fireEvent.click(closeButton);

            await waitFor(() => {
                expect(screen.queryByText(/upload failed/i)).not.toBeInTheDocument();
            });
        });
    });
});
