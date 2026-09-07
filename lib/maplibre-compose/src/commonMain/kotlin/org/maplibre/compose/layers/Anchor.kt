package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import org.maplibre.compose.util.MaplibreComposable

internal val LocalAnchor: ProvidableCompositionLocal<Anchor> = compositionLocalOf { Anchor.Top }

/**
 * Declares where layers from the style content are placed in the layer stack of the loaded base
 * style: [Top], [Bottom], [Above], or [Below]. Layers that resolve to the same position keep their
 * order from the style content.
 *
 * See [Anchor.Companion] for the composable functions that apply an anchor to a block of layers.
 */
@Immutable
public sealed interface Anchor {
  /**
   * Layers are placed over every other layer. See [Anchor.Companion.Top] to use this in the style
   * content.
   */
  public data object Top : Anchor

  /**
   * Layers are placed under every other layer. See [Anchor.Companion.Bottom] to use this in the
   * style content.
   */
  public data object Bottom : Anchor

  /**
   * Layers are placed directly over the highest base-style layer that [predicate] accepts, or at
   * the bottom of the stack when it accepts none. Layers declared in the style content are not
   * candidates, and the [LayerHandle] passed to the predicate is read-only. The predicate runs
   * outside composition, so snapshot state it reads is not observed; read state in composition and
   * capture the values. See [Anchor.Companion.Above] to use this in the style content.
   */
  public class Above private constructor(private val selector: LayerSelector) : Anchor {
    public constructor(predicate: (LayerHandle) -> Boolean) : this(LayerSelector(predicate))

    /** Anchors over the base-style layer with the given [layerId]. */
    public constructor(layerId: String) : this(LayerSelector(layerId))

    public val predicate: (LayerHandle) -> Boolean
      get() = selector.predicate

    override fun equals(other: Any?): Boolean = other is Above && selector == other.selector

    override fun hashCode(): Int = selector.hashCode()

    override fun toString(): String = "Above($selector)"
  }

  /**
   * Layers are placed directly under the lowest base-style layer that [predicate] accepts, or at
   * the top of the stack when it accepts none. Layers declared in the style content are not
   * candidates, and the [LayerHandle] passed to the predicate is read-only. The predicate runs
   * outside composition, so snapshot state it reads is not observed; read state in composition and
   * capture the values. See [Anchor.Companion.Below] to use this in the style content.
   */
  public class Below private constructor(private val selector: LayerSelector) : Anchor {
    public constructor(predicate: (LayerHandle) -> Boolean) : this(LayerSelector(predicate))

    /** Anchors under the base-style layer with the given [layerId]. */
    public constructor(layerId: String) : this(LayerSelector(layerId))

    public val predicate: (LayerHandle) -> Boolean
      get() = selector.predicate

    override fun equals(other: Any?): Boolean = other is Below && selector == other.selector

    override fun hashCode(): Int = selector.hashCode()

    override fun toString(): String = "Below($selector)"
  }

  public companion object {
    /** The layers specified in [block] are placed over every other layer. */
    @Composable
    @MaplibreComposable
    public fun Top(block: @Composable () -> Unit): Unit = At(Top, block)

    /** The layers specified in [block] are placed under every other layer. */
    @Composable
    @MaplibreComposable
    public fun Bottom(block: @Composable () -> Unit): Unit = At(Bottom, block)

    /**
     * The layers specified in [block] are placed directly over the base-style layer with the given
     * [layerId], or at the bottom of the stack when the base style has no such layer.
     */
    @Composable
    @MaplibreComposable
    public fun Above(layerId: String, block: @Composable () -> Unit): Unit =
      At(Above(layerId), block)

    /**
     * The layers specified in [block] are placed directly over the highest base-style layer that
     * [predicate] accepts, or at the bottom of the stack when it accepts none. See [Anchor.Above].
     */
    @Composable
    @MaplibreComposable
    public fun Above(predicate: (LayerHandle) -> Boolean, block: @Composable () -> Unit): Unit =
      At(Above(predicate), block)

    /**
     * The layers specified in [block] are placed directly under the base-style layer with the given
     * [layerId], or at the top of the stack when the base style has no such layer.
     */
    @Composable
    @MaplibreComposable
    public fun Below(layerId: String, block: @Composable () -> Unit): Unit =
      At(Below(layerId), block)

    /**
     * The layers specified in [block] are placed directly under the lowest base-style layer that
     * [predicate] accepts, or at the top of the stack when it accepts none. See [Anchor.Below].
     */
    @Composable
    @MaplibreComposable
    public fun Below(predicate: (LayerHandle) -> Boolean, block: @Composable () -> Unit): Unit =
      At(Below(predicate), block)

    /** The layers specified in [block] are placed at the given [Anchor]. */
    @Composable
    @MaplibreComposable
    public fun At(anchor: Anchor, block: @Composable () -> Unit) {
      CompositionLocalProvider(LocalAnchor provides anchor) { block() }
    }
  }
}

/** The ID form compares by ID; the predicate form compares by reference. */
private class LayerSelector(val predicate: (LayerHandle) -> Boolean, val layerId: String? = null) {
  constructor(layerId: String) : this({ it.id == layerId }, layerId)

  override fun equals(other: Any?): Boolean =
    other is LayerSelector &&
      layerId == other.layerId &&
      (layerId != null || predicate == other.predicate)

  override fun hashCode(): Int = layerId?.hashCode() ?: predicate.hashCode()

  override fun toString(): String = if (layerId != null) "layerId=$layerId" else "predicate"
}
