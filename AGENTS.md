## Pull requests

When you open a pull request, write **Description** and **Test plan** in at most
one sentence of prose each. I will expand the PR description if more detail is
needed. More context: [AI_POLICY.md](./AI_POLICY.md).

## Searching vendored MapLibre Native codebase

When searching the vendored maplibre-native codebase:

- Location: Look in `lib/maplibre-native-bindings-jni/vendor/maplibre-native/`
- Key Directories:
  - `platform/linux/` - Linux-specific code (includes linux.cmake)
  - `platform/windows/` - Windows-specific code (includes windows.cmake)
  - `platform/darwin/` - macOS/iOS-specific code (includes darwin.cmake)
  - `platform/default/` - Cross-platform code
  - `include/mbgl/` - Public headers
  - `src/mbgl/` - Implementation files
- Common Search Patterns:
  - Platform-specific cmake: `platform/*/platform/*.cmake`
  - MLN options: `option(MLN*WITH*\*`
  - Compiler flags: `target_compile_options`, `target_link_options`
  - Feature detection: `MLN_WITH_OPENGL`, `MLN_WITH_VULKAN`

## Development Commands

### Building and Running

- **Run desktop demo:** `./gradlew :demo-app:run`
- **Run web demo:** `./gradlew :demo-app:jsRun`
- **Build all modules:** `./gradlew build`
- **Clean build:** `./gradlew clean`

### Documentation

- **Generate docs:** `./gradlew generateDocs` (builds both MkDocs site and Dokka
  API reference)
- **Build MkDocs only:** `./gradlew mkdocsBuild`
- **Build API docs only:** `./gradlew dokkaGenerate`

### Testing

Tests are located in platform-specific source sets:

- Android device tests: `src/androidDeviceTest`
- Android host tests: `src/androidHostTest`
- iOS tests: `src/iosTest`
- Common tests: `src/commonTest`

## Architecture Overview

MapLibre Compose is a Kotlin Multiplatform wrapper around MapLibre SDKs for
rendering interactive maps across Android, iOS, Desktop, and Web platforms.

### Project Structure

- **`lib/`**: Core library modules
  - `maplibre-compose`: Main map composables and core functionality
  - `maplibre-compose-material3`: Material 3 themed UI components
  - `maplibre-compose-gms`: Google location services components
  - `maplibre-js-bindings`: Kotlin/JS bindings for MapLibre GL JS
    - This wraps the TypeScript library whose original types are available at
      build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts
  - `maplibre-native-bindings`: Kotlin/JVM bindings for MapLibre Native
  - `maplibre-native-bindings-jni`: C++ library required by
    `maplibre-native-bindings`
    - This wraps the C++ library vendored at
      lib/maplibre-native-bindings-jni/vendor/maplibre-native
- **`demo-app/`**: Multiplatform demo application
- **`iosApp/`**: iOS-specific demo app wrapper
- **`buildSrc/`**: Custom Gradle build conventions

### Key Packages

- `org.maplibre.compose.map`: Core map composable and components
- `org.maplibre.compose.camera`: Camera controls and positioning
- `org.maplibre.compose.layers`: Layer composables for map visualization
- `org.maplibre.compose.sources`: Data source composables
- `org.maplibre.compose.expressions`: DSL for MapLibre expressions
- `org.maplibre.compose.offline`: Offline map data management
- `org.maplibre.compose.location`: Location engine

### Platform Implementation

The library uses platform-specific implementations:

- **Android/iOS**: MapLibre Native SDKs (MapLibre Android SDK, MapLibre iOS)
- **Web**: MapLibre GL JS via `maplibre-js-bindings`
- **Desktop**: MapLibre Native Core via `maplibre-native-bindings`

## Cursor Cloud specific instructions

This is a **headless Linux VM with no GPU**. Tooling is managed by `mise` (see
`mise.toml`); the startup update script runs `mise install` and inits the
`maplibre-native` git submodules. `mise` is activated in `~/.bashrc`, so login
shells (including new `tmux` shells) have the pinned tools on `PATH`; otherwise
prefix commands with `mise exec --` or run via `mise run <task>`.

Standard commands live in the sections above and in `mise.toml` tasks; the notes
below are the non-obvious, cloud-only caveats.

### What can and cannot run here

- **Runnable target: Desktop/JVM.** It renders a real interactive map via Mesa
  software OpenGL (`llvmpipe`) on the pre-existing VNC display `:1`.
- **Web/JS builds and serves** (`./gradlew :demo-app:jsRun`, port 8080) but the
  map does **not** visually render: the Compose/Skiko canvas needs WebGL, which
  is unavailable without a GPU. The dev server itself works fine.
- **Android and iOS cannot run/build here**: no Android SDK is installed and
  there is no macOS/Xcode. So `./gradlew lint`, `testDebugUnitTest`,
  `packageDebug`, and iOS tasks are out of scope in this VM.

### Running the desktop demo (headless)

Skiko cannot create a hardware GL context here, so force its software renderer.
MapLibre Native's own map GL context works on `llvmpipe`:

```bash
DISPLAY=:1 LIBGL_ALWAYS_SOFTWARE=1 SKIKO_RENDER_API=SOFTWARE \
  ./gradlew :demo-app:run -PdesktopRenderer=opengl
```

Without `SKIKO_RENDER_API=SOFTWARE` the window fails to map (stays 1x1) after a
`RenderException: Cannot create Linux GL context`. The app opens on display `:1`
(view it through the Desktop pane / noVNC).

### Lint / test / build

- Lint: `mise check` (hk → actionlint, pkl, dprint/ktfmt/clang-format). Auto-fix
  with `mise fix`. First `dprint` run compiles its wasm plugins and is slow.
- Headless tests that pass here: `./gradlew jsBrowserTest` (headless Chrome) and
  `./gradlew desktopTest` (builds the native lib, then runs JVM tests).
- The desktop native build compiles MapLibre Native C++ from the vendored
  submodule and takes several minutes on the first run (cached afterward).

### Native build system deps (already baked into the VM image)

Beyond the `libgl1-mesa-dev`/`libx11-dev`/etc. list in
`.github/actions/setup-cmake`, this VM also needs **`libstdc++-14-dev`**:
`clang` selects the GCC-14 toolchain dir, so without it linking fails with
`cannot find -lstdc++`. These are installed once (snapshotted), not by the
update script.
