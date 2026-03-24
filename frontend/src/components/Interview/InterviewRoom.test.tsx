import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import InterviewRoom from "./InterviewRoom";
import { InterviewDTO, InterviewQuestion } from "../../types";

// ============================================================
// Mocks
// ============================================================

const mockNavigate = jest.fn();

jest.mock("react-router-dom", () => {
  const actual = jest.requireActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

const { MemoryRouter } = jest.requireActual("react-router-dom");

// Mock interviewService
const mockSubmitVideoPresigned = jest.fn();
const mockSubmitVideoResponse = jest.fn(); // FIX: Legacy fallback must also be mocked
const mockCompleteInterview = jest.fn();

jest.mock("../../services/interview.service", () => ({
  interviewService: {
    submitVideoPresigned: (...args: any[]) => mockSubmitVideoPresigned(...args),
    submitVideoResponse: (...args: any[]) => mockSubmitVideoResponse(...args), // FIX: Component falls back to this when presigned fails
    completeInterview: (...args: any[]) => mockCompleteInterview(...args),
    getInterview: jest.fn(),
    terminateInterview: jest.fn(),
  },
}));

// ── Mock Zustand store ──────────────────────────────────────
const mockSetInterview = jest.fn();
const mockSetCurrentQuestionIndex = jest.fn();
const mockSetRecordingState = jest.fn();

let mockStoreState: any;

jest.mock("../../stores/useInterviewStore", () => ({
  useInterviewStore: (selector?: (state: any) => any) => {
    const state = {
      interview: mockStoreState.interview,
      currentQuestionIndex: mockStoreState.currentQuestionIndex,
      isRecording: mockStoreState.isRecording,
      setInterview: mockSetInterview,
      setCurrentQuestionIndex: mockSetCurrentQuestionIndex,
      setRecordingState: mockSetRecordingState,
      nextQuestion: jest.fn(),
      prevQuestion: jest.fn(),
    };
    return selector ? selector(state) : state;
  },
}));

// ── Mock hooks ──────────────────────────────────────────────
jest.mock("../../hooks/useInterviewEvents", () => ({
  useInterviewEvents: jest.fn(),
}));

jest.mock("../../hooks/useProctoring", () => ({
  useProctoring: () => ({
    violationCount: 0,
    isWarningVisible: false,
    isTerminated: false,
    lastViolationReason: null,
  }),
}));

// Mock child components to simplify testing the parent logic
jest.mock("./QuestionPresenter", () => {
  return function MockQuestionPresenter({
    questionText,
    onAudioComplete,
  }: {
    questionText: string;
    onAudioComplete?: () => void;
    audioUrl?: string | null;
    questionNumber?: number;
    totalQuestions?: number;
  }) {
    return (
      <div data-testid="avatar-player">
        <p>{questionText}</p>
        <button onClick={onAudioComplete} data-testid="avatar-done-btn">
          Finish Speaking
        </button>
      </div>
    );
  };
});

jest.mock("../VideoRecorder/VideoRecorder", () => {
  return function MockVideoRecorder({
    questionText,
    onRecordingComplete,
  }: {
    questionText: string;
    onRecordingComplete: (blob: Blob) => Promise<void>;
  }) {
    return (
      <div data-testid="video-recorder">
        <p>{questionText}</p>
        <button
          onClick={() =>
            onRecordingComplete(new Blob(["test-video"], { type: "video/webm" }))
          }
          data-testid="submit-recording-btn"
        >
          Submit Recording
        </button>
      </div>
    );
  };
});

jest.mock("./ProctoringWarning", () => ({
  ProctoringWarning: () => null,
}));

// ============================================================
// Test data
// ============================================================

const mockQuestions: InterviewQuestion[] = [
  {
    questionId: 1,
    questionText: "Tell me about yourself.",
    questionNumber: 1,
    category: "Behavioral",
    difficulty: "Easy",
    avatarVideoUrl: "https://example.com/avatar1.mp4",
    audioUrl: "/api/files/audio/question_1.mp3",
    answered: false,
  },
  {
    questionId: 2,
    questionText: "What is polymorphism?",
    questionNumber: 2,
    category: "Technical",
    difficulty: "Medium",
    avatarVideoUrl: "https://example.com/avatar2.mp4",
    audioUrl: "/api/files/audio/question_2.mp3",
    answered: false,
  },
  {
    questionId: 3,
    questionText: "Describe a challenging project.",
    questionNumber: 3,
    category: "Behavioral",
    difficulty: "Hard",
    avatarVideoUrl: "https://example.com/avatar3.mp4",
    audioUrl: "/api/files/audio/question_3.mp3",
    answered: false,
  },
];

const mockInitialData: InterviewDTO = {
  interviewId: 42,
  status: "IN_PROGRESS",
  type: "VIDEO",
  jobRoleTitle: "Software Engineer",
  startedAt: "2024-01-01T00:00:00Z",
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
    </MemoryRouter>,
  );

/** Render and click "Begin Interview" to pass the ready screen */
const renderAndStart = (props = {}) => {
  renderRoom(props);
  fireEvent.click(screen.getByRole("button", { name: /begin interview/i }));
};

// ============================================================
// Tests
// ============================================================

describe("InterviewRoom Component", () => {
  beforeEach(() => {
    jest.clearAllMocks();

    // Default store state: interview loaded, at question 0, not recording
    mockStoreState = {
      interview: mockInitialData,
      currentQuestionIndex: 0,
      isRecording: false,
    };
  });

  // ──────────────────────────────────────────────────────────
  // Ready Screen
  // ──────────────────────────────────────────────────────────

  describe("Ready Screen", () => {
    test('shows "Your Interview is Ready!" before starting', () => {
      renderRoom();
      expect(screen.getByText(/your interview is ready/i)).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /begin interview/i }),
      ).toBeInTheDocument();
    });

    test('clicking "Begin Interview" shows the question view', () => {
      renderRoom();
      fireEvent.click(screen.getByRole("button", { name: /begin interview/i }));
      expect(screen.getByText("Question 1 of 3")).toBeInTheDocument();
    });
  });

  // ──────────────────────────────────────────────────────────
  // Initial Render (after starting)
  // ──────────────────────────────────────────────────────────

  describe("Initial Render", () => {
    test('shows progress "Question 1 of 3"', () => {
      renderAndStart();
      expect(screen.getByText("Question 1 of 3")).toBeInTheDocument();
    });

    test("renders avatar player with first question", () => {
      renderAndStart();
      expect(screen.getByTestId("avatar-player")).toBeInTheDocument();
      expect(screen.getAllByText("Tell me about yourself.").length).toBeGreaterThanOrEqual(1);
    });

    test('renders "Skip Video & Start Recording" button', () => {
      renderAndStart();
      expect(
        screen.getByRole("button", { name: /skip video/i }),
      ).toBeInTheDocument();
    });
  });

  // ──────────────────────────────────────────────────────────
  // Question Flow: Avatar → Recorder
  // ──────────────────────────────────────────────────────────

  describe("Question Flow", () => {
    test("shows video recorder after avatar finishes speaking", () => {
      // Set isRecording to true to simulate store update after avatar done
      mockStoreState.isRecording = true;
      renderAndStart();

      expect(screen.getByTestId("video-recorder")).toBeInTheDocument();
    });

    test("calls setRecordingState when skip button is clicked", () => {
      renderAndStart();

      fireEvent.click(
        screen.getByRole("button", { name: /skip video/i }),
      );

      expect(mockSetRecordingState).toHaveBeenCalledWith(true);
    });
  });

  // ──────────────────────────────────────────────────────────
  // Video Submission
  // ──────────────────────────────────────────────────────────

  describe("Video Submission", () => {
    test("calls interviewService.submitVideoPresigned with correct params", async () => {
      mockSubmitVideoPresigned.mockResolvedValueOnce({ id: 1 });
      mockStoreState.isRecording = true;
      renderAndStart();

      fireEvent.click(screen.getByTestId("submit-recording-btn"));

      await waitFor(() => {
        expect(mockSubmitVideoPresigned).toHaveBeenCalledTimes(1);
        expect(mockSubmitVideoPresigned).toHaveBeenCalledWith(
          42, // interviewId
          1,  // questionId
          expect.any(Blob),
          expect.any(Function), // onProgress
        );
      });
    });

    test("shows error when video upload fails", async () => {
      // FIX: Must reject BOTH upload paths — presigned is tried first; on failure the
      // component falls back to legacy (submitVideoResponse). Error only shows if both fail.
      mockSubmitVideoPresigned.mockRejectedValueOnce(new Error("Upload failed"));
      mockSubmitVideoResponse.mockRejectedValueOnce(new Error("Upload failed"));
      mockStoreState.isRecording = true;
      renderAndStart();

      fireEvent.click(screen.getByTestId("submit-recording-btn"));

      await waitFor(() => {
        expect(screen.getByText(/upload failed/i)).toBeInTheDocument();
      });
    });
  });

  // ──────────────────────────────────────────────────────────
  // Interview Completion
  // ──────────────────────────────────────────────────────────

  describe("Interview Completion", () => {
    test("completes interview and navigates to /complete on last question submit", async () => {
      // Single question interview — immediately becomes the last
      const singleQuestionData: InterviewDTO = {
        ...mockInitialData,
        questions: [mockQuestions[0]],
      };
      mockStoreState.interview = singleQuestionData;
      mockStoreState.isRecording = true;

      mockSubmitVideoPresigned.mockResolvedValueOnce({ id: 1 });
      mockCompleteInterview.mockResolvedValueOnce(undefined);

      render(
        <MemoryRouter>
          <InterviewRoom interviewId={42} initialData={singleQuestionData} />
        </MemoryRouter>,
      );

      // Pass ready screen
      fireEvent.click(screen.getByRole("button", { name: /begin interview/i }));

      // Submit recording
      fireEvent.click(screen.getByTestId("submit-recording-btn"));

      await waitFor(() => {
        expect(mockCompleteInterview).toHaveBeenCalledWith(42);
      });

      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith("/interview/42/complete");
      });
    });

    test("shows submitting state while finishing interview", async () => {
      const singleQuestionData: InterviewDTO = {
        ...mockInitialData,
        questions: [mockQuestions[0]],
      };
      mockStoreState.interview = singleQuestionData;
      mockStoreState.isRecording = true;

      mockSubmitVideoPresigned.mockResolvedValueOnce({ id: 1 });
      mockCompleteInterview.mockReturnValueOnce(new Promise(() => { })); // Never resolves

      render(
        <MemoryRouter>
          <InterviewRoom interviewId={42} initialData={singleQuestionData} />
        </MemoryRouter>,
      );

      fireEvent.click(screen.getByRole("button", { name: /begin interview/i }));
      fireEvent.click(screen.getByTestId("submit-recording-btn"));

      await waitFor(() => {
        expect(screen.getByText(/submitting your interview/i)).toBeInTheDocument();
      });
    });
  });

  // ──────────────────────────────────────────────────────────
  // Error Handling
  // ──────────────────────────────────────────────────────────

  describe("Error Handling", () => {
    test("error alert can be dismissed", async () => {
      // FIX: Must reject BOTH upload paths — error is shown only when both presigned
      // and the legacy fallback fail.
      mockSubmitVideoPresigned.mockRejectedValueOnce(new Error("Upload failed"));
      mockSubmitVideoResponse.mockRejectedValueOnce(new Error("Upload failed"));
      mockStoreState.isRecording = true;
      renderAndStart();

      fireEvent.click(screen.getByTestId("submit-recording-btn"));

      await waitFor(() => {
        expect(screen.getByText(/upload failed/i)).toBeInTheDocument();
      });

      const closeButton = screen.getByRole("button", { name: /close/i });
      fireEvent.click(closeButton);

      await waitFor(() => {
        expect(screen.queryByText(/upload failed/i)).not.toBeInTheDocument();
      });
    });
  });
});
