package org.maplibre.kmp.native.canvas

import java.awt.Canvas
import org.lwjgl.system.jawt.JAWT
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo
import org.lwjgl.system.jawt.JAWTFunctions.*
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo

internal sealed class JawtContext(canvas: Canvas) {
  var drawingSurface =
    JAWT_GetDrawingSurface(canvas, awt.GetDrawingSurface()) ?: error("GetDrawingSurface failed")

  init {
    try {
      lock()
      try {
        val dsi: JAWTDrawingSurfaceInfo =
          JAWT_DrawingSurface_GetDrawingSurfaceInfo(
            drawingSurface,
            drawingSurface.GetDrawingSurfaceInfo(),
          )!!
        try {
          onDrawingSurfaceInfo(dsi)
        } finally {
          JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, drawingSurface.FreeDrawingSurfaceInfo())
        }
      } finally {
        unlock()
      }
    } catch (e: Throwable) {
      dispose()
      throw e
    }
  }

  protected abstract fun onDrawingSurfaceInfo(dsi: JAWTDrawingSurfaceInfo)

  fun dispose() {
    JAWT_FreeDrawingSurface(drawingSurface, awt.FreeDrawingSurface())
  }

  fun lock(): LockResult {
    return LockResult(JAWT_DrawingSurface_Lock(drawingSurface, drawingSurface.Lock())).also {
      check(it.isSuccess)
    }
  }

  fun unlock() {
    JAWT_DrawingSurface_Unlock(drawingSurface, drawingSurface.Unlock())
  }

  companion object {
    private val awt: JAWT = JAWT.create()

    init {
      awt.version(JAWT_VERSION_9)
      check(JAWT_GetAWT(awt))
    }

    fun create(canvas: Canvas): JawtContext {
      val os = System.getProperty("os.name").lowercase()
      return when {
        "mac" in os -> MacOS(canvas)
        "win" in os -> Win32(canvas)
        else -> X11(canvas)
      }
    }
  }

  class MacOS(canvas: Canvas) : JawtContext(canvas) {
    var platformInfo: Long = 0
      private set

    override fun onDrawingSurfaceInfo(dsi: JAWTDrawingSurfaceInfo) {
      platformInfo = dsi.platformInfo()
    }

    fun initLayer() {
      TODO()
      // need to make objc calls somehow
      // create a metal layer and attach it to the platformInfo
    }

    val layer: Long
      get() = TODO()
  }

  class Win32(canvas: Canvas) : JawtContext(canvas) {
    var hwnd: Long = 0
      private set

    var hdc: Long = 0
      private set

    override fun onDrawingSurfaceInfo(dsi: JAWTDrawingSurfaceInfo) {
      val dsiWin = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo())
      hwnd = dsiWin.hwnd()
      hdc = dsiWin.hdc()
    }
  }

  class X11(canvas: Canvas) : JawtContext(canvas) {
    var display: Long = 0
      private set

    var drawable: Long = 0
      private set

    override fun onDrawingSurfaceInfo(dsi: JAWTDrawingSurfaceInfo) {
      val dsiWin = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo())
      display = dsiWin.display()
      drawable = dsiWin.drawable()
    }
  }

  @JvmInline
  internal value class LockResult(val value: Int) {
    inline val isError
      get() = value and JAWT_LOCK_ERROR != 0

    inline val isSuccess
      get() = !isError

    inline val clipChanged
      get() = value and JAWT_LOCK_CLIP_CHANGED != 0

    inline val boundsChanged
      get() = value and JAWT_LOCK_BOUNDS_CHANGED != 0

    inline val surfaceChanged
      get() = value and JAWT_LOCK_SURFACE_CHANGED != 0
  }
}
