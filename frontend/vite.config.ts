import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The dashboard is served by the Spring application from /dashboard/, on the same origin as the
 * /ui API it calls. Same-origin is what lets the session cookie work without CORS, so the base
 * path here has to match the path the backend serves the bundle from.
 */
export default defineConfig({
  base: '/dashboard/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // Hashed filenames let the backend cache assets while revalidating the shell.
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 700,
  },
  server: {
    port: 5173,
    strictPort: true,
    // Development proxy so the dev server is same-origin with the API too. Without this the browser
    // would treat /ui as cross-origin and refuse to send the session cookie.
    proxy: {
      '/ui': { target: process.env.BACKEND_ORIGIN ?? 'http://localhost:8080', changeOrigin: false },
      '/actuator': { target: process.env.BACKEND_ORIGIN ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
});
