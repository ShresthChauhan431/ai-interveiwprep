import React from "react";
import {
  BrowserRouter as Router,
  Route,
  Routes,
  Navigate,
  useNavigate,
  useParams,
  useLocation,
} from "react-router-dom";
import {
  ThemeProvider,
  CssBaseline,
  Box,
  CircularProgress,
  Typography,
} from "@mui/material";
import { AuthProvider, useAuth } from "./context/AuthContext";
import theme from "./theme";
import ErrorBoundary from "./components/Common/ErrorBoundary"; // FIX: Import ErrorBoundary to catch render errors and show fallback screen

// Page imports
import Login from "./components/Auth/Login";
import Register from "./components/Auth/Register";
import Dashboard from "./components/Dashboard/Dashboard";
import CommunicationLive from "./components/CommunicationLive/CommunicationLive";
import InterviewStart from "./components/Interview/InterviewStart";
import InterviewRoom from "./components/Interview/InterviewRoom";
import InterviewComplete from "./components/Interview/InterviewComplete";
import InterviewReview from "./components/Interview/InterviewReview";
import InterviewDisqualified from "./components/Interview/InterviewDisqualified"; // FIX: Import disqualified page for proctoring termination route (Issue 3)
import InterviewHistoryPage from "./components/Interview/InterviewHistoryPage";
import Navigation from "./components/Common/Navigation";
import LandingPage from "./components/LandingPage/LandingPage";

// ============================================================
// Route Guards
// ============================================================

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          height: "100vh",
        }}
      >
        <CircularProgress size={40} sx={{ mb: 2 }} />
        <Typography color="text.secondary">Loading...</Typography>
      </Box>
    );
  }

  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
};

const PublicRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          height: "100vh",
        }}
      >
        <CircularProgress size={40} />
      </Box>
    );
  }

  return isAuthenticated ? (
    <Navigate to="/dashboard" replace />
  ) : (
    <>{children}</>
  );
};

// ============================================================
// Wrapper pages (extract route params for components that need them)
// ============================================================

const InterviewStartWrapper: React.FC = () => {
  const nav = useNavigate();
  return (
    <InterviewStart
      onStart={(interviewId: number) =>
        nav(`/interview/${interviewId}/session`)
      }
    />
  );
};

const InterviewSessionPage: React.FC = () => {
  const { interviewId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();

  // InterviewDTO can come from InterviewStart (via router state) or be fetched
  const [initialData, setInitialData] = React.useState<any | null>(
    (location.state as any)?.interviewData ?? null,
  );
  const [loading, setLoading] = React.useState(!initialData);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (initialData) return; // Already have data from router state
    let cancelled = false;

    const fetchInterview = async () => {
      try {
        const { interviewService } =
          await import("./services/interview.service");
        const data = await interviewService.getInterview(Number(interviewId));
        if (!cancelled) {
          setInitialData(data);
          setLoading(false);
        }
      } catch (err: any) {
        if (!cancelled) {
          setError(err.message || "Failed to load interview.");
          setLoading(false);
        }
      }
    };

    fetchInterview();
    return () => {
      cancelled = true;
    };
  }, [interviewId, initialData]);

  if (loading) {
    return (
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          height: "60vh",
        }}
      >
        <CircularProgress size={48} sx={{ mb: 2 }} />
        <Typography color="text.secondary">
          Loading interview session...
        </Typography>
      </Box>
    );
  }

  if (error || !initialData) {
    return (
      <Box sx={{ maxWidth: 480, mx: "auto", py: 8, textAlign: "center" }}>
        <Typography variant="h6" color="error" gutterBottom>
          {error || "Interview not found."}
        </Typography>
        <Box
          component="button"
          onClick={() => navigate("/dashboard")}
          sx={{
            mt: 2,
            px: 3,
            py: 1,
            bgcolor: "primary.main",
            color: "#fff",
            border: "none",
            borderRadius: 2,
            cursor: "pointer",
            fontWeight: 600,
          }}
        >
          Back to Dashboard
        </Box>
      </Box>
    );
  }

  return (
    <InterviewRoom
      interviewId={Number(interviewId)}
      initialData={initialData}
    />
  );
};

