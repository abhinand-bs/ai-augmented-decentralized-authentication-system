import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    include: ['src/**/*.test.{js,jsx}'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/**/*.test.{js,jsx}', 'src/test/**', 'src/main.jsx'],
      reporter: ['text-summary', 'json-summary', 'html'],
      // Just below the values measured on 2026-11-23 (40.4 / 38.3 / 33.0 / 40.0); raise as tests are added.
      thresholds: { statements: 39, branches: 37, functions: 32, lines: 39 },
    },
  },
});
