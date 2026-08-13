// MapLibre GL JS 6 parses vector tiles in maplibre-gl-worker.mjs, which imports
// maplibre-gl-shared.mjs as a sibling. Webpack must emit both files under those
// names so the worker's relative import resolves. MapLibre.initialize() points
// MapLibre at the worker with setWorkerUrl.

const fs = require("fs");
const path = require("path");

const MAPLIBRE_DIST = path.join(
  path.dirname(require.resolve("maplibre-gl/package.json")),
  "dist",
);
const WORKER_FILES = ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"];

class CopyMapLibreWorkerPlugin {
  apply(compiler) {
    compiler.hooks.thisCompilation.tap(
      "CopyMapLibreWorkerPlugin",
      (compilation) => {
        compilation.hooks.processAssets.tap(
          {
            name: "CopyMapLibreWorkerPlugin",
            stage: compiler.webpack.Compilation.PROCESS_ASSETS_STAGE_ADDITIONAL,
          },
          () => {
            for (const file of WORKER_FILES) {
              const source = new compiler.webpack.sources.RawSource(
                fs.readFileSync(path.join(MAPLIBRE_DIST, file)),
              );
              if (compilation.getAsset(file)) {
                compilation.updateAsset(file, source);
              } else {
                compilation.emitAsset(file, source);
              }
            }
          },
        );
      },
    );
  }
}

config.plugins.push(new CopyMapLibreWorkerPlugin());
