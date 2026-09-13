package org.maplibre.compose.map

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

/**
 * A UIKit map surface backed by [presentation]. Add it to your view hierarchy and size it with
 * UIKit layout. Its window supplies the display scale, including on a CarPlay display.
 *
 * The view attaches its Metal layer while it has a window and a positive size, and detaches when
 * removed or given an empty size. It does not own the presentation: the host controls activation
 * and closes the presentation when finished. One view or layer can attach to a presentation at a
 * time.
 *
 * This view only renders. It installs no gesture recognizers; forward host gestures through
 * [AppleMapPresentation.state]. Compose's [MaplibreMap] supplies its own input handling. Create and
 * use this view on the main thread.
 */
public class MaplibreMapView(
  public val presentation: AppleMapPresentation,
  frame: CValue<CGRect> = CGRectMake(0.0, 0.0, 0.0, 0.0),
) : UIView(frame) {
  private var binding: AppleMapPresentation.LayerBinding? = null

  private val metalLayer: CAMetalLayer
    get() = layer as CAMetalLayer

  init {
    presentation.checkOpen()
    opaque = false
    metalLayer.opaque = false
    metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm
  }

  override fun didMoveToWindow() {
    super.didMoveToWindow()
    updateLayer()
  }

  override fun layoutSubviews() {
    super.layoutSubviews()
    updateLayer()
  }

  private fun updateLayer() {
    val screen = window?.screen
    val width = bounds.useContents { size.width }
    val height = bounds.useContents { size.height }
    if (screen == null || width <= 0.0 || height <= 0.0) {
      detach()
      return
    }
    // A closed state or failed presenter leaves an inert view until the host removes it.
    if (presentation.isClosed || presentation.state.isClosed) return
    val scale = screen.scale
    contentScaleFactor = scale
    metalLayer.contentsScale = scale
    val physicalWidth = (width * scale).toInt().coerceAtLeast(1)
    val physicalHeight = (height * scale).toInt().coerceAtLeast(1)
    val current = binding
    if (current == null) {
      binding = presentation.attachLayer(metalLayer, physicalWidth, physicalHeight, scale.toFloat())
    } else {
      current.update(physicalWidth, physicalHeight, scale.toFloat())
    }
  }

  internal fun detach() {
    binding?.close()
    binding = null
  }

  public companion object : UIViewMeta() {
    @OptIn(BetaInteropApi::class) override fun layerClass(): ObjCClass = CAMetalLayer
  }
}
