import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

// Gradle compiles the Kotlin app and everything it loads at runtime -- the webpack bundle, skiko's
// wasm, the Compose resource directories -- into one distribution directory. Vite serves that
// directory as static content and copies it into the built site; it bundles nothing itself.
const distributions = {
  development: "../common/build/dist/js/developmentExecutable",
  production: "../common/build/dist/js/productionExecutable",
};

export default defineConfig(({ mode }) => ({
  publicDir: fileURLToPath(
    new URL(distributions[mode] ?? distributions.production, import.meta.url),
  ),
  build: { outDir: "build/dist", emptyOutDir: true },
}));
