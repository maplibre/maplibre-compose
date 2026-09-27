# MapLibre GL JS patches

The submodule in `third_party/maplibre-gl-js` pins upstream GL JS 6.9.1. Its
version must match `maplibre-js` in `gradle/libs.versions.toml`. The build
applies these numbered patches in filename order before compiling the engine:

| Patch                                  | Upstream                                                                                                       | Purpose                                                                      |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `0001-camera-bounds-map-padding.patch` | [#8482](https://github.com/maplibre/maplibre-gl-js/pull/8482), head `b315940e195d74fe3fc9a577b2a2ca852f931f98` | Query bounds using destination camera padding without changing the live map. |
| `0002-camera-bounds-pitch.patch`       | [#8483](https://github.com/maplibre/maplibre-gl-js/pull/8483), head `0f4cfa3ea46ea424d58ff424f7241368dcf2e98a` | Account for pitch when fitting bounds.                                       |

The second PR is stacked on the first; its patch contains only the pitch change.
The backports retain upstream camera unit tests and adapt type imports to 6.9.1.
They omit changelog entries, generated bundle-size expectations, and the
unrelated browser-suite marker snapshot adjustment. The pitch fit retains
upstream's single-pass approximation; it does not promise an exact perspective
fit at every pitch and extent. Compose retains its iterative refinement after
the engine fit to preserve tight framing in its existing bounds API.

## Maintain the patches

`mise run deps:maplibre-gl-js` initializes the submodule and applies the patches
with `git apply --index`. Its HEAD remains the upstream pin. A stamp in the
submodule's Git directory records the resulting source and patch contents, so a
second sync is a no-op. Changed patches are reapplied from the pin. Local source
edits stop the sync instead of being overwritten.

To add a patch, edit the synced source, run its tests, and save the unstaged
diff with `git -C third_party/maplibre-gl-js diff --binary` into the next
numbered patch file. Then run `.mise/bin/sync-maplibre-gl-js force` to reset
your edits and apply the updated stack. Do not commit inside the submodule or
stage a locally patched commit as its pin.

To update upstream, first save any local source edits, check out the new
upstream commit, and stage the submodule pointer. Update the catalog version,
drop patches already included upstream, adapt the remaining patches, and
force-sync. Follow
[the JS upgrade skill](../../.agents/skills/bump-maplibre-gl-js/SKILL.md) for
binding and compatibility checks.

## Build and distribution

`mise run build:maplibre-gl-js` installs upstream's locked build dependencies,
runs its code generators and production build, and bundles a standalone worker.
The result is cached in `lib/maplibre-compose/build/maplibre-gl-js`. Gradle
invokes this task for JS packaging. Native-only builds do not compile GL JS.

The engine and worker are included under `maplibre-gl/` in the JS KLIB. Kotlin's
normal Maven dependency handling extracts the JavaScript modules; consumers need
no npm dependency, extraction task, webpack configuration, or engine
publication. Project dependencies stage the same files in KGP's local npm
module. The default worker is embedded as text and instantiated through a Blob
URL. A host whose CSP excludes blob workers can serve `maplibre-gl/worker.mjs`
from the published KLIB and call `configureMapLibreWorker` before creating maps.
It must use the worker from the same Compose release, including its patches.

`mise run test:maplibre-gl-js` runs the carried upstream camera tests.
`mise run test:js` runs these, the normal browser suites, and an independent
consumer of locally published Maven artifacts. `mise run test:js:publishing`
runs just the publication check after browser tooling has been installed. That
consumer renders and queries a GeoJSON feature with external browser requests
blocked, so it also exercises the worker shipped to users.
