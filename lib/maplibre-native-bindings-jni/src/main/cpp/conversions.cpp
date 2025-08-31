#include <tuple>

#include <mbgl/map/mode.hpp>

#include <smjni/java_ref.h>
#include <smjni/java_string.h>

#include <CameraOptions_class.h>
#include <MapOptions_class.h>

#include "conversions.hpp"

#include "java_classes.hpp"

namespace maplibre_jni {

mbgl::LatLng convertLatLng(JNIEnv* env, jLatLng latLngObj) {
  auto lat = java_classes::get<LatLng_class>().getLatitude(env, latLngObj);
  auto lng = java_classes::get<LatLng_class>().getLongitude(env, latLngObj);
  return mbgl::LatLng(lat, lng);
}

jLatLng convertLatLng(JNIEnv* env, mbgl::LatLng latLng) {
  auto latLngObj = java_classes::get<LatLng_class>().ctor(
    env, latLng.latitude(), latLng.longitude()
  );
  return latLngObj.release();
}

mbgl::ScreenCoordinate convertScreenCoordinate(
  JNIEnv* env, jScreenCoordinate screenCoordinateObj
) {
  auto x =
    java_classes::get<ScreenCoordinate_class>().getX(env, screenCoordinateObj);
  auto y =
    java_classes::get<ScreenCoordinate_class>().getY(env, screenCoordinateObj);
  return mbgl::ScreenCoordinate(x, y);
}

jScreenCoordinate convertScreenCoordinate(
  JNIEnv* env, mbgl::ScreenCoordinate screenCoordinate
) {
  auto screenCoordinateObj = java_classes::get<ScreenCoordinate_class>().ctor(
    env, screenCoordinate.x, screenCoordinate.y
  );
  return screenCoordinateObj.release();
}

mbgl::EdgeInsets convertEdgeInsets(JNIEnv* env, jEdgeInsets edgeInsetsObj) {
  auto top = java_classes::get<EdgeInsets_class>().getTop(env, edgeInsetsObj);
  auto bottom =
    java_classes::get<EdgeInsets_class>().getBottom(env, edgeInsetsObj);
  auto left = java_classes::get<EdgeInsets_class>().getLeft(env, edgeInsetsObj);
  auto right =
    java_classes::get<EdgeInsets_class>().getRight(env, edgeInsetsObj);
  return mbgl::EdgeInsets(top, bottom, left, right);
}

jEdgeInsets convertEdgeInsets(JNIEnv* env, const mbgl::EdgeInsets& edgeInsets) {
  auto obj = java_classes::get<EdgeInsets_class>().ctor(
    env, edgeInsets.top(), edgeInsets.left(), edgeInsets.bottom(),
    edgeInsets.right()
  );
  return obj.release();
}

mbgl::CameraOptions convertCameraOptions(
  JNIEnv* env, jCameraOptions cameraOptionsObj
) {
  auto center =
    java_classes::get<CameraOptions_class>().getCenter(env, cameraOptionsObj);
  auto padding =
    java_classes::get<CameraOptions_class>().getPadding(env, cameraOptionsObj);
  auto anchor =
    java_classes::get<CameraOptions_class>().getAnchor(env, cameraOptionsObj);
  auto zoom =
    java_classes::get<CameraOptions_class>().getZoom(env, cameraOptionsObj);
  auto bearing =
    java_classes::get<CameraOptions_class>().getBearing(env, cameraOptionsObj);
  auto pitch =
    java_classes::get<CameraOptions_class>().getPitch(env, cameraOptionsObj);

  mbgl::CameraOptions options;
  if (center) options.withCenter(convertLatLng(env, center.c_ptr()));
  if (padding) options.withPadding(convertEdgeInsets(env, padding.c_ptr()));
  if (anchor) options.withAnchor(convertScreenCoordinate(env, anchor.c_ptr()));
  if (zoom)
    options.withZoom(java_classes::get<Double_class>().doubleValue(env, zoom));
  if (bearing)
    options.withBearing(
      java_classes::get<Double_class>().doubleValue(env, bearing)
    );
  if (pitch)
    options.withPitch(
      java_classes::get<Double_class>().doubleValue(env, pitch)
    );
  return options;
}

jCameraOptions convertCameraOptions(JNIEnv* env, const mbgl::CameraOptions& c) {
  smjni::local_java_ref<jLatLng> jCenter;
  if (c.center) {
    jCenter = java_classes::get<LatLng_class>().ctor(
      env, c.center->latitude(), c.center->longitude()
    );
  }
  smjni::local_java_ref<jEdgeInsets> jPadding;
  if (c.padding) {
    jPadding = java_classes::get<EdgeInsets_class>().ctor(
      env, c.padding->top(), c.padding->left(), c.padding->bottom(),
      c.padding->right()
    );
  }
  smjni::local_java_ref<jScreenCoordinate> jAnchor;
  if (c.anchor) {
    jAnchor = java_classes::get<ScreenCoordinate_class>().ctor(
      env, c.anchor->x, c.anchor->y
    );
  }
  smjni::local_java_ref<jDouble> jZoom;
  if (c.zoom) {
    jZoom = java_classes::get<Double_class>().valueOf(env, *c.zoom);
  }
  smjni::local_java_ref<jDouble> jBearing;
  if (c.bearing) {
    jBearing = java_classes::get<Double_class>().valueOf(env, *c.bearing);
  }
  smjni::local_java_ref<jDouble> jPitch;
  if (c.pitch) {
    jPitch = java_classes::get<Double_class>().valueOf(env, *c.pitch);
  }
  return java_classes::get<CameraOptions_class>()
    .ctor(env, jCenter, jPadding, jAnchor, jZoom, jBearing, jPitch)
    .release();
}

mbgl::Size convertSize(JNIEnv* env, jSize sizeObj) {
  auto width = java_classes::get<Size_class>().getWidth(env, sizeObj);
  auto height = java_classes::get<Size_class>().getHeight(env, sizeObj);
  return mbgl::Size(width, height);
}

jSize convertSize(JNIEnv* env, const mbgl::Size& size) {
  auto obj = java_classes::get<Size_class>().ctor(
    env, static_cast<jint>(size.width), static_cast<jint>(size.height)
  );
  return obj.release();
}

std::tuple<std::string, std::string, std::optional<std::string>>
convertTileServerTemplate(
  JNIEnv* env, jTileServerTemplate tileServerTemplateObj
) {
  std::optional<std::string> versionPrefix = std::nullopt;
  auto v = java_classes::get<TileServerTemplate_class>().getVersionPrefix(
    env, tileServerTemplateObj
  );
  if (v) {
    versionPrefix = smjni::java_string_to_cpp(env, v);
  }
  return std::make_tuple(
    smjni::java_string_to_cpp(
      env, java_classes::get<TileServerTemplate_class>().getTemplate(
             env, tileServerTemplateObj
           )
    ),
    smjni::java_string_to_cpp(
      env, java_classes::get<TileServerTemplate_class>().getDomainName(
             env, tileServerTemplateObj
           )
    ),
    versionPrefix
  );
}

mbgl::TileServerOptions convertTileServerOptions(
  JNIEnv* env, jTileServerOptions tileServerOptionsObj
) {
  mbgl::TileServerOptions tileServerOptions;

  tileServerOptions.withBaseURL(
    smjni::java_string_to_cpp(
      env, java_classes::get<TileServerOptions_class>().getBaseURL(
             env, tileServerOptionsObj
           )
    )
  );
  tileServerOptions.withUriSchemeAlias(
    smjni::java_string_to_cpp(
      env, java_classes::get<TileServerOptions_class>().getUriSchemeAlias(
             env, tileServerOptionsObj
           )
    )
  );
  tileServerOptions.withApiKeyParameterName(
    smjni::java_string_to_cpp(
      env, java_classes::get<TileServerOptions_class>().getApiKeyParameterName(
             env, tileServerOptionsObj
           )
    )
  );
  tileServerOptions.setRequiresApiKey(
    java_classes::get<TileServerOptions_class>().getRequiresApiKey(
      env, tileServerOptionsObj
    )
  );

  std::apply(
    std::bind_front(
      &mbgl::TileServerOptions::withSourceTemplate, tileServerOptions
    ),
    convertTileServerTemplate(
      env, java_classes::get<TileServerOptions_class>()
             .getSourceTemplate(env, tileServerOptionsObj)
             .c_ptr()
    )
  );
  std::apply(
    std::bind_front(
      &mbgl::TileServerOptions::withStyleTemplate, tileServerOptions
    ),
    convertTileServerTemplate(
      env, java_classes::get<TileServerOptions_class>()
             .getStyleTemplate(env, tileServerOptionsObj)
             .c_ptr()
    )
  );
  std::apply(
    std::bind_front(
      &mbgl::TileServerOptions::withSpritesTemplate, tileServerOptions
    ),
    convertTileServerTemplate(
      env, java_classes::get<TileServerOptions_class>()
             .getSpritesTemplate(env, tileServerOptionsObj)
             .c_ptr()
    )
  );
  std::apply(
    std::bind_front(
      &mbgl::TileServerOptions::withGlyphsTemplate, tileServerOptions
    ),
    convertTileServerTemplate(
      env, java_classes::get<TileServerOptions_class>()
             .getGlyphsTemplate(env, tileServerOptionsObj)
             .c_ptr()
    )
  );
  std::apply(
    std::bind_front(
      &mbgl::TileServerOptions::withTileTemplate, tileServerOptions
    ),
    convertTileServerTemplate(
      env, java_classes::get<TileServerOptions_class>()
             .getTileTemplate(env, tileServerOptionsObj)
             .c_ptr()
    )
  );

  tileServerOptions.withDefaultStyle(
    smjni::java_string_to_cpp(
      env, java_classes::get<TileServerOptions_class>().getDefaultStyle(
             env, tileServerOptionsObj
           )
    )
  );

  return tileServerOptions;
}

mbgl::MapOptions convertMapOptions(JNIEnv* env, jMapOptions optionsObj) {
  auto jSize = java_classes::get<MapOptions_class>().getSize(env, optionsObj);
  auto jMapMode =
    java_classes::get<MapOptions_class>().getMapMode(env, optionsObj);
  auto jConstrainMode =
    java_classes::get<MapOptions_class>().getConstrainMode(env, optionsObj);
  auto jViewportMode =
    java_classes::get<MapOptions_class>().getViewportMode(env, optionsObj);
  auto jNorthOrientation =
    java_classes::get<MapOptions_class>().getNorthOrientation(env, optionsObj);

  mbgl::MapOptions mapOptions;
  mapOptions.withMapMode(
    static_cast<mbgl::MapMode>(
      java_classes::get<MapMode_class>().getNativeValue(env, jMapMode)
    )
  );
  mapOptions.withConstrainMode(
    static_cast<mbgl::ConstrainMode>(java_classes::get<ConstrainMode_class>()
                                       .getNativeValue(env, jConstrainMode))
  );
  mapOptions.withViewportMode(
    static_cast<mbgl::ViewportMode>(
      java_classes::get<ViewportMode_class>().getNativeValue(env, jViewportMode)
    )
  );
  mapOptions.withCrossSourceCollisions(
    java_classes::get<MapOptions_class>().getCrossSourceCollisions(
      env, optionsObj
    )
  );
  mapOptions.withNorthOrientation(
    static_cast<mbgl::NorthOrientation>(
      java_classes::get<NorthOrientation_class>().getNativeValue(
        env, jNorthOrientation
      )
    )
  );
  mapOptions.withSize(
    mbgl::Size(
      java_classes::get<Size_class>().getWidth(env, jSize),
      java_classes::get<Size_class>().getHeight(env, jSize)
    )
  );
  mapOptions.withPixelRatio(
    java_classes::get<MapOptions_class>().getPixelRatio(env, optionsObj)
  );

  return mapOptions;
}

jMapOptions convertMapOptions(JNIEnv* env, const mbgl::MapOptions& opts) {
  auto jMapMode = java_classes::get<MapMode_class>().fromNativeValue(
    env, static_cast<jint>(opts.mapMode())
  );
  auto jConstrainMode =
    java_classes::get<ConstrainMode_class>().fromNativeValue(
      env, static_cast<jint>(opts.constrainMode())
    );
  auto jViewportMode = java_classes::get<ViewportMode_class>().fromNativeValue(
    env, static_cast<jint>(opts.viewportMode())
  );
  auto jNorthOrientation =
    java_classes::get<NorthOrientation_class>().fromNativeValue(
      env, static_cast<jint>(opts.northOrientation())
    );
  auto jSize = java_classes::get<Size_class>().ctor(
    env, static_cast<jint>(opts.size().width),
    static_cast<jint>(opts.size().height)
  );
  jfloat jPixelRatio = opts.pixelRatio();
  return java_classes::get<MapOptions_class>()
    .ctor(
      env, jMapMode, jConstrainMode, jViewportMode,
      static_cast<jboolean>(opts.crossSourceCollisions()), jNorthOrientation,
      jSize, jPixelRatio
    )
    .release();
}

mbgl::ResourceOptions convertResourceOptions(
  JNIEnv* env, jResourceOptions resourceOptionsObj
) {
  auto jTileServerOptions =
    java_classes::get<ResourceOptions_class>().getTileServerOptions(
      env, resourceOptionsObj
    );
  auto jApiKey = java_classes::get<ResourceOptions_class>().getApiKey(
    env, resourceOptionsObj
  );
  auto jCachePath = java_classes::get<ResourceOptions_class>().getCachePath(
    env, resourceOptionsObj
  );
  auto jAssetPath = java_classes::get<ResourceOptions_class>().getAssetPath(
    env, resourceOptionsObj
  );
  auto jMaximumCacheSize =
    java_classes::get<ResourceOptions_class>().getMaximumCacheSize(
      env, resourceOptionsObj
    );

  mbgl::ResourceOptions resourceOptions;
  resourceOptions.withTileServerOptions(
    convertTileServerOptions(env, jTileServerOptions.c_ptr())
  );
  resourceOptions.withApiKey(smjni::java_string_to_cpp(env, jApiKey));
  resourceOptions.withCachePath(smjni::java_string_to_cpp(env, jCachePath));
  resourceOptions.withAssetPath(smjni::java_string_to_cpp(env, jAssetPath));
  resourceOptions.withMaximumCacheSize(jMaximumCacheSize);

  return resourceOptions;
}

mbgl::ClientOptions convertClientOptions(
  JNIEnv* env, jClientOptions clientOptionsObj
) {
  mbgl::ClientOptions clientOptions;
  clientOptions.withName(
    smjni::java_string_to_cpp(
      env,
      java_classes::get<ClientOptions_class>().getName(env, clientOptionsObj)
    )
  );
  clientOptions.withVersion(
    smjni::java_string_to_cpp(
      env,
      java_classes::get<ClientOptions_class>().getVersion(env, clientOptionsObj)
    )
  );
  return clientOptions;
}

}  // namespace maplibre_jni
