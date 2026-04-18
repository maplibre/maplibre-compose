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

// clang-format off
#include "fix_x11_pollution.h"
// clang-format on

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

    // Do one lock/unlock to capture the X11 Display* (which is the JVM's
    // persistent X11 connection and never changes). The Drawable is NOT cached
    // here — it is refreshed on every subsequent lock() call.
    lock();
    unlock();
  }

  ~JawtContext() {
    if (drawingSurface != nullptr) awt.FreeDrawingSurface(drawingSurface);
  }

  // Locks the JAWT drawing surface and refreshes platform-specific surface
  // info (display, drawable, etc.). The returned flags indicate surface changes
  // (e.g. JAWT_LOCK_SURFACE_CHANGED). Must be paired with unlock().
  jint lock() {
    check(currentDsi_ == nullptr, "JawtContext::lock() called while already locked");
    jint lockResult = drawingSurface->Lock(drawingSurface);
    check(lockResult != JAWT_LOCK_ERROR, "drawingSurface->Lock failed");

    // Re-fetch surface info on every lock — the Drawable (X11 window ID)
    // can change across lock cycles when the AWT peer is recreated (e.g.
    // on first show, on resize with JAWT_LOCK_SURFACE_CHANGED). Using a
    // stale Drawable in glXMakeCurrent/glXSwapBuffers causes a segfault.
    currentDsi_ = drawingSurface->GetDrawingSurfaceInfo(drawingSurface);
    check(currentDsi_ != nullptr, "drawingSurface->GetDrawingSurfaceInfo failed");

#if defined(_WIN32)
    auto* dsiWin =
      static_cast<JAWT_Win32DrawingSurfaceInfo*>(currentDsi_->platformInfo);
    hwnd = dsiWin->hwnd;
    hdc = dsiWin->hdc;
#elif defined(__APPLE__)
    surfaceLayers = static_cast<SurfaceLayersRef>(currentDsi_->platformInfo);
#elif defined(__linux__)
    auto* dsiX11 =
      static_cast<JAWT_X11DrawingSurfaceInfo*>(currentDsi_->platformInfo);
    display = dsiX11->display;
    drawable = dsiX11->drawable;
#endif

    return lockResult;
  }

  // Releases JAWT surface info and unlocks the drawing surface.
  void unlock() {
    if (currentDsi_ != nullptr) {
      drawingSurface->FreeDrawingSurfaceInfo(currentDsi_);
      currentDsi_ = nullptr;
    }
    drawingSurface->Unlock(drawingSurface);
  }

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
  JAWT_DrawingSurface* drawingSurface = nullptr;
  JAWT awt{};
  JAWT_DrawingSurfaceInfo* currentDsi_ = nullptr;

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
