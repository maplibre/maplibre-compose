<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://maplibre.org/img/maplibre-logos/maplibre-logo-for-dark-bg.svg">
    <source media="(prefers-color-scheme: light)" srcset="https://maplibre.org/img/maplibre-logos/maplibre-logo-for-light-bg.svg">
    <img alt="MapLibre Logo" src="https://maplibre.org/img/maplibre-logos/maplibre-logo-for-light-bg.svg" width="200">
  </picture>
</p>

# MapLibre for Compose Multiplatform

[![Maven Central Version](https://img.shields.io/maven-central/v/org.maplibre.compose/maplibre-compose?label=Maven)](https://central.sonatype.com/namespace/org.maplibre.compose)
[![License](https://img.shields.io/github/license/maplibre/maplibre-compose?label=License)](https://github.com/maplibre/maplibre-compose/blob/main/LICENSE)
[![Kotlin Version](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmaplibre%2Fmaplibre-compose%2Frefs%2Fheads%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.gradle-kotlin&prefix=v&logo=kotlin&label=Kotlin)](./gradle/libs.versions.toml)
[![Compose Version](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmaplibre%2Fmaplibre-compose%2Frefs%2Fheads%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.gradle-compose&prefix=v&logo=jetpackcompose&label=Compose)](./gradle/libs.versions.toml)
[![Documentation](https://img.shields.io/badge/Documentation-blue?logo=MaterialForMkDocs&logoColor=white)](https://maplibre.org/maplibre-compose/)
[![API Reference](https://img.shields.io/badge/API_Reference-blue?logo=Kotlin&logoColor=white)](https://maplibre.org/maplibre-compose/api/)
[![Slack](https://img.shields.io/badge/Slack-4A154B?logo=slack&logoColor=white)](https://osmus.slack.com/archives/maplibre-compose)

## Introduction

MapLibre Compose is a [Compose Multiplatform][compose] wrapper around the
[MapLibre][maplibre] SDKs for rendering interactive maps. You can use it to add
maps to your Compose UIs across Android, iOS, Desktop, and Web.

<p float="left">
  <img src="https://github.com/user-attachments/assets/08233dcb-1237-4a70-93df-ee24d25c4be1" height="450" alt="iOS Screenshot"/>
  <img src="https://github.com/user-attachments/assets/d9fdf1ee-eb78-490d-880d-054106cb29dc" height="450" alt="Android Screenshot"/>
</p>

## Usage

- [Getting Started](https://maplibre.org/maplibre-compose/getting-started/)
- [API Reference](https://maplibre.org/maplibre-compose/api/)
- [Demo App](./demo-app)

## Progress

See [the status table][status] for a breakdown of supported features on each
platform. Android and iOS have the most complete support, while Desktop and Web
are still catching up.

| Target  | Progress                                                             |
| ------- | -------------------------------------------------------------------- |
| Android | ![90%](https://progress-bar.xyz/90/?progress_color=3DDC84&width=200) |
| iOS     | ![90%](https://progress-bar.xyz/90/?progress_color=147efb&width=200) |
| Web     | ![20%](https://progress-bar.xyz/20/?progress_color=FF9500&width=200) |
| Desktop | ![5%](https://progress-bar.xyz/5/?progress_color=CE0000&width=200)   |

[compose]: https://www.jetbrains.com/compose-multiplatform/
[maplibre]: https://maplibre.org/
[status]: https://maplibre.org/maplibre-compose/#status
