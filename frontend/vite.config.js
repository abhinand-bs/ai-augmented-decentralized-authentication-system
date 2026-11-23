import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the backend, so the SPA and the API share one origin
// (http://localhost:5173) and the backend needs no CORS configuration.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // BACKEND_URL lets a second backend (e.g. on a spare port) serve the dev server; default: localhost:8080.
    proxy: { '/api': process.env.BACKEND_URL || 'http://localhost:8080' },
  },
});
