import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// 前后端分离：dev 时 /api 代理到 Spring Boot（:8081）；build 产物 web/dist 可由后端托管
export default defineConfig({
  plugins: [svelte()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', emptyOutDir: true },
})
