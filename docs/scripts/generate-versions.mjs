// Writes the versions the pages quote into src/generated/versions.json.
//
// Release and snapshot come from the Git tags, via `version-args` on stdin. The
// MapLibre platform versions come from the Gradle version catalog, which stays
// the one place they are pinned.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "smol-toml";

const docsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = path.dirname(docsDir);
const outputPath = path.join(docsDir, "src/generated/versions.json");

function readGradleProperties(text) {
  const properties = new Map();
  for (const line of text.split("\n")) {
    const match = /^-P([^=]+)=(.*)$/.exec(line.trim());
    if (match) properties.set(match[1], match[2]);
  }
  return properties;
}

function demand(map, key, source) {
  const value = map.get(key);
  if (value === undefined || value === "") {
    throw new Error(`${source} did not supply ${key}`);
  }
  return value;
}

const stdin = fs.readFileSync(0, "utf8");
const properties = readGradleProperties(stdin);
if (properties.size === 0) {
  throw new Error(
    "no -P flags on stdin; pipe `.mise/bin/version-args build` into this script",
  );
}

const catalogPath = path.join(repoRoot, "gradle/libs.versions.toml");
const catalog = new Map(
  Object.entries(parse(fs.readFileSync(catalogPath, "utf8")).versions ?? {}),
);

const versions = {
  release: demand(properties, "maplibreReleaseVersion", "version-args"),
  snapshot: demand(properties, "maplibreSnapshotVersion", "version-args"),
  maplibreIos: demand(catalog, "maplibre-ios", catalogPath),
  maplibreJs: demand(catalog, "maplibre-js", catalogPath),
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(versions, null, 2)}\n`);
