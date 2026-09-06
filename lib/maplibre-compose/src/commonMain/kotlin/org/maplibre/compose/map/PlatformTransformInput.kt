package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * Android also classifies the pointer events surrounding a trackpad pan or pinch. Keep those
 * contacts out of tap/drag recognition. Explicit Compose pan/scale types use [isPlatformTransform].
 */
internal expect fun hasAndroidTransformClassification(event: PointerEvent): Boolean

internal fun isPlatformTransform(type: PointerEventType): Boolean =
  type == PointerEventType.ScaleStart ||
    type == PointerEventType.ScaleChange ||
    type == PointerEventType.ScaleEnd ||
    type == PointerEventType.PanStart ||
    type == PointerEventType.PanMove ||
    type == PointerEventType.PanEnd

/** Classified wrappers stay out of ordinary recognition until their reported contacts lift. */
internal class PlatformTransformRouting {
  enum class Kind {
    Scale,
    Pan,
  }

  val suppressed = mutableSetOf<Kind>()
  private val contacts = mutableSetOf<PointerId>()
  var blocked: Boolean = false
    private set

  val hasContacts: Boolean
    get() = contacts.isNotEmpty()

  fun intercept() {
    blocked = contacts.isNotEmpty()
  }

  fun route(
    type: PointerEventType,
    classified: Boolean,
    changes: List<PointerInputChange>,
  ): Boolean {
    if (type == PointerEventType.Scroll) return false
    val routed = classified || isPlatformTransform(type) || changes.any { it.id in contacts }
    if (routed) {
      changes.forEach { if (it.pressed) contacts.add(it.id) else contacts.remove(it.id) }
      if (contacts.isEmpty()) blocked = false
    }
    return routed
  }
}
