# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

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
  - `maplibre-compose-webview`: WebView-based implementation
  - `kotlin-maplibre-js`: JavaScript bindings for MapLibre GL JS
- **`demo-app/`**: Multiplatform demo application
- **`iosApp/`**: iOS-specific application wrapper
- **`buildSrc/`**: Custom Gradle build conventions

### Key Packages

- `org.maplibre.compose.map`: Core map composable and components
- `org.maplibre.compose.camera`: Camera controls and positioning
- `org.maplibre.compose.layers`: Layer composables for map visualization
- `org.maplibre.compose.sources`: Data source composables
- `org.maplibre.compose.expressions`: DSL for MapLibre expressions
- `org.maplibre.compose.offline`: Offline map data management

### Platform Implementation

The library uses platform-specific implementations:

- **Android/iOS**: Native MapLibre SDKs (MapLibre Android SDK, MapLibre iOS)
- **Web**: MapLibre GL JS via Kotlin/JS bindings
- **Desktop**: WebView-based implementation using KCEF

### Build Configuration

- Kotlin version: Check `gradle/libs.versions.toml`
- Android SDK: min 23, compile/target 35
- iOS deployment target: 12.0
- JVM toolchain: 21, target: 11
