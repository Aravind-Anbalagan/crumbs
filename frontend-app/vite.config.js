import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  
  // 1. Build Config: Sends your compiled React app and public folder 
  // directly into Spring Boot's static folder for production.
  build: {
    outDir: '../src/main/resources/static', 
    emptyOutDir: true, 
  },
  
  // 2. Server Config: Acts as a middleman during local development, 
  // routing any '/api' requests from React on port 5173 to Spring Boot on 8080.
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      }
    }
  }
})