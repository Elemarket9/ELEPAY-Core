import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Built assets are served by the Spring Boot service (copied into static/ by the Docker build).
const backendProxy = {
  target: "http://127.0.0.1:8080",
  changeOrigin: true,
  configure: (proxy: { on: (event: string, cb: (proxyReq: { setHeader: (k: string, v: string) => void }) => void) => void }) => {
    proxy.on("proxyReq", (proxyReq) => {
      proxyReq.setHeader("origin", "http://127.0.0.1:8080");
    });
  },
};

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/v1": backendProxy, "/dev": backendProxy } },
  build: { outDir: "dist", sourcemap: false, chunkSizeWarningLimit: 300 },
});
