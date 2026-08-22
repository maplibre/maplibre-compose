package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import kotlinx.cinterop.useContents
import org.maplibre.compose.map.MapExtent
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

/** An iOS surface that the Metal FFI runtime presents into directly. */
@Composable
internal fun IosMlnFfiSurface(
  renderer: MlnFfiMapRenderer,
  runtimeBackends: Set<MapRenderBackend>,
  maximumFps: Int? = null,
  modifier: Modifier,
  logger: Logger?,
  presentWindow: Boolean = true,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val controller = remember(renderer) { IosMlnFfiSurfaceController(renderer, logger, maximumFps) }
  val available = MapRenderBackend.METAL in runtimeBackends

  SideEffect { controller.setMaximumFps(maximumFps) }

  DisposableEffect(controller, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> controller.setActive(true)
        Lifecycle.Event.ON_STOP -> controller.setActive(false)
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    controller.setActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(controller) { onDispose { controller.close() } }

  if (!available) {
    DisposableEffect(runtimeBackends) {
      logger?.e {
        "iOS MapLibre Compose requires the Metal maplibre-native-ffi runtime; " +
          "available backends: ${runtimeBackends.joinToString().ifEmpty { "none" }}"
      }
      onDispose {}
    }
    return
  }

  // The map loop loads styles without a surface, so until the first style arrives no Metal view
  // goes into the hierarchy. The placeholder keeps the map's layout size and gestures.
  if (!presentWindow) {
    Box(modifier)
    return
  }

  // The Metal view only presents. Compose's mapInput modifier handles every gesture.
  UIKitView(
    modifier = modifier,
    factory = {
      val view = IosMetalMapView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
      view.onLayoutChanged = { extent ->
        controller.surfaceLayoutChanged(view.layerAddress, extent)
      }
      view
    },
    update = {},
    onRelease = { controller.surfaceDestroyed() },
    properties =
      UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
  )
}

/**
 * A view whose layer is a `CAMetalLayer` that MapLibre presents into.
 *
 * The view keeps the layer's `contentsScale` in step with its screen and reports each settled
 * extent to the surface controller; the render session writes `drawableSize` from that extent.
 * Compose's gesture modifier owns every pointer, and this view declines UIKit hit-testing.
 */
private class IosMetalMapView(frame: CValue<CGRect>) : UIView(frame) {
  var onLayoutChanged: ((extent: MapExtent) -> Unit)? = null

  /**
   * The address of this view's `CAMetalLayer`. The address is valid while the view is alive, and
   * the view outlives the render session that borrows its layer.
   */
  val layerAddress: Long
    get() = metalLayer.objcPtr().toLong()

  private val metalLayer: CAMetalLayer
    get() = layer as CAMetalLayer

  init {
    userInteractionEnabled = false
    // Never opaque, matching MLNMapView: an unpresented Metal layer would otherwise fill with
    // black.
    opaque = false
    metalLayer.opaque = false
    metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm
  }

  override fun layoutSubviews() {
    super.layoutSubviews()
    val scale = window?.screen?.scale ?: UIScreen.mainScreen.scale
    contentScaleFactor = scale
    val width = bounds.useContents { size.width }
    val height = bounds.useContents { size.height }
    if (width <= 0.0 || height <= 0.0) return
    val physicalWidth = (width * scale).toInt()
    val physicalHeight = (height * scale).toInt()
    val layer = metalLayer
    layer.contentsScale = scale
    onLayoutChanged?.invoke(MapExtent.fromPhysical(physicalWidth, physicalHeight, scale))
  }

  companion object : UIViewMeta() {
    @OptIn(BetaInteropApi::class) override fun layerClass(): ObjCClass = CAMetalLayer
  }
}
