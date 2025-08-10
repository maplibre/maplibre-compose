#pragma once

#include <jni.h>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/util/size.hpp>
#include <memory>

#ifdef USE_METAL_BACKEND
#include "metal/awt_metal_backend.hpp"
#elif USE_VULKAN_BACKEND
#include "vulkan/awt_vulkan_backend.hpp"
#elif USE_EGL_BACKEND || USE_WGL_BACKEND || USE_GLX_BACKEND
#include "gl/awt_gl_backend.hpp"
#endif

namespace maplibre_jni {
#ifdef USE_METAL_BACKEND
using PlatformBackend = MetalBackend;
#elif USE_VULKAN_BACKEND
using PlatformBackend = VulkanBackend;
#elif USE_EGL_BACKEND || USE_WGL_BACKEND || USE_GLX_BACKEND
using PlatformBackend = GLBackend;
#endif

std::unique_ptr<PlatformBackend> createPlatformBackend(
  JNIEnv *env, jobject canvas, int width, int height
);

}  // namespace maplibre_jni
