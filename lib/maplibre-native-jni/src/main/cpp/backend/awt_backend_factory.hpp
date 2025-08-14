#pragma once

#include <jni.h>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/util/size.hpp>
#include <memory>

#ifdef USE_METAL_BACKEND
#include "metal/awt_metal_backend.hpp"
#endif

namespace maplibre_jni {
#ifdef USE_METAL_BACKEND
using PlatformBackend = MetalBackend;
#endif

std::unique_ptr<PlatformBackend> createPlatformBackend(
  JNIEnv *env, jCanvas canvas
);

}  // namespace maplibre_jni
