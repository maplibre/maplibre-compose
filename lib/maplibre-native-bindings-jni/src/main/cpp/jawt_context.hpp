#pragma once

#include <jawt.h>
#include <jawt_md.h>
#include <type_mapping.h>

#include "utils.hpp"

#if defined(_WIN32)
#include <windows.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
#endif

namespace maplibre_jni {

#ifdef __APPLE__
#ifdef __OBJC__
using SurfaceLayersRef = id<JAWT_SurfaceLayers>;
#else
using SurfaceLayersRef = void*;
#endif
#endif

class JawtContext {
 public:
  JawtContext(const JawtContext&) = delete;
  JawtContext(JawtContext&&) = delete;
  auto operator=(const JawtContext&) -> JawtContext& = delete;
  auto operator=(JawtContext&&) -> JawtContext& = delete;

  JawtContext(JNIEnv* env, jCanvas canvas) {
    awt.version = JAWT_VERSION_9;
    check(JAWT_GetAWT(env, &awt) != JNI_FALSE, "JAWT_GetAWT failed");

    drawingSurface = awt.GetDrawingSurface(env, canvas);
    check(drawingSurface != nullptr, "awt.GetDrawingSurface failed");

    lock();

    JAWT_DrawingSurfaceInfo* dsi =
      drawingSurface->GetDrawingSurfaceInfo(drawingSurface);
    check(dsi != nullptr, "drawingSurface->GetDrawingSurfaceInfo failed");

#if defined(_WIN32)
    JAWT_Win32DrawingSurfaceInfo* dsiWin =
      (JAWT_Win32DrawingSurfaceInfo*)dsi->platformInfo;
    hwnd = dsiWin->hwnd;
    hdc = dsiWin->hdc;
#elif defined(__APPLE__)
    surfaceLayers = (SurfaceLayersRef)dsi->platformInfo;
#elif defined(__linux__)
    JAWT_X11DrawingSurfaceInfo* dsiX11 =
      (JAWT_X11DrawingSurfaceInfo*)dsi->platformInfo;
    display = dsiX11->display;
    drawable = dsiX11->drawable;
#endif

    drawingSurface->FreeDrawingSurfaceInfo(dsi);

    unlock();
  }

  ~JawtContext() {
    check(drawingSurface != nullptr, "drawingSurface is nullptr");
    awt.FreeDrawingSurface(drawingSurface);
  }

  jint lock() {
    jint lockResult = drawingSurface->Lock(drawingSurface);
    check(lockResult != JAWT_LOCK_ERROR, "drawingSurface->Lock failed");
    return lockResult;
  }

  void unlock() { drawingSurface->Unlock(drawingSurface); }

#if defined(_WIN32)
  HWND getHwnd() const { return hwnd; }
  HDC getHdc() const { return hdc; }
#elif defined(__APPLE__)
  SurfaceLayersRef getSurfaceLayers() const { return surfaceLayers; }
#elif defined(__linux__)
  Display* getDisplay() const { return display; }
  Drawable getDrawable() const { return drawable; }
#endif

 private:
  JAWT_DrawingSurface* drawingSurface;
  JAWT awt{};

#if defined(_WIN32)
  HWND hwnd = nullptr;
  HDC hdc = nullptr;
#elif defined(__APPLE__)
  SurfaceLayersRef surfaceLayers = nullptr;
#elif defined(__linux__)
  Display* display = nullptr;
  Drawable drawable = 0;
#endif
};

}  // namespace maplibre_jni
