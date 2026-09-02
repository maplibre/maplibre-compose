# Common API gaps

Pending capabilities that MapLibre Native FFI provides but MapLibre Compose has
no cross-platform API for.

The FFI capability set defines the target for native platforms. Web support may
require a separate MapLibre GL JS implementation.

## Style light

Position, color, intensity, and anchor of the style's light source, which fill
extrusions shade against.

- FFI: `setStyleLightJson`, `setStyleLightProperty`, `styleLightProperty`

Naturally a Compose API: a `Light` composable inside `MaplibreMap`'s content,
set the same way layers are.

## Projection mode

Switching between Mercator and globe projections.

- FFI: `projectionMode`

## Style transition options

The style's global transition duration and delay, and whether symbol placement
cross-fades. What every paint property's animation takes its default from, so
this is the one setting that changes how the whole map feels when data updates.

- FFI: `setStyleTransitionOptions`, `styleTransitionOptions`
  ([#465](https://github.com/maplibre/maplibre-native-ffi/pull/465))

Naturally a parameter on `MaplibreMap` or its style content, alongside the other
per-map options.

## Missing style images

The event MapLibre raises when a style references a sprite that is not in the
loaded image set, so an application can supply it on demand instead of shipping
every icon up front. The FFI session logs it today and can do nothing else,
because there is no common callback to route it to.

- FFI: the `MAP_STYLE_IMAGE_MISSING` runtime event, paired with the existing
  `setStyleImage`

See the `MAP_STYLE_IMAGE_MISSING` branch in `MlnFfiMapSession.handleEvent`.
