import { createRequire } from "node:module";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const source = new URL("../../third_party/maplibre-gl-js/", import.meta.url);
const output = new URL("../../lib/maplibre-compose/build/maplibre-gl-js/", import.meta.url);
const catalog = await readFile(new URL("../../gradle/libs.versions.toml", import.meta.url), "utf8");
const pinnedVersion = catalog.match(/^maplibre-js = "([^"]+)"/m)[1];
const upstreamVersion = JSON.parse(await readFile(new URL("package.json", source), "utf8")).version;
if (upstreamVersion !== pinnedVersion) {
  throw new Error(`GL JS submodule is ${upstreamVersion}, but the version catalog pins ${pinnedVersion}`);
}
const require = createRequire(new URL("package.json", source));
const { build } = await import(require.resolve("rolldown"));
await mkdir(output, { recursive: true });

// A single worker has no sibling imports and can also be self-hosted under a strict CSP.
await build({
  input: fileURLToPath(new URL("dist/maplibre-gl-worker.mjs", source)),
  platform: "browser",
  output: { file: fileURLToPath(new URL("worker.mjs", output)), format: "esm", minify: true },
});
const worker = await readFile(new URL("worker.mjs", output), "utf8");
await writeFile(new URL("worker-source.mjs", output), `export default ${JSON.stringify(worker)};\n`);
for (const name of ["maplibre-gl.mjs", "maplibre-gl-shared.mjs", "maplibre-gl.d.ts", "LICENSE.txt"]) {
  const path = name === "LICENSE.txt" ? name : `dist/${name}`;
  const contents = await readFile(new URL(path, source), "utf8");
  await writeFile(new URL(name, output), contents.replace(/^\/\/# sourceMappingURL=.*$/gm, ""));
}
await writeFile(new URL("index.mjs", output), await readFile(new URL("runtime.mjs", import.meta.url)));
