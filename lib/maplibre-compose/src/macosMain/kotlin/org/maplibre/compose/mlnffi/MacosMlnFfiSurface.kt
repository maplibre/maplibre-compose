package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.macos.AppKitMapEntry
import org.maplibre.compose.macos.LocalAppKitMapHost
import org.maplibre.compose.map.MapExtent
import platform.AppKit.NSView
import platform.Foundation.NSMakeRect
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.QuartzCore.CAMetalLayer

@OptIn(BetaInteropApi::class)
@Composable
internal fun MacosMlnFfiSurface(
  renderer: MlnFfiMapRenderer,
  runtimeBackends: Set<MapRenderBackend>,
  maximumFps: Int?,
  modifier: Modifier,
  logger: MapLog?,
  presentWindow: Boolean,
) {
  check(MapRenderBackend.METAL in runtimeBackends) { "macOS Native requires the Metal FFI runtime" }
  val host = LocalAppKitMapHost.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val controller =
    remember(renderer, host) { AppleMlnFfiSurfaceController(renderer, logger, maximumFps) }
  DisposableEffect(controller) { onDispose { controller.close() } }
  SideEffect { controller.setMaximumFps(maximumFps) }
  DisposableEffect(controller, lifecycle) {
    val observer = LifecycleEventObserver { _, _ ->
      controller.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    lifecycle.addObserver(observer)
    controller.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    onDispose { lifecycle.removeObserver(observer) }
  }
  if (!presentWindow) {
    Box(modifier)
    return
  }
  val entry =
    remember(controller) {
      val layer =
        CAMetalLayer().apply {
          opaque = false
          pixelFormat = MTLPixelFormatBGRA8Unorm
        }
      val view =
        NSView(NSMakeRect(0.0, 0.0, 0.0, 0.0)).apply {
          wantsLayer = true
          this.layer = layer
        }
      AppKitMapEntry(
        view,
        resized = { bounds, scale, visible ->
          layer.contentsScale = scale.toDouble()
          if (visible && !bounds.isEmpty) {
            controller.surfaceLayoutChanged(
              layer.objcPtr().toLong(),
              MapExtent.fromPhysical(bounds.width.toInt(), bounds.height.toInt(), scale.toDouble()),
            )
          } else {
            controller.surfaceDestroyed()
          }
        },
        detach = { controller.surfaceDestroyed() },
      )
    }
  DisposableEffect(host, entry) {
    host.add(entry)
    onDispose { host.remove(entry) }
  }
  val scale = LocalDensity.current.density
  Box(
    modifier
      .onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        val bounds =
          Rect(
            position.x,
            position.y,
            position.x + coordinates.size.width,
            position.y + coordinates.size.height,
          )
        host.layout(entry, bounds, coordinates.boundsInRoot(), scale)
      }
      .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
  )
}
