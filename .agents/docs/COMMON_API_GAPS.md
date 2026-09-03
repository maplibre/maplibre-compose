# Common API gaps

Pending capabilities that MapLibre Native FFI provides but MapLibre Compose has
no cross-platform API for.

The FFI capability set defines the target for native platforms. Web support may
require a separate MapLibre GL JS implementation.

## Projection mode

Switching between Mercator and globe projections.

- FFI: `projectionMode`

## Missing style images

The event MapLibre raises when a style references a sprite that is not in the
loaded image set, so an application can supply it on demand instead of shipping
every icon up front. The FFI session logs it today and can do nothing else,
because there is no common callback to route it to.

- FFI: the `MAP_STYLE_IMAGE_MISSING` runtime event, paired with the existing
  `setStyleImage`

See the `MAP_STYLE_IMAGE_MISSING` branch in `MlnFfiMapSession.handleEvent`.
