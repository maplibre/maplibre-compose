package org.maplibre.kmp.native.map

import java.awt.Canvas
import java.awt.EventQueue
import org.maplibre.kmp.native.util.AutoCleanPointer
import org.maplibre.kmp.native.util.Size
import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public class CanvasRenderer(
  @get:CalledByNative @get:JvmName("getCanvas") internal val canvas: Canvas,
  pixelRatio: Float,
) : RendererFrontend {
  internal val nativePeer =
    AutoCleanPointer(
      new = { alloc(canvas = this, pixelRatio = pixelRatio) },
      destroy = { destroy(it) },
    )

  @get:CalledByNative
  @get:JvmName("getNativePointer")
  override val nativePointer: Long
    get() = nativePeer.rawPtr

  public fun dispose(): Unit = nativePeer.clean()

  public external fun render()

  public external fun setSize(size: Size)

  public external fun runOnce()

  @Suppress("unused")
  @CalledByNative
  private fun requestCanvasRepaint() {
    if (!EventQueue.isDispatchThread()) EventQueue.invokeLater { canvas.repaint() }
    else canvas.repaint()
  }

  @Suppress("unused")
  @CalledByNative
  private fun requestRunOnce() {
    EventQueue.invokeLater { runOnce() }
  }

  private companion object Companion {
    @JvmStatic external fun alloc(canvas: CanvasRenderer, pixelRatio: Float): Long

    @JvmStatic external fun destroy(ptr: Long)
  }
}
