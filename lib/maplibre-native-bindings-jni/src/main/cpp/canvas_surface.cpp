#include <jawt.h>
#include <jawt_md.h>
#include <jni.h>

#include "canvas_renderer.hpp"

namespace maplibre_jni {

void CanvasSurfaceInfo::lock() { drawingSurface_->Lock(drawingSurface_); }

void CanvasSurfaceInfo::unlock() { drawingSurface_->Unlock(drawingSurface_); }

void* CanvasSurfaceInfo::getPlatformInfo() { return platformInfo_; }

#if defined(_WIN32)

HWND CanvasSurfaceInfo::getNativeWindow() {
  auto win32DrawingSurfaceInfo =
    reinterpret_cast<JAWT_Win32DrawingSurfaceInfo*>(platformInfo_);
  return win32DrawingSurfaceInfo->hwnd;
}

#elif defined(__linux__)

Display* CanvasSurfaceInfo::getNativeDisplay() {
  auto x11DrawingSurfaceInfo =
    reinterpret_cast<JAWT_X11DrawingSurfaceInfo*>(platformInfo_);
  return x11DrawingSurfaceInfo->display;
}

Drawable CanvasSurfaceInfo::getNativeDrawable() {
  auto x11DrawingSurfaceInfo =
    reinterpret_cast<JAWT_X11DrawingSurfaceInfo*>(platformInfo_);
  return x11DrawingSurfaceInfo->drawable;
}

#endif

}  // namespace maplibre_jni