const InterviewCompletePage: React.FC = () => {
  const { interviewId } = useParams();
  return <InterviewComplete interviewId={Number(interviewId)} />;
};

const InterviewReviewPage: React.FC = () => {
  const { interviewId } = useParams();
  return <InterviewReview interviewId={Number(interviewId)} />;
};

// ============================================================
// 404 Page
// ============================================================

const NotFoundPage: React.FC = () => {
  const navigate = useNavigate();

  return (
    <Box
      sx={{
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        textAlign: "center",
      }}
    >
      <Typography variant="h1" fontWeight={800} color="text.disabled">
        404
      </Typography>
      <Typography variant="h5" gutterBottom>
        Page Not Found
      </Typography>
      <Typography color="text.secondary" sx={{ mb: 3 }}>
        The page you're looking for doesn't exist or has been moved.
      </Typography>
      <Box
        component="button"
        onClick={() => navigate("/")}
        sx={{
          px: 4,
          py: 1.2,
          fontSize: "1rem",
          fontWeight: 600,
          bgcolor: "primary.main",
          color: "#fff",
          border: "none",
          borderRadius: 2,
          cursor: "pointer",
          "&:hover": { bgcolor: "primary.dark" },
        }}
      >
        Go Home
      </Box>
    </Box>
  );
};

// ============================================================
// Profile Page (placeholder — can be expanded later)
// ============================================================

const ProfilePage: React.FC = () => {
  const { user } = useAuth();

  return (
    <Box sx={{ maxWidth: 600, mx: "auto", py: 4, px: 3 }}>
      <Typography variant="h4" fontWeight={700} gutterBottom>
        Profile
      </Typography>
      <Box
        sx={{
          p: 3,
          border: "1px solid",
          borderColor: "divider",
          borderRadius: 3,
          bgcolor: "background.paper",
        }}
      >
        <Typography variant="body1">
          <strong>Name:</strong> {user?.name || "—"}
        </Typography>
        <Typography variant="body1" sx={{ mt: 1 }}>
          <strong>Email:</strong> {user?.email || "—"}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Member since{" "}
          {user?.createdAt
            ? new Date(user.createdAt).toLocaleDateString()
            : "—"}
        </Typography>
      </Box>
    </Box>
  );
};

// ============================================================
// App Component
// ============================================================

const App: React.FC = () => {
  return (
    <ErrorBoundary>
      {" "}
      {/* FIX: Wrap entire App in ErrorBoundary so render crashes show user-friendly fallback instead of blank page */}
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <AuthProvider>
          <Router>
            <Navigation />
            <Routes>
              {/* Public routes */}
              <Route path="/" element={<LandingPage />} />
              <Route
                path="/login"
                element={
                  <PublicRoute>
                    <Login />
                  </PublicRoute>
                }
              />
              <Route
                path="/register"
                element={
                  <PublicRoute>
                    <Register />
                  </PublicRoute>
                }
              />
              {/* Protected routes */}
              <Route
                path="/communication-live"
                element={
                  <ProtectedRoute>
                    <CommunicationLive />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/dashboard"
                element={
                  <ProtectedRoute>
                    <Dashboard />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/interview/start"
                element={
                  <ProtectedRoute>
                    <InterviewStartWrapper />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/interview/:interviewId/session"
                element={
                  <ProtectedRoute>
                    <InterviewSessionPage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/interview/:interviewId"
                element={
                  <ProtectedRoute>
                    <InterviewCompletePage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/interview/:interviewId/complete"
                element={
                  <ProtectedRoute>
                    <InterviewCompletePage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/interview/:interviewId/review"
                element={
                  <ProtectedRoute>
                    <InterviewReviewPage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/profile"
                element={
                  <ProtectedRoute>
                    <ProfilePage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/history"
                element={
                  <ProtectedRoute>
                    <InterviewHistoryPage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/interview-disqualified"
                element={
                  <ProtectedRoute>
                    <InterviewDisqualified />
                  </ProtectedRoute>
                }
              />{" "}
              {/* FIX: Route for proctoring disqualification page (Issue 3) */}
              {/* 404 */}
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </Router>
        </AuthProvider>
      </ThemeProvider>
    </ErrorBoundary> // FIX: Close ErrorBoundary wrapper
  );
};

export default App;
