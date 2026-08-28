package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.style.GENERATED_ID_PREFIX
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.rasterizePainter
import org.maplibre.compose.util.ImageStretch

/**
 * The style images that the application registered, exposed on [MapState.images].
 *
 * [add] and [remove] mutate the loaded style directly, beside the images that the style
 * composition's painter and bitmap parameters register. An image registered here resolves
 * `iconImage` and every other style-spec image reference by its id.
 *
 * An id that a base style's sprite also defines is replaced: the last writer wins, in either
 * direction, because sprites load asynchronously. See [MapState] for the reload rule that applies
 * to every imperative style mutation.
 */
public class StyleImages internal constructor(private val state: MapState) {

  /**
   * The ids registered through this state, in registration order. The loaded style's full image
   * list, including sprite images and the style composition's registrations, is not enumerable. A
   * composition that reads this property recomposes when the list changes.
   */
  public val ids: List<String>
    get() = state.publishedAppImages

  /**
   * Registers [image] under [id] in the loaded style. An id this state already registered is
   * replaced.
   *
   * A [MapState.baseStyle] reload drops the image; reapply it after the load.
   *
   * @param sdf whether MapLibre reads the pixels as a signed distance field.
   * @param stretch the regions that stretch when a symbol scales the image.
   * @throws IllegalArgumentException when [id] uses the library's reserved id prefix.
   * @throws IllegalStateException when no style is loaded or the state is closed.
   */
  public suspend fun add(
    id: String,
    image: ImageBitmap,
    sdf: Boolean = false,
    stretch: ImageStretch? = null,
  ) {
    add(id) { binding -> binding.addImage(id, image, sdf, stretch) }
  }

  /**
   * Registers [painter] under [id] in the loaded style, rasterized at the state's density and
   * layout direction at the time of the call. An id this state already registered is replaced.
   *
   * A [MapState.baseStyle] reload drops the image; reapply it after the load.
   *
   * @param size the rasterized size; null takes the painter's intrinsic size.
   * @param sdf whether the rasterization is converted to a signed distance field.
   * @param stretch the regions that stretch when a symbol scales the image.
   * @throws IllegalArgumentException when [id] uses the library's reserved id prefix.
   * @throws IllegalStateException when no style is loaded or the state is closed.
   */
  public suspend fun add(
    id: String,
    painter: Painter,
    size: DpSize? = null,
    sdf: Boolean = false,
    stretch: ImageStretch? = null,
  ) {
    add(id) { binding ->
      val bitmap =
        rasterizePainter(
          painter = painter,
          density = state.host.density,
          layoutDirection = state.host.layoutDirection,
          size = size,
          asSdf = sdf,
        )
      binding.addImage(id, bitmap, sdf, stretch)
    }
  }

  private suspend fun add(id: String, addTo: (StyleBinding) -> Unit) {
    require(!id.startsWith(GENERATED_ID_PREFIX)) {
      "Image id '$id' uses the reserved prefix '$GENERATED_ID_PREFIX'"
    }
    state.runStyleEffect { binding -> addAccepted(binding, id, addTo)?.let { throw it } }
  }

  /**
   * Removes the image with [id], which [add] registered, from the loaded style.
   *
   * @throws IllegalArgumentException when this state did not register [id].
   * @throws IllegalStateException when no style is loaded or the state is closed.
   */
  public suspend fun remove(id: String) {
    state.runStyleEffect { binding -> removeAccepted(binding, id)?.let { throw it } }
  }

  private fun addAccepted(
    binding: StyleBinding,
    id: String,
    addTo: (StyleBinding) -> Unit,
  ): Throwable? {
    val refusal =
      state.record.read {
        when {
          closed ->
            IllegalStateException("MapState is closed; a closed state cannot mutate the style")
          this.binding !== binding ->
            IllegalStateException("Image '$id' was not added: the style unloaded during the add")
          else -> null
        }
      }
    if (refusal != null) return refusal
    if (!binding.isLoaded) {
      return IllegalStateException("No loaded style; an image can only be added to a loaded style")
    }
    return try {
      addTo(binding)
      if (!state.commitAppImage(binding, id)) {
        IllegalStateException("Image '$id' was not added: the style unloaded during the add")
      } else {
        null
      }
    } catch (error: Throwable) {
      error
    }
  }

  private fun removeAccepted(binding: StyleBinding, id: String): Throwable? {
    val refusal =
      state.record.read {
        when {
          closed ->
            IllegalStateException("MapState is closed; a closed state cannot mutate the style")
          this.binding !== binding ->
            IllegalStateException(
              "Image '$id' was not removed: the style unloaded during the removal"
            )
          else -> null
        }
      }
    if (refusal != null) return refusal
    if (!binding.isLoaded) {
      return IllegalStateException(
        "No loaded style; an image can only be removed from a loaded style"
      )
    }
    if (state.record.read { id !in appImages }) {
      return IllegalArgumentException("Image id '$id' was not added through this state")
    }
    return try {
      binding.removeImage(id)
      if (!state.commitAppImageRemoval(binding, id)) {
        IllegalStateException("Image '$id' was not removed: the style unloaded during the removal")
      } else {
        null
      }
    } catch (error: Throwable) {
      error
    }
  }
}
