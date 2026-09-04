package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.util.ImageStretch

/**
 * Supplies the image that the engine asks for by id, or null when the resolver has none.
 *
 * See [MapState.missingImageResolver].
 */
public typealias MissingImageResolver = suspend (id: String) -> ResolvedStyleImage?

/**
 * The image that a [MissingImageResolver] supplies, with the options that [StyleImages.add] takes
 * for it.
 */
@Immutable
public data class ResolvedStyleImage(
  /** The pixels that the engine draws. */
  public val image: ImageBitmap,
  /** Whether [image] is a signed distance field, which a layer recolors. */
  public val sdf: Boolean = false,
  /** Stretch and content box for an icon that a symbol layer sizes to wrap its text. */
  public val stretch: ImageStretch? = null,
)
