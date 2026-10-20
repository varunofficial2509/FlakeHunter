import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    // The browser only talks to Vite (one origin, so no CORS); Vite forwards /api to the gateway.
    proxy: { "/api": "http://localhost:8080" },
  },
});
