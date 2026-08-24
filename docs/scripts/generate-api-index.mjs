/**
 * Builds a symbol index of the Dokka output for the `<Api>` component.
 *
 * Scans `docs/public/api/lib/<module>/<package>/` and writes
 * `docs/src/generated/api-index.json`, mapping each package and each symbol to
 * the Dokka pages that document it. The component resolves against this index
 * at build time, so a page that names a missing symbol fails the site build.
 */
import { mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const docsDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const apiDir = join(docsDir, "public", "api", "lib");
const outFile = join(docsDir, "src", "generated", "api-index.json");

/** Decodes Dokka's kebab-case: `-camera-state` -> `CameraState`. */
function decodeKebab(name) {
  return name.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());
}

function entries(dir) {
  return readdirSync(dir, { withFileTypes: true });
}

const packages = {};
const symbols = {};

function addSymbol(name, entry) {
  (symbols[name] ??= []).push(entry);
}

/**
 * Indexes one symbol directory. `prefix` is the dot-joined outer symbol path;
 * a `-companion` directory is transparent, so companion members resolve as
 * members of the enclosing symbol.
 */
function scanSymbolDir(dir, urlBase, module, pkg, prefix) {
  addSymbol(prefix, { module, pkg, url: `${urlBase}/index.html` });
  for (const entry of entries(dir)) {
    if (entry.isDirectory()) {
      const nested = entry.name === "-companion" ? prefix : `${prefix}.${decodeKebab(entry.name)}`;
      const nestedUrl = `${urlBase}/${entry.name}`;
      if (entry.name === "-companion") {
        scanCompanionDir(join(dir, entry.name), nestedUrl, module, pkg, prefix);
      } else {
        scanSymbolDir(join(dir, entry.name), nestedUrl, module, pkg, nested);
      }
    } else if (isMemberPage(entry.name)) {
      const member = decodeKebab(entry.name.replace(/\.html$/, ""));
      if (member !== prefix.split(".").at(-1)) {
        addSymbol(`${prefix}.${member}`, { module, pkg, url: `${urlBase}/${entry.name}` });
      }
    }
  }
}

function scanCompanionDir(dir, urlBase, module, pkg, prefix) {
  for (const entry of entries(dir)) {
    if (entry.isDirectory()) {
      scanSymbolDir(
        join(dir, entry.name),
        `${urlBase}/${entry.name}`,
        module,
        pkg,
        `${prefix}.${decodeKebab(entry.name)}`,
      );
    } else if (isMemberPage(entry.name)) {
      const member = decodeKebab(entry.name.replace(/\.html$/, ""));
      addSymbol(`${prefix}.${member}`, { module, pkg, url: `${urlBase}/${entry.name}` });
    }
  }
}

/** Platform-prefixed pages like `[maplibre-native]foo.html` duplicate a member. */
function isMemberPage(name) {
  return name.endsWith(".html") && name !== "index.html" && !name.startsWith("[");
}

for (const module of entries(apiDir)) {
  if (!module.isDirectory()) continue;
  const moduleDir = join(apiDir, module.name);
  for (const pkg of entries(moduleDir)) {
    if (!pkg.isDirectory()) continue;
    const pkgDir = join(moduleDir, pkg.name);
    const pkgUrl = `${module.name}/${pkg.name}`;
    (packages[pkg.name] ??= []).push({
      module: module.name,
      url: `${pkgUrl}/index.html`,
    });
    for (const entry of entries(pkgDir)) {
      if (entry.isDirectory()) {
        scanSymbolDir(
          join(pkgDir, entry.name),
          `${pkgUrl}/${entry.name}`,
          module.name,
          pkg.name,
          decodeKebab(entry.name),
        );
      } else if (isMemberPage(entry.name)) {
        const symbol = decodeKebab(entry.name.replace(/\.html$/, ""));
        addSymbol(symbol, { module: module.name, pkg: pkg.name, url: `${pkgUrl}/${entry.name}` });
      }
    }
  }
}

// A companion factory function can share its name with the class it builds
// (e.g. `Anchor.Replace`). Within one module and package, keep the type page.
for (const [name, hits] of Object.entries(symbols)) {
  const deduped = new Map();
  for (const hit of hits) {
    const key = `${hit.module}/${hit.pkg}`;
    const kept = deduped.get(key);
    if (!kept || (hit.url.endsWith("/index.html") && !kept.url.endsWith("/index.html"))) {
      deduped.set(key, hit);
    }
  }
  symbols[name] = [...deduped.values()];
}

mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, JSON.stringify({ packages, symbols }));

const symbolCount = Object.keys(symbols).length;
const packageCount = Object.keys(packages).length;
console.log(`api-index: ${symbolCount} symbols in ${packageCount} packages`);
