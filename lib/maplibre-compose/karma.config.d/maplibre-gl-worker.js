// MapLibre GL JS 6 loads vector tiles in a module worker. Karma serves nothing it
// is not told about, so these two files are copied next to the test bundle and
// proxied to the names MapLibre.initialize() asks for.

const fs = require("fs");
const path = require("path");

const MAPLIBRE_DIST = path.join(
  path.dirname(require.resolve("maplibre-gl/package.json")),
  "dist",
);
const WORKER_FILES = ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"];
const destDir = path.join(config.basePath, "kotlin");
fs.mkdirSync(destDir, { recursive: true });

config.files = config.files || [];
config.proxies = config.proxies || {};
config.mime = Object.assign({ "text/javascript": ["js", "mjs"] }, config.mime);

for (const file of WORKER_FILES) {
  fs.copyFileSync(path.join(MAPLIBRE_DIST, file), path.join(destDir, file));
  config.files.push({
    pattern: path.join(destDir, file),
    included: false,
    served: true,
    watched: false,
  });
  config.proxies["/" + file] = "/base/kotlin/" + file;
}
