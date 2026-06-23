import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    // Go up one level from frontend-app, then straight into your resources
    outDir: '../src/main/resources/static', 
    emptyOutDir: true, 
  }
})