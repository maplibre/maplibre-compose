@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.macos

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeOtherMouseDragged
import platform.AppKit.NSEventTypeRightMouseDragged
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect

class AppKitMapHostTest {
  @Test
  fun forwards_non_primary_drag_movement_to_the_existing_compose_view_before_release() {
    NSApplication.sharedApplication()
    val window = NSWindow(NSMakeRect(0.0, 0.0, 400.0, 300.0), 0u, NSBackingStoreBuffered, false)
    window.releasedWhenClosed = false
    val received = mutableListOf<NSEvent>()
    window.contentView =
      object : NSView(window.frame) {
        override fun mouseDragged(event: NSEvent) {
          received.add(event)
        }
      }
    AppKitMapHost(window).use { host ->
      host.attach()
      for (type in listOf(NSEventTypeRightMouseDragged, NSEventTypeOtherMouseDragged)) {
        val event =
          checkNotNull(
            NSEvent.mouseEventWithType(
              type,
              NSMakePoint(120.0, 80.0),
              0u,
              1.0,
              window.windowNumber,
              null,
              1,
              1,
              0f,
            )
          )
        assertNull(host.forwardDrag(event))
        assertEquals(event, received.last())
      }
      assertEquals(2, received.size)
    }
    window.close()
  }

  @Test
  fun clipped_layout_preserves_full_map_extent_and_converts_top_left_pixels_to_appkit_points() {
    NSApplication.sharedApplication()
    val window = NSWindow(NSMakeRect(0.0, 0.0, 400.0, 300.0), 0u, NSBackingStoreBuffered, false)
    window.releasedWhenClosed = false
    val original = checkNotNull(window.contentView)
    val host = AppKitMapHost(window)
    var destroyed = 0
    var layout: Rect? = null
    var visible = false
    val view = NSView(NSMakeRect(0.0, 0.0, 0.0, 0.0))
    val entry =
      AppKitMapEntry(
        view,
        { bounds, _, shown ->
          layout = bounds
          visible = shown
        },
        { destroyed++ },
      )
    host.attach()
    host.add(entry)
    val bounds = Rect(40f, -20f, 440f, 180f)
    host.layout(entry, bounds, Rect(40f, 0f, 440f, 180f), 2f)
    assertEquals(view.superview, entry.clipView)
    entry.clipView.frame.useContents {
      assertEquals(20.0, origin.x)
      assertEquals(210.0, origin.y)
      assertEquals(200.0, size.width)
      assertEquals(90.0, size.height)
    }
    view.frame.useContents {
      assertEquals(0.0, origin.x)
      assertEquals(0.0, origin.y)
      assertEquals(200.0, size.width)
      assertEquals(100.0, size.height)
    }
    assertEquals(bounds, layout)
    assertTrue(visible)
    host.layout(entry, bounds, Rect.Zero, 2f)
    assertTrue(entry.clipView.hidden)
    assertFalse(visible)
    host.close()
    host.close()
    assertEquals(1, destroyed)
    assertEquals(original, window.contentView)
    window.close()
  }
}
