import { defineConfig } from "vite";

export default defineConfig({
  server: {
    host: "0.0.0.0",
    port: 5173,
    // ngrok 등 터널을 통해 들어오는 요청을 허용한다.
    allowedHosts: true,
  },
});
