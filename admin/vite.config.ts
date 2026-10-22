/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// The dashboard and the API share an origin: in development Vite proxies /v1 to the Ktor server, so the session
// cookie stays first-party (SameSite=Strict, HttpOnly) and no CORS is needed. In production, serve dist/ and the API
// from the same host (see README).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: { '/v1': { target: env.VITE_API_PROXY_TARGET || 'http://localhost:8080', changeOrigin: false } },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  }
})
