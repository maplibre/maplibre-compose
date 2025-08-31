#pragma once

#include <mbgl/map/camera.hpp>
#include <mbgl/map/map_options.hpp>
#include <mbgl/storage/resource_options.hpp>
#include <mbgl/util/client_options.hpp>
#include <mbgl/util/tile_server_options.hpp>

#include <jni.h>

#include "type_mapping.h"

namespace maplibre_jni {

mbgl::LatLng convertLatLng(JNIEnv* env, jLatLng latLngObj);

jLatLng convertLatLng(JNIEnv* env, mbgl::LatLng latLng);

mbgl::EdgeInsets convertEdgeInsets(JNIEnv* env, jEdgeInsets edgeInsetsObj);
jEdgeInsets convertEdgeInsets(JNIEnv* env, const mbgl::EdgeInsets& edgeInsets);

mbgl::ScreenCoordinate convertScreenCoordinate(
  JNIEnv* env, jScreenCoordinate screenCoordinateObj
);

jScreenCoordinate convertScreenCoordinate(
  JNIEnv* env, mbgl::ScreenCoordinate screenCoordinate
);

mbgl::CameraOptions convertCameraOptions(
  JNIEnv* env, jCameraOptions cameraOptionsObj
);
jCameraOptions convertCameraOptions(
  JNIEnv* env, const mbgl::CameraOptions& cameraOptions
);

mbgl::Size convertSize(JNIEnv* env, jSize sizeObj);
jSize convertSize(JNIEnv* env, const mbgl::Size& size);

mbgl::MapOptions convertMapOptions(JNIEnv* env, jMapOptions optionsObj);
jMapOptions convertMapOptions(JNIEnv* env, const mbgl::MapOptions& mapOptions);

mbgl::ResourceOptions convertResourceOptions(
  JNIEnv* env, jResourceOptions resourceOptionsObj
);

mbgl::TileServerOptions convertTileServerOptions(
  JNIEnv* env, jTileServerOptions tileServerOptionsObj
);

mbgl::ClientOptions convertClientOptions(
  JNIEnv* env, jClientOptions clientOptionsObj
);

}  // namespace maplibre_jni
