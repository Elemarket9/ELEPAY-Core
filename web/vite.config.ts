import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Built assets are served by the Spring Boot service (copied into static/ by the Docker build).
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/v1": "http://127.0.0.1:8080", "/dev": "http://127.0.0.1:8080" } },
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 300 },
});
