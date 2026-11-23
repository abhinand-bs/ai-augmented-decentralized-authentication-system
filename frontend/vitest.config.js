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
      // Just below the values measured on 2026-11-23 (36.9 / 33.7 / 29.6 / 36.3); raise as tests are added.
      thresholds: { statements: 35, branches: 32, functions: 28, lines: 35 },
    },
  },
});
