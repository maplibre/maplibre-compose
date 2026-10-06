package org.maplibre.compose.map

import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.checkStyleHandle

/**
 * A style image in one loaded style generation. Operations on an expired handle or an unready style
 * throw [StyleHandleException].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface StyleImageHandle {
  public val id: String
  /** Removal access, or null when composition owns this image. */
  public val asMutable: MutableStyleImageHandle?
}

/**
 * Permission to remove a style image.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MutableStyleImageHandle : StyleImageHandle {
  /** Enqueues removal. Expired handles fail locally; native refusals are logged. */
  public fun remove()
}

internal class StyleImageHandleImpl(
  override val id: String,
  private val style: MapStyleState,
  private val binding: StyleBinding,
) : StyleImageHandle {
  private val identity = binding.identity.images.get(id)

  override val asMutable: MutableStyleImageHandle?
    get() = operation {
      if (style.isImageWritable(id)) MutableStyleImageHandleImpl(this) else null
    }

  fun remove() = operation {
    style.requireImageWritable(id)
    style.owner.resourceCommands.removeImage(id, binding, identity)
  }

  private fun <T> operation(action: () -> T): T =
    style.operationGuard(binding).run {
      binding.requireCurrent()
      checkStyleHandle(binding.identity.images.isCurrent(id, identity)) {
        "Image '$id' has been removed or replaced"
      }
      action()
    }
}

private class MutableStyleImageHandleImpl(private val image: StyleImageHandleImpl) :
  MutableStyleImageHandle, StyleImageHandle by image {
  override fun remove() = image.remove()
}
