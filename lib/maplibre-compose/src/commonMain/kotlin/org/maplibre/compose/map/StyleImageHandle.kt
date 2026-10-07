package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import org.maplibre.compose.style.StyleBinding

/**
 * A style image in one loaded style generation. The handle expires when the image is replaced or
 * removed, or when the style reloads. An expired handle reads null and ignores writes, which log a
 * warning.
 */
public sealed interface StyleImageHandle {
  public val id: String
  /** Removal access, or null when composition owns this image. */
  public val asMutable: MutableStyleImageHandle?
}

/** Permission to remove a style image. */
public sealed interface MutableStyleImageHandle : StyleImageHandle {
  /**
   * Enqueues removal. An expired handle does nothing and logs a warning; native refusals are
   * logged.
   *
   * @throws IllegalStateException if a removal through this handle has already removed the image.
   */
  public fun remove()
}

internal class StyleImageHandleImpl(
  override val id: String,
  private val style: MapStyleState,
  private val binding: StyleBinding,
) : StyleImageHandle {
  private val identity = binding.identity.images.get(id)
  // Set once a removal through this handle has run; using the handle after that is misuse. A
  // removal that a later write supersedes leaves it unset.
  @Volatile private var removed = false

  override val asMutable: MutableStyleImageHandle?
    get() = if (begin() && style.isImageWritable(id)) MutableStyleImageHandleImpl(this) else null

  fun remove() {
    if (!begin()) {
      binding.logger?.w { "Image '$id' ignored its removal: the handle has expired" }
      return
    }
    style.requireImageWritable(id)
    style.owner.resourceCommands.removeImage(id, binding, identity) { removed = true }
  }

  /**
   * Starts an operation and returns whether this handle is live.
   *
   * @throws IllegalStateException when the map is closed, or when [remove] on this handle has
   *   removed the image.
   */
  private fun begin(): Boolean {
    style.owner.requireOpen()
    val current = binding.identity.images.isCurrent(id, identity)
    check(!removed) { "Image '$id' was removed through this handle" }
    return current && style.owner.isCurrent(binding) && binding.isLoaded
  }
}

private class MutableStyleImageHandleImpl(private val image: StyleImageHandleImpl) :
  MutableStyleImageHandle, StyleImageHandle by image {
  override fun remove() = image.remove()
}
