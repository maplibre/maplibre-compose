package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.DesktopComposeMapPresentationHost
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.RenderBackendPair

/**
 * A desktop host that allocates the texture MapLibre renders into, for producers that cannot
 * allocate a ring Compose can read. MapLibre renders into a texture of type [T] that Compose then
 * draws through a context of type [C].
 *
 * Subclasses allocate textures in [acquireFrame] and hand them to [textures]. This class draws
 * them, and releases each once Compose has drawn a newer generation.
 */
// TODO: lend MapLibre a ring of two or more textures, so that it renders the next frame while
// Compose samples the current one. Today the texture Compose draws is copied before MapLibre writes
// it again. Measure the benefit with benchmarks first.
internal abstract class SharedTextureMapHost<C : ComposeGpuContext, T : Any>(
  presentationHost: DesktopComposeMapPresentationHost<C>,
  backends: RenderBackendPair,
  rendererThreadName: String,
) : DesktopMapHost<C>(presentationHost, backends, rendererThreadName) {
  protected val textures = SharedTextures<T> { release(it) }

  /** Draws [texture], the texture of [generation], and returns whether anything was drawn. */
  protected abstract fun present(
    scope: DrawScope,
    context: C,
    texture: T,
    generation: Long,
    destination: MlnFfiMapDestination,
  ): Boolean

  /** Frees [texture] once Compose will not draw it again. */
  protected abstract fun release(texture: T)

  final override fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean {
    if (target.backend != producer) return false
    return withComposeContext { context ->
      frameCompletion.adoptContext(context.skiaContext, ::contextReplaced)
      val texture = textures[target.generation] ?: return@withComposeContext false
      val drew = present(scope, context, texture, target.generation, destination)
      if (drew) textures.releaseRetired(except = target.generation)
      drew
    } ?: false
  }
}

/**
 * The textures a host has handed to MapLibre, by [MlnFfiRenderTarget.generation]: the [current]
 * one, which MapLibre renders into, and retired ones Compose may still draw.
 *
 * A replaced texture stays until Compose has drawn a newer generation. A resize can race ahead of
 * the draw of the frame before it, and releasing the texture early makes the map flash transparent.
 */
internal class SharedTextures<T : Any>(private val release: (T) -> Unit) {
  private val retired = mutableMapOf<Long, T>()

  /** The generation of [current], or of the last texture replaced while there is none. */
  var generation = 0L
    private set

  /** The texture MapLibre renders into, or null when there is none. */
  var current: T? = null
    private set

  /** Every texture not yet released, [current] first. */
  val all: List<T>
    get() = listOfNotNull(current) + retired.values

  /**
   * The texture of [generation], or null once it is released. Not the texture [retireCurrent] took
   * out of use: its generation names no texture until the next [replaceCurrent].
   */
  operator fun get(generation: Long): T? =
    if (generation == this.generation) current else retired[generation]

  /** Makes [texture] current under the next generation, retiring the previous one. */
  fun replaceCurrent(texture: T?) {
    retireCurrent()
    current = texture
    generation += 1
  }

  /** Retires [current] without replacing it. */
  fun retireCurrent() {
    current?.let { retired[generation] = it }
    current = null
  }

  /** Takes [current] out without retiring it, for the caller to release. */
  fun takeCurrent(): T? = current.also { current = null }

  /** Releases every retired texture except the one of [except], which Compose just drew. */
  fun releaseRetired(except: Long? = null) {
    val iterator = retired.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key != except) {
        release(entry.value)
        iterator.remove()
      }
    }
  }

  /** Takes every texture out without releasing it, [current] first, for release in stages. */
  fun removeAll(): List<T> = all.also {
    current = null
    retired.clear()
  }

  /**
   * Releases every texture, [current] first. Each stays until its release returns, so after a
   * failure the caller can still reach, and retry, the ones not yet released.
   */
  fun releaseAll() {
    current?.let {
      release(it)
      current = null
    }
    releaseRetired()
  }
}

/**
 * Moves a host to the device Compose draws with now. MapLibre's render session still holds the
 * textures and producer context of the old device, so the first frame on a new device throws
 * [MlnFfiRecoverableFrameException]. Recovery closes that session and retries, and only then is it
 * safe to release them.
 */
internal class DeviceChangeRecovery<K : Any>(private val message: String) {
  private var pending: K? = null

  /**
   * Returns true when the host must now replace everything it made for [previous] with objects for
   * [next]. [previous] is null when the host holds nothing tied to a device.
   *
   * @throws MlnFfiRecoverableFrameException the first time [next] differs from [previous].
   */
  fun changed(previous: K?, next: K): Boolean {
    if (previous == null || previous == next) {
      pending = null
      return false
    }
    if (pending != next) {
      pending = next
      throw MlnFfiRecoverableFrameException(message, null)
    }
    pending = null
    return true
  }
}
