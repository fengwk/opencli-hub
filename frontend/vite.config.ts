import path from 'node:path'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Backend target for local development. Configurable so the dev server can
// forward both REST (`/api`) and the VNC WebSocket
// (`/api/instances/*/vnc`) to any Hub instance.
const apiTarget =
  process.env.OPENCLI_HUB_API_TARGET || process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    host: '0.0.0.0',
    proxy: {
      // `ws: true` upgrades WebSocket requests under `/api`, which covers the
      // Hub VNC endpoint `/api/instances/{id}/vnc` while regular REST calls to
      // `/api/**` are proxied over HTTP.
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
