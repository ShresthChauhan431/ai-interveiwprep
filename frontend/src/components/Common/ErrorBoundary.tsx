import React from 'react';
import { Box, Typography, Button } from '@mui/material';

// FIX: ErrorBoundary catches React render errors and shows a user-friendly fallback screen instead of a blank white page

interface ErrorBoundaryProps {
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null }; // FIX: Initialize with no error state
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    // FIX: Update state so next render shows the fallback UI instead of crashing
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    // FIX: Log error details for debugging — this is the only place we can capture React component stack traces
    console.error('ErrorBoundary caught an error:', error, errorInfo);
  }

  render(): React.ReactNode {
    if (this.state.hasError) {
      // FIX: Show a friendly error screen with a refresh button instead of a blank white page
      return (
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            minHeight: '100vh',
            gap: 2,
            px: 3,
            textAlign: 'center',
          }}
        >
          <Typography variant="h5" fontWeight={600}>
            Something went wrong
          </Typography>
          <Typography color="text.secondary" sx={{ maxWidth: 400 }}>
            An unexpected error occurred. Please refresh the page to continue.
          </Typography>
          {/* FIX: Show error message in development for easier debugging */}
          {process.env.NODE_ENV === 'development' && this.state.error && (
            <Typography
              variant="body2"
              color="error"
              sx={{
                mt: 1,
                p: 2,
                bgcolor: 'error.50',
                borderRadius: 1,
                maxWidth: 500,
                wordBreak: 'break-word',
                fontFamily: 'monospace',
                fontSize: '0.75rem',
              }}
            >
              {this.state.error.message}
            </Typography>
          )}
          <Button
            onClick={() => window.location.reload()} // FIX: Full page reload to reset all state and recover from error
            variant="contained"
            sx={{ mt: 2 }}
          >
            Refresh Page
          </Button>
        </Box>
      );
    }

    return this.props.children; // FIX: Render children normally when no error
  }
}

export default ErrorBoundary;
