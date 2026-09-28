# MapLibre GL JS patches

`third_party/maplibre-gl-js` pins upstream; its version must match `maplibre-js`
in `gradle/libs.versions.toml`. Numbered patches apply in filename order. Gradle
builds the patched engine and packages it with its worker in the JS artifact.

| Patch                                  | Upstream                                                      | Purpose                                     |
| -------------------------------------- | ------------------------------------------------------------- | ------------------------------------------- |
| `0001-camera-bounds-map-padding.patch` | [#8482](https://github.com/maplibre/maplibre-gl-js/pull/8482) | Fit bounds with destination camera padding. |
| `0002-camera-bounds-pitch.patch`       | [#8483](https://github.com/maplibre/maplibre-gl-js/pull/8483) | Account for pitch when fitting bounds.      |

## Maintenance

- `mise run deps:maplibre-gl-js` initializes the submodule and applies patches.
- `mise run build:maplibre-gl-js` builds the engine and worker.
- `mise run test:js` runs the Compose browser tests against the patched engine.

To add a patch, sync first, edit the source, and save
`git -C third_party/maplibre-gl-js diff --binary` as the next numbered patch.
Applied patches are staged, so this captures only your new edits. Export edits
before running `.mise/bin/sync-maplibre-gl-js force`, which discards them and
reapplies the stack. Keep the submodule pin on an upstream commit. After adding
or adapting a patch, run the upstream tests it touches, such as
`npm run test-unit -- src/ui/camera.test.ts` in the submodule.

When updating upstream, stage the new submodule pin, update the catalog version,
drop merged patches, adapt the rest, and force-sync. Follow the
[JS upgrade skill](../../.agents/skills/bump-maplibre-gl-js/SKILL.md) for
validation.
