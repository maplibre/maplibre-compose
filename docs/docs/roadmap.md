# Roadmap

MapLibre Compose is still primarily developed by
[one person](https://github.com/sargunv) in his spare time. Therefore, there are
no target dates for the completion of projects listed on this page.

We'd love for the community to help move the project forward. Community
contributions don't just take the form of code changes in pull requests; a
number of the projects below require an interested party to do research,
investigation, or experiment with some proof of concept.

If you'd like to get involved, please join us in
[our Slack channel](https://osmus.slack.com/archives/maplibre-compose).

## On deck

These projects are in progress or ready to start writing code. Community
contributions are highly welcome.

### [Desktop Parity](https://github.com/maplibre/maplibre-compose/issues/570)

**Status:** Shovel ready 🪏

The goal is to support Compose Desktop platforms (macOS, Windows, and Linux) on
par with our current level of support for Android and iOS. These are complex
platforms to support, because we have to integrate with the MapLibre Native C++
core on macOS, Windows, and Linux, and bridge that integration to the JVM.

Approach:

- Write Kotlin JVM bindings to
  [MapLibre Native](https://maplibre.org/maplibre-native/cpp/api/) in C++ using
  [SimpleJNI](https://github.com/gershnik/SimpleJNI).
- Use those bindings to fill in missing functionality in the `desktopMain`
  target of MapLibre Compose.

Next steps:

- Add support for loading Compose resource URIs.
- Add support for querying visible map features.
- Add support for programmatic layer styling (sources, layers, expressions,
  images, etc).
- Add support for the offline manager.
- Add support for platform location services on macOS, Windows, and Linux.
- Fix or investigate map loading on Linux (currently segfaults for unknown
  reason).
- Validate that display density is handled correctly on all three desktop
  platforms. On Linux, validate both Wayland and X11.
- Fix keeping CAMetalLayer with the AWT Canvas on macOS.

### [JS Parity](https://github.com/maplibre/maplibre-compose/issues/222)

**Status:** Shovel ready 🪏

The goal is to support Compose apps in the browser using Kotlin JS, backed by
MapLibre GL JS instead of MapLibre Native.

Approach:

- Write Kotlin JS bindings for
  [MapLibre GL JS](https://maplibre.org/maplibre-gl-js/docs/).
- Use those bindings to implement browser support in the `jsMain` module of
  MapLibre Compose.

Next steps:

- Add support for programmatic layer styling (sources, layers, expressions,
  images, etc).
- Add support for browser location services.
- Update to MapLibre GL JS v5 for 3d globe support.

### [Documentation](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20documentation%20label%3Adocumentation)

**Status:** Needs Exploration 🔍 but some parts are shovel ready 🪏

The goal is to overhaul the documentation to make it easier for newcomers to use
the library, and to make LLMs more reliable at writing correct code using
MapLibre Compose.

Next steps:

- Improve
  [the demo app](https://github.com/maplibre/maplibre-compose/issues/486),
  fixing known bugs and adding demos showing the capabilities of MapLibre
  Compose.
- Add inline examples to the documentation site (requires JS parity above) to go
  with code snippets.

Investigation needed:

- Explore
  [the Q&A section](https://github.com/maplibre/maplibre-compose/discussions/categories/q-a)
  to understand points of common confusion.
- Build a tutorial-style exploration of the library, covering basic concepts and
  real-world use cases.
- Explain the style composition and expressions DSL from the Kotlin perspective
  for an audience who may not be familiar with the
  [MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/).
- Explore generating a useful [`llms.txt`](https://llmstxt.org) file for the
  documentation site.
- Explore improving the usefulness of context about MapLibre Compose
  [on Context7](https://context7.com/maplibre/maplibre-compose).

### Devex improvements

**Status:** Needs Exploration 🔍 but some parts are shovel ready 🪏

The project would benefit from work to improve the experience of developing
MapLibre Compose for desktop. The biggest pain points right now are:

- Building MapLibre Native core from source in local development and every CI
  run.
- Regressions due to limited automatic tests on all platforms.
- Brittle local development setup.

Next steps:

- [Build against the prebuilt MapLibre Native distribution.](https://github.com/maplibre/maplibre-compose/issues/568)
- [Configure a reproducible build environment.](https://github.com/maplibre/maplibre-compose/issues/684)

Investigation needed:

- [Explore testing strategies for testing map behavior on all platforms.](https://github.com/maplibre/maplibre-compose/issues/29)
- Explore benchmarking strategies for map rendering and other logic on all
  platforms.

## Road to v1.0

These projects should be completed before a v1.0 release of MapLibre Compose,
but are not currently being worked on. If you're interested and would like to
take them on, community contributions are of course still welcome!

### [WASM Parity](https://github.com/maplibre/maplibre-compose/issues/209)

**Status:** Needs Exploration 🔍

The goal is to support Compose apps in the browser using Kotlin WASM, backed by
MapLibre GL JS instead of MapLibre Native.

Next steps:

- Explore to what extent JS bindings to Kotlin can be shared between Kotlin JS
  and Kotlin WASM.
- Build a proof of concept of MapLibre GL JS bindings to Kotlin WASM.
- Use the proof of concept to build minimal MapLibre Compose support on WASM
  (e.g. map loading, style switching).

### [Improve controls on desktop and web](https://github.com/maplibre/maplibre-compose/issues/230)

**Status:** Needs Exploration 🔍

The goal is to build an intuitive experience controlling the map on all
platforms. MapLibre Native for iOS and Android already provide a rich set of
gestures for those mobile platforms, so the focus here is on providing an
intuitive experience for desktop and web users.

Research Areas:

- Explore map controls conventions on desktop and web for zooming, panning,
  tilting, and rotating using mouse, keyboard, and multi-touch trackpads.
- Explore conventions for maps popular in different regions: Google Maps, Apple
  Maps, Amap, Baidu Maps, Naver Map, Kakao Maps, Yandex Maps, Mappls, Maps.me
- Explore the available input APIs on macOS, Linux (X11 and Wayland), Windows,
  and web browsers.
- Design a set of controls that work well on all platforms, considering
  platform-specific input devices and accessibility features.

### [Support map snapshotter](https://github.com/maplibre/maplibre-compose/issues/28)

**Status:** Blocked 🚧

The goal is to provide a multiplatform interface for generating static snapshots
of the map. We'd like to do this with the same kind of programmatic styling we
support for interactive maps, so first we need to decouple the map style API
from the `MaplibreMap` UI `@Composable`.

### [Native core integration on Android and iOS](https://github.com/maplibre/maplibre-compose/issues/572)

**Status:** Needs Exploration 🔍

The goal is to streamline our MapLibre Native bindings by wrapping just the
MapLibre Native C++ core on Android, iOS, and Desktop with a common Kotlin
JVM+Native wrapper. Today, we wrap the MapLibre Native core for desktop, but use
the MapLibre Native Android Java/Kotlin bindings on Android and MapLibre Native
iOS Obj-C bindings on iOS. Each of these have different APIs, and so our
Multiplatform APIs tend to be the lowest-common-denominator of them all.

Research Areas:

- Explore using our existing Desktop JNI bindings on Android, with code to
  integrate with an Android Surface instead of an AWT Canvas.
- Explore writing Kotlin Native cinterop bindings to the MapLibre Native core,
  with code to integrate with a Metal layer on iOS.
- Explore unifying those two sets of bindings with a single, thin,
  `expect`/`actual` interface on top of MapLibre Native.

## Long term

These projects are unlikely to be worked on until after a v1.0 release of
MapLibre Compose. But if you're interested and would like to take them on,
community contributions are of course still welcome!

### [Support secondary platforms (car, watch, tv, etc)](https://github.com/maplibre/maplibre-compose/issues/26)

**Status:** Needs Exploration 🔍

The goal is to provide some support for building maps that are used on secondary
platforms, such as cars, watches, and TVs. Not all these platforms support
Compose UI, so this may involve writing bare KMP wrappers for MapLibre Native on
some platforms, or rendering map snapshots, or integrating with some alternative
UI toolkits.

### Pure Compose map rendering

**Status:** Needs Exploration 🔍

The goal is to perform all map rendering in Compose, instead of including
interop UI components for each platform. This will allow us to eliminate
compositing limitations, especially on desktop and web.

I'm not sure if this is possible, but if it is, it would likely be accomplished
by performing map rendering to a texture, and using a Compose Multiplatform
runtime shader to render that texture.

Research Areas:

- Explore the capabilities of runtime shaders across all Compose platforms
- Build a proof of concept that performs hardware accelerated graphics rendering
  to a texture using OpenGL, Vulkan, or Metal, and renders that texture to
  Compose UI without interop views.

Alternatively, we could explore a pure Skia implementation of MapLibre map
rendering.
