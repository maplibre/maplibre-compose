#include <cassert>

#include <jawt.h>
#include <jawt_md.h>

class JawtInfo {
 private:
  JAWT awt;
  JAWT_DrawingSurface* drawingSurface;
  JAWT_DrawingSurfaceInfo* drawingSurfaceInfo;
  void* platformInfo;

 public:
  inline JawtInfo(JNIEnv* env, jobject panel) {
    awt.version = JAWT_VERSION_9;
    auto awtResult = JAWT_GetAWT(env, &awt);
    assert(awtResult != JNI_FALSE);

    drawingSurface = awt.GetDrawingSurface(env, panel);
    assert(drawingSurface != NULL);

    auto lockResult = drawingSurface->Lock(drawingSurface);
    assert((lockResult & JAWT_LOCK_ERROR) == 0);

    drawingSurfaceInfo = drawingSurface->GetDrawingSurfaceInfo(drawingSurface);
    platformInfo = drawingSurfaceInfo->platformInfo;
  }

  inline bool lock() {
    auto result = drawingSurface->Lock(drawingSurface);
    assert((result & JAWT_LOCK_ERROR) == 0);
    return result & JAWT_LOCK_SURFACE_CHANGED;
  }

  inline void unlock() { drawingSurface->Unlock(drawingSurface); }

#if defined(__linux__)
  inline JAWT_X11DrawingSurfaceInfo* getPlatformInfo() {
    return reinterpret_cast<JAWT_X11DrawingSurfaceInfo*>(platformInfo);
  }
#elif defined(_WIN32)
  inline JAWT_Win32DrawingSurfaceInfo* getPlatformInfo() {
    return reinterpret_cast<JAWT_Win32DrawingSurfaceInfo*>(platformInfo);
  }
#elif defined(__APPLE__)
  inline id<JAWT_SurfaceLayers>* getPlatformInfo() {
    return reinterpret_cast<id<JAWT_SurfaceLayers>*>(platformInfo);
  }
#endif

  inline ~JawtInfo() {
    if (drawingSurface != NULL) {
      drawingSurface->FreeDrawingSurfaceInfo(drawingSurfaceInfo);
      drawingSurface->Unlock(drawingSurface);
      awt.FreeDrawingSurface(drawingSurface);
    }
  }
};
