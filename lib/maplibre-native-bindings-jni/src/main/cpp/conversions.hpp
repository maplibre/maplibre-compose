#pragma once

#include <mbgl/map/camera.hpp>
#include <mbgl/map/map_options.hpp>
#include <mbgl/storage/resource_options.hpp>
#include <mbgl/util/client_options.hpp>
#include <mbgl/util/tile_server_options.hpp>

#include "type_mapping.h"

namespace maplibre_jni {

auto convertLatLng(JNIEnv* env, jLatLng latLngObj) -> mbgl::LatLng;

auto convertLatLng(JNIEnv* env, mbgl::LatLng latLng) -> jLatLng;

auto convertEdgeInsets(JNIEnv* env, jEdgeInsets edgeInsetsObj)
  -> mbgl::EdgeInsets;

auto convertEdgeInsets(JNIEnv* env, const mbgl::EdgeInsets& edgeInsets)
  -> jEdgeInsets;

auto convertScreenCoordinate(JNIEnv* env, jScreenCoordinate screenCoordinateObj)
  -> mbgl::ScreenCoordinate;

auto convertScreenCoordinate(
  JNIEnv* env, mbgl::ScreenCoordinate screenCoordinate
) -> jScreenCoordinate;

auto convertCameraOptions(JNIEnv* env, jCameraOptions cameraOptionsObj)
  -> mbgl::CameraOptions;

auto convertCameraOptions(JNIEnv* env, const mbgl::CameraOptions& cameraOptions)
  -> jCameraOptions;

auto convertSize(JNIEnv* env, jSize sizeObj) -> mbgl::Size;

auto convertSize(JNIEnv* env, const mbgl::Size& size) -> jSize;

auto convertMapOptions(JNIEnv* env, jMapOptions mapOptionsObj)
  -> mbgl::MapOptions;

auto convertMapOptions(JNIEnv* env, const mbgl::MapOptions& mapOptions)
  -> jMapOptions;

auto convertResourceOptions(JNIEnv* env, jResourceOptions resourceOptionsObj)
  -> mbgl::ResourceOptions;

auto convertTileServerOptions(
  JNIEnv* env, jTileServerOptions tileServerOptionsObj
) -> mbgl::TileServerOptions;

auto convertClientOptions(JNIEnv* env, jClientOptions clientOptionsObj)
  -> mbgl::ClientOptions;

}  // namespace maplibre_jni
