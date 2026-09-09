/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // dev: same-origin API and WebSocket through the Vite proxy (production serves the bundle behind nginx/Caddy with /api proxied)
    proxy: {
      '/api': { target: process.env.HEJJE_API_TARGET ?? 'http://localhost:8080', changeOrigin: false },
      '/ws': { target: process.env.HEJJE_API_TARGET ?? 'http://localhost:8080', ws: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: [],
    include: ['tests/**/*.test.{ts,tsx}'],
  },
});
