package org.maplibre.compose.map

import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException

/** A style image in one loaded style generation. */
public sealed interface StyleImageHandle {
  public val id: String
  /** Removal access, or null when composition owns this image. */
  public val asMutable: MutableStyleImageHandle?
}

/** Permission to remove a style image. */
public sealed interface MutableStyleImageHandle : StyleImageHandle {
  /** Removes this image. Fails if this handle has expired. */
  public fun remove(): Boolean
}

internal class StyleImageHandleImpl(
  override val id: String,
  private val style: MapStyleState,
  private val binding: StyleBinding,
) : StyleImageHandle {
  private val identity = binding.identity.images.get(id)

  override val asMutable: MutableStyleImageHandle?
    get() = operation {
      if (style.requireOwner().isImageWritable(id)) MutableStyleImageHandleImpl(this) else null
    }

  fun remove(): Boolean = operation {
    if (!style.requireOwner().isImageWritable(id)) {
      throw StyleHandleException("Image ID '$id' is declared by the style content")
    }
    style.requireOwner().removeStyleImage(id, binding, identity)
  }

  private fun <T> operation(action: () -> T): T =
    style.operationGuard(binding).run {
      binding.requireCurrent()
      check(binding.identity.images.isCurrent(id, identity)) {
        "Image '$id' has been removed or replaced"
      }
      action()
    }
}

private class MutableStyleImageHandleImpl(private val image: StyleImageHandleImpl) :
  MutableStyleImageHandle, StyleImageHandle by image {
  override fun remove(): Boolean = image.remove()
}
