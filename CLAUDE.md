# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## Project Overview

MapLibre Compose is a Kotlin Multiplatform wrapper around MapLibre SDKs that
provides a declarative Compose-based API for rendering interactive maps across
Android, iOS, Desktop, and Web platforms.

## Essential Commands

### Development

```bash
# Format code before committing
./gradlew spotlessApply

# Install git hooks for auto-formatting
./gradlew installGitHooks

# Run Android lint
./gradlew lint

# Generate documentation
./gradlew generateDocs
```

### Testing

```bash
# Android unit tests
./gradlew testDebugUnitTest

# Android instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# iOS tests (requires macOS with Xcode)
./gradlew iosSimulatorArm64Test

# Web tests
./gradlew jsBrowserTest

# Desktop tests
./gradlew desktopTest

# Run specific test
./gradlew :lib:maplibre-compose:testDebugUnitTest --tests "*.SpecificTestClass"
```

### Running Demo App

```bash
# Desktop
./gradlew :demo-app:run

# Web (opens in browser)
./gradlew :demo-app:jsRun

# Android/iOS: Use IDE (Android Studio/Xcode)
```

### Building

```bash
# Build Android APK
./gradlew packageDebug

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## Architecture

### Module Structure

- `lib/maplibre-compose` - Core library with map composables and common API
- `lib/maplibre-compose-material3` - Material 3 themed map components
- `lib/maplibre-compose-expressions` - Type-safe DSL for MapLibre style
  expressions
- `lib/kotlin-maplibre-js` - Kotlin/JS bindings for MapLibre GL JS
- `lib/maplibre-compose-webview` - WebView-based implementation for desktop
- `demo-app` - Multiplatform demo application

### Platform Implementation Strategy

- **Android**: Direct integration with MapLibre Native Android SDK
- **iOS**: Direct integration with MapLibre Native iOS SDK via SPM
- **Web**: Uses MapLibre GL JS through Kotlin/JS bindings
- **Desktop**: Currently uses KCEF WebView to embed MapLibre GL JS

### Core Components

- `MaplibreMap` - Main composable for displaying maps
- `CameraState` - Manages camera position, zoom, bearing, and animations
- `StyleState` - Handles map styling and style transitions
- Layer composables (`LineLayer`, `FillLayer`, `SymbolLayer`, etc.)
- Source composables (`GeoJsonSource`, `VectorSource`, `RasterSource`)
- Gesture handlers and map interaction controls

### Code Conventions

- Kotlin code uses Google style (enforced by spotless with ktfmt)
- Swift code for iOS uses swift-format
- All public APIs require KDoc documentation
- Platform-specific code in `src/{platform}Main` source sets
- Common code shared across platforms in `src/commonMain`
- Use `@Composable` functions for all UI components
- State management follows Compose patterns (remember, mutableStateOf)

### Testing Approach

- Unit tests for business logic in `commonTest`
- Platform-specific tests for native integrations
- UI tests using Compose Testing framework
- Test data fixtures in `src/commonTest/resources`

## Key Development Notes

1. **Gradle Configuration**: Uses version catalogs (`gradle/libs.versions.toml`)
   and convention plugins in `buildSrc`

2. **Required Setup**:

   - JDK 21 for building (targets JVM 11)
   - Android SDK with compileSdk 35
   - For iOS: Xcode with iOS 12.0+ deployment target
   - Create `local.properties` with `sdk.dir=/path/to/Android/sdk`

3. **Platform Considerations**:

   - Android and iOS are stable platforms with full feature support
   - Web and Desktop are experimental with limited features
   - Desktop requires KCEF setup with specific JVM flags

4. **State Management**:

   - Use `rememberCameraState()` for camera control
   - Use `rememberStyleState()` for style management
   - States are designed to be hoisted for external control

5. **Expression DSL**:
   - Type-safe DSL for creating MapLibre style expressions
   - Located in `maplibre-compose-expressions` module
   - Provides compile-time validation of expression syntax
