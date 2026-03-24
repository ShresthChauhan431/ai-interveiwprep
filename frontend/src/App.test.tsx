import React from 'react';
import { render, screen } from '@testing-library/react';
import App from './App';

// Smoke test — verifies the app renders without crashing
test('renders the app without crashing', () => {
  // App renders the LandingPage at "/" by default in a MemoryRouter context.
  // We just verify it doesn't throw during initial render.
  expect(() => render(<App />)).not.toThrow();
});

