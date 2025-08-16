# MapLibre Compose Agent Guide

## Overview

MapLibre Compose is a Kotlin Multiplatform wrapper around MapLibre SDKs for
rendering interactive maps in Compose UIs across Android, iOS, Desktop, and Web.
The codebase is organized as follows:

- **lib/maplibre-compose**: Core library with map composables, camera controls,
  layers, sources, and expressions DSL
- **lib/maplibre-compose-material3**: Material 3 UI components and controls for
  maps
- **lib/maplibre-compose-webview**: WebView-based map implementation
- **lib/kotlin-maplibre-js**: Kotlin/JS bindings for MapLibre GL JS
- **demo-app**: Multiplatform demo application showcasing library features

## Build & Test Commands

```bash
./gradlew build                    # Build all modules
./gradlew check                    # Run all checks (tests, lint, etc.)
just fmt                           # Auto-format code (Kotlin, Swift, Markdown, YAML)
./gradlew test                     # Run all unit tests
./gradlew :lib:maplibre-compose:test  # Run tests for specific module
./gradlew connectedAndroidTest     # Run Android instrumentation tests
./gradlew desktopTest              # Run desktop tests
./gradlew jsTest                   # Run JavaScript tests
./gradlew iosSimulatorArm64Test    # Run iOS tests on simulator
```

## Code Style Guidelines

- **Formatting**: Use ktfmt Google style (run via `just fmt`)
- **Imports**: Organize imports alphabetically, avoid wildcards
- **Naming**: Follow Kotlin conventions (camelCase for functions/variables,
  PascalCase for classes)
- **Package Structure**: `org.maplibre.compose.*` for main library code
- **Multiplatform**: Use `expect`/`actual` for platform-specific implementations
- **Compose**: Follow Compose naming conventions (@Composable functions start
  with uppercase)
- **Error Handling**: Use Result types or sealed classes for errors, avoid
  throwing exceptions
- **Testing**: Place tests in corresponding test source sets (commonTest,
  androidTest, etc.)
