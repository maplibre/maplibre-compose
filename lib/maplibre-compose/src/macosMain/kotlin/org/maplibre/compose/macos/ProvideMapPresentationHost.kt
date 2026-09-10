package org.maplibre.compose.macos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlinx.cinterop.useContents
import platform.AppKit.NSEvent
import platform.AppKit.NSEventMaskOtherMouseDragged
import platform.AppKit.NSEventMaskRightMouseDragged
import platform.AppKit.NSView
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewWidthSizable
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowWillCloseNotification
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.QuartzCore.CATransaction

/**
 * Installs Metal map views beneath [window]'s existing Compose content view.
 *
 * Call once around the content of a native macOS Compose `Window`. The host preserves Compose's
 * renderer and input view. Maps clear their drawing area to reveal the native view; draw overlays
 * after the map. Use layout positioning and sizing to move and resize maps. Graphics-layer
 * translation, scaling, rotation, overlapping maps, and ancestors that force offscreen composition
 * or group opacity are not supported.
 *
 * The host detaches render surfaces when the window closes or this composition is disposed. The
 * application remains responsible for disposing its Compose scene and map state.
 */
@Composable
public fun ProvideMapPresentationHost(window: NSWindow, content: @Composable () -> Unit) {
  val host = remember(window) { AppKitMapHost(window) }
  DisposableEffect(host) {
    host.attach()
    onDispose { host.close() }
  }
  CompositionLocalProvider(LocalAppKitMapHost provides host, content = content)
}

internal val LocalAppKitMapHost =
  staticCompositionLocalOf<AppKitMapHost> {
    error(
      "Wrap this window's content in org.maplibre.compose.macos.ProvideMapPresentationHost(window)"
    )
  }

internal class AppKitMapHost(private val window: NSWindow) : AutoCloseable {
  private val composeView = checkNotNull(window.contentView)
  private val container = NSView(composeView.frame)
  private val entries = mutableListOf<AppKitMapEntry>()
  private var observer: platform.darwin.NSObjectProtocol? = null
  private var dragMonitor: Any? = null
  private var attached = false
  private var closed = false

  fun attach() {
    check(NSThread.isMainThread)
    if (attached || closed) return
    check(window.contentView == composeView) { "Install only one map presentation host per window" }
    composeView.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
    window.contentView = container
    container.addSubview(composeView)
    attached = true
    entries.forEach { addView(it) }
    // Compose 1.12's native Window implements mouseDragged but omits rightMouseDragged and
    // otherMouseDragged. Its existing handler forwards movement using the current button state.
    dragMonitor =
      NSEvent.addLocalMonitorForEventsMatchingMask(
        NSEventMaskRightMouseDragged or NSEventMaskOtherMouseDragged
      ) { event ->
        event?.let(::forwardDrag)
      }
    observer =
      NSNotificationCenter.defaultCenter.addObserverForName(
        NSWindowWillCloseNotification,
        window,
        NSOperationQueue.mainQueue,
      ) {
        close()
      }
  }

  internal fun forwardDrag(event: NSEvent): NSEvent? {
    if (event.window != window) return event
    composeView.mouseDragged(event)
    return null
  }

  fun add(entry: AppKitMapEntry) {
    check(!closed) { "Map presentation window is closed" }
    entries.add(entry)
    if (attached) addView(entry)
  }

  private fun addView(entry: AppKitMapEntry) {
    container.addSubview(
      entry.clipView,
      positioned = platform.AppKit.NSWindowBelow,
      relativeTo = composeView,
    )
  }

  fun remove(entry: AppKitMapEntry) {
    if (!entries.remove(entry)) return
    entry.detach()
    entry.clipView.removeFromSuperview()
  }

  override fun close() {
    if (closed) return
    closed = true
    entries.toList().forEach(::remove)
    observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
    observer = null
    dragMonitor?.let { NSEvent.removeMonitor(it) }
    dragMonitor = null
    if (window.contentView == container) {
      composeView.removeFromSuperview()
      window.contentView = composeView
    }
  }

  fun layout(entry: AppKitMapEntry, bounds: Rect, visible: Rect, scale: Float) {
    if (closed) return
    val height = composeView.bounds.useContents { size.height }
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    try {
      entry.clipView.hidden = visible.isEmpty
      entry.clipView.setFrame(
        NSMakeRect(
          visible.left / scale.toDouble(),
          height - visible.bottom / scale,
          visible.width.coerceAtLeast(0f) / scale.toDouble(),
          visible.height.coerceAtLeast(0f) / scale.toDouble(),
        )
      )
      entry.view.setFrame(
        NSMakeRect(
          (bounds.left - visible.left) / scale.toDouble(),
          (visible.bottom - bounds.bottom) / scale.toDouble(),
          bounds.width / scale.toDouble(),
          bounds.height / scale.toDouble(),
        )
      )
      entry.resized(bounds, scale, !visible.isEmpty)
    } finally {
      CATransaction.commit()
    }
  }
}

internal class AppKitMapEntry(
  val view: NSView,
  val resized: (Rect, Float, Boolean) -> Unit,
  val detach: () -> Unit,
) {
  val clipView =
    NSView(NSMakeRect(0.0, 0.0, 0.0, 0.0)).apply {
      wantsLayer = true
      layer!!.masksToBounds = true
      addSubview(view)
    }
}
