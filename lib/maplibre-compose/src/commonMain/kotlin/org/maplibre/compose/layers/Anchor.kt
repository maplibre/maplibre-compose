package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import org.maplibre.compose.util.MaplibreComposable

internal val LocalAnchor: ProvidableCompositionLocal<Anchor> = compositionLocalOf { Anchor.Top }

/**
 * Declares where the layers should be anchored, i.e. positioned in the list of layers in the map
 * style.
 *
 * This allows for layers declared in Compose to be inserted at any location of the layers defined
 * in the base style JSON rather than exclusively on top of these.
 *
 * **Note:** This mechanism can only be used to anchor layers at `layerId`s from the *base map
 * style* referred to in the `baseStyle` parameter of the
 * [MapLibreMap][org.maplibre.compose.map.MaplibreMap] composable. Anchoring layers defined in the
 * composition to other layers defined in the composition is not possible.
 *
 * See [Anchor.Companion] for [Composable] functions to use in the layers composition.
 */
@Immutable
public sealed interface Anchor {
  /**
   * Layer(s) are anchored at the top, i.e. in front of all other layers. See [Anchor.Companion.Top]
   * to use this in the layers composition.
   */
  public data object Top : Anchor

  /**
   * Layer(s) are anchored at the bottom, i.e. in behind of all other layers. See
   * [Anchor.Companion.Bottom] to use this in the layers composition.
   */
  public data object Bottom : Anchor

  /**
   * Layer(s) are anchored above the layer (i.e. in front of it) with the given [layerId] from the
   * base map style. See [Anchor.Companion.Above] to use this in the layers composition.
   */
  public data class Above(val layerId: String) : Anchor

  /**
   * Layer(s) are anchored below the layer (i.e. behind it) with the given [layerId] from the base
   * map style. See [Anchor.Companion.Below] to use this in the layers composition.
   */
  public data class Below(val layerId: String) : Anchor

  /**
   * Layer(s) replace the layer (i.e. are shown instead of it) with the given [layerId] from the
   * base map style. See [Anchor.Companion.Replace] to use this in the layers composition.
   */
  public data class Replace(val layerId: String) : Anchor

  public companion object {
    /** The layers specified in [block] are put at the top, i.e. in front of all other layers. */
    @Composable
    @MaplibreComposable
    public fun Top(block: @Composable () -> Unit): Unit = At(Top, block)

    /** The layers specified in [block] are put at the bottom, i.e. behind of all other layers. */
    @Composable
    @MaplibreComposable
    public fun Bottom(block: @Composable () -> Unit): Unit = At(Bottom, block)

    /**
     * The layers specified in [block] are put above the layer (i.e. in front of it) with the given
     * [layerId] from the base map style.
     */
    @Composable
    @MaplibreComposable
    public fun Above(layerId: String, block: @Composable () -> Unit): Unit =
      At(Above(layerId), block)

    /**
     * The layers specified in [block] are put below the layer (i.e. behind it) with the given
     * [layerId] from the base map style.
     */
    @Composable
    @MaplibreComposable
    public fun Below(layerId: String, block: @Composable () -> Unit): Unit =
      At(Below(layerId), block)

    /**
     * The layers specified in [block] replace the layer (i.e. are shown instead of it) with the
     * given [layerId] from the base map style.
     */
    @Composable
    @MaplibreComposable
    public fun Replace(layerId: String, block: @Composable () -> Unit): Unit =
      At(Replace(layerId), block)

    /** The layers specified in [block] are put at the given [Anchor]. */
    @Composable
    @MaplibreComposable
    public fun At(anchor: Anchor, block: @Composable () -> Unit) {
      CompositionLocalProvider(LocalAnchor provides anchor) { block() }
    }
  }
}
