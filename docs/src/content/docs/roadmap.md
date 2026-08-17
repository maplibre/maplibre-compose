---
title: Roadmap
description: Projects underway and projects waiting for an interested contributor.
---

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

**Status:** Nearly done 🏁

The goal is to support Compose Desktop platforms (macOS, Windows, and Linux) on
par with our current level of support for Android and iOS.

Desktop is now built on the published
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi) Kotlin
Multiplatform bindings, rather than JNI bindings and a vendored MapLibre Native
checkout of our own. The camera, gestures, styles, sources, layers, expressions,
images, feature queries, Compose resource loading, and the offline manager all
work. The bridge between MapLibre's renderer and Compose's is a replaceable
integration point rather than something wired into Skiko's internals.

Next steps:

- Add support for platform location services on Windows. Linux uses the XDG
  Location portal, and macOS uses Core Location. Desktop orientation providers
  still need platform sensor integrations.

### [Native core integration on Android and iOS](https://github.com/maplibre/maplibre-compose/issues/572)

**Status:** Needs Exploration 🔍

The goal is to wrap just the MapLibre Native C++ core on Android, iOS, and
desktop with one common Kotlin JVM+Native wrapper. Desktop consumes
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi), but
Android uses MapLibre Native's Java/Kotlin bindings and iOS its Obj-C ones. Each
has a different API, so our multiplatform API tends toward the
lowest-common-denominator of all three.

The desktop work above is the evidence this is worth doing: it is a full map
implementation on the FFI, and several capabilities MapLibre Native offers are
sitting unused behind it simply because there is no cross-platform API to reach
them.

Research Areas:

- Explore using `maplibre-native-ffi` on Android, with code to integrate with an
  Android Surface instead of an AWT Canvas.
- Explore using its Kotlin/Native targets on iOS, with code to integrate with a
  Metal layer.
- Explore unifying those platforms behind a single, thin, `expect`/`actual`
  interface on top of MapLibre Native.

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
- Add inline examples to the documentation site to go with code snippets.

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

- Regressions due to limited automatic tests on all platforms.
- Brittle local development setup.

Next steps:

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

The goal is to support Compose apps in the browser using Kotlin WASM. Which map
draws them is open: the current Kotlin/JS platform uses MapLibre GL JS, and
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi) has
gained WebGPU and WebGL support on wasm alongside a Kotlin/Wasm binding.

Next steps:

- If MapLibre GL JS: explore how much of the Kotlin JS platform can be shared
  with Kotlin WASM, and build a proof of concept.
- If MapLibre Native: build minimal support on the Kotlin/Wasm bindings — map
  loading and style switching — and find out what a browser does to a renderer
  that never expected one.

### [Improve controls on desktop and web](https://github.com/maplibre/maplibre-compose/issues/230)

**Status:** Needs Exploration 🔍

The goal is to build an intuitive experience controlling the map on all
platforms. MapLibre Native for iOS and Android already provide a rich set of
gestures for those mobile platforms, so the focus here is on desktop and web.

Desktop now has a working set, tuned to match MapLibre GL JS: drag to pan,
scroll and double-click to zoom, right-drag or ctrl-drag to rotate and tilt, and
keyboard control throughout. Touchscreens on the Desktop FFI host use
Android-style pan, pinch, rotate, shove, quick-zoom, and velocity gestures. What
is left is covering input devices such as multi-touch trackpads and the
accessibility needs the current controls do not yet reach.

Research Areas:

- Explore map controls conventions on desktop and web for zooming, panning,
  tilting, and rotating using mouse, keyboard, and multi-touch trackpads.
- Explore conventions for maps popular in different regions: Google Maps, Apple
  Maps, Amap, Baidu Maps, Naver Map, Kakao Maps, Yandex Maps, Mappls, Maps.me
- Explore the available input APIs on macOS, Linux (X11 and Wayland), Windows,
  and web browsers.
- Design a set of controls that work well on all platforms, considering
  platform-specific input devices and accessibility features.

### [Imperative escape hatches](https://github.com/maplibre/maplibre-compose/issues/18)

**Status:** Needs Exploration 🔍

Styling is declarative: you compose sources and layers into the map and MapLibre
Compose applies the difference. That works well for content you own and not at
all for content you did not write. Changing the visibility, filter, or zoom
range of a layer that came from the base style is a recurring request, and today
the answers are to replace the layer with `Anchor.Replace` and reproduce its
properties, or to fetch the style JSON and edit it before handing it to the map.
Both are workarounds for the same missing thing.

Once every platform is on `maplibre-native-ffi`, the escape hatch may already
exist. Its handles are an imperative map API, so exposing them — opt-in, and
marked as delicate — would let an application blocked on something we have not
wrapped reach past us rather than wait for us, with the same API everywhere.
Doing that today would mean exposing a different one per platform, which is what
makes [#538](https://github.com/maplibre/maplibre-compose/issues/538) hard to
answer well.

### Fill in the missing map capabilities

**Status:** Blocked 🚧

MapLibre Native can do a number of things MapLibre Compose has no cross-platform
API for, among them style light, custom geometry sources, the location indicator
layer, alternative projections, style transition options, HTTP header
transforms, supplying missing style images on demand, resource transforms,
merging offline databases, and
[static map snapshots](https://github.com/maplibre/maplibre-compose/issues/28) —
which `maplibre-native-ffi` can now produce by reading a rendered map back to
the CPU.

These are deliberately not being built yet. Doing any of them today means
writing the same feature four times — against the Android SDK, the iOS SDK,
`maplibre-native-ffi`, and MapLibre GL JS — and throwing three of those away
once the native core integration above lands. They become one implementation
each afterwards, which is why that work comes first.

Snapshots carry one extra requirement, since we would like to style them the
same way interactive maps are styled: the style API has to be usable without a
`MaplibreMap` composable to hang it on.

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
