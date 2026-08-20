---
title: Roadmap
description: Projects on the path to 1.0, and work that waits until after.
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

## Road to 1.0

These projects should be completed before a v1.0 release of MapLibre Compose.
Community contributions are highly welcome.

### [Documentation](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20documentation%20label%3Adocumentation)

**Status:** Needs Exploration 🔍 but some parts are shovel ready 🪏

The goal is to overhaul the documentation to make it easier for newcomers to use
the library.

Next steps:

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

Android, iOS, and Desktop now share `maplibre-native-ffi` handles, which are an
imperative map API. Exposing them — opt-in, and marked as delicate — would let
an application reach past us for something we have not wrapped. A common hatch
is one object per backend: the browser still uses MapLibre GL JS. The shape of
that API is sketched in
[`API_REDESIGN.md`](https://github.com/maplibre/maplibre-compose/blob/main/.agents/docs/API_REDESIGN.md).
Publishing those handles is also the answer to
[#538](https://github.com/maplibre/maplibre-compose/issues/538).

### Fill in the missing map capabilities

**Status:** Needs Exploration 🔍

MapLibre Native can do a number of things MapLibre Compose has no cross-platform
API for, among them style light, the location indicator layer, alternative
projections, style transition options, HTTP header transforms, supplying missing
style images on demand, resource transforms, merging offline databases, and
[static map snapshots](https://github.com/maplibre/maplibre-compose/issues/28).
The inventory is in
[`COMMON_API_GAPS.md`](https://github.com/maplibre/maplibre-compose/blob/main/.agents/docs/COMMON_API_GAPS.md).

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
