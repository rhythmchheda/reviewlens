import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, the browser talks only to Vite (port 5173). Vite forwards API calls to Spring Boot,
// so the backend needs no CORS config. Override the target with BACKEND_URL if 8081 is taken.
const backend = process.env.BACKEND_URL || 'http://localhost:8081';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/products': backend,
      '/actuator': backend,
    },
  },
});
