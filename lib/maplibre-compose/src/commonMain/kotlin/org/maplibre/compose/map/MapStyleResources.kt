package org.maplibre.compose.map

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceHandle
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.Projection
import org.maplibre.compose.style.Sky
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.ImageStretch

/** Provides lookup, iteration, and structural commands for the current loaded sources. */
@Stable
public class StyleSources internal constructor(private val style: MapStyleState) :
  Iterable<SourceHandle> {
  /** Returns the current generation's handle for [id], or null when unavailable or absent. */
  public operator fun get(id: String): SourceHandle? = style.sourceHandle(id)

  /** Adds [source] to the current loaded-style generation and returns its handle. */
  public fun add(source: Source): SourceHandle = style.requireOwner().addStyleSource(source)

  /** Removes [id] from the current loaded-style generation and reports whether it existed. */
  public fun remove(id: String): Boolean = style.requireOwner().removeStyleSource(id)

  /** Iterates over the current loaded sources in engine style order. */
  override fun iterator(): Iterator<SourceHandle> = style.sourceHandles().values.iterator()
}

/** Provides lookup and iteration for the current loaded layers. */
@Stable
public class StyleLayers internal constructor(private val style: MapStyleState) :
  Iterable<LayerHandle> {
  /** Returns the current generation's handle for [id], or null when unavailable or absent. */
  public operator fun get(id: String): LayerHandle? = style.layerHandle(id)

  /** Iterates over the current loaded layers from bottom to top. */
  override fun iterator(): Iterator<LayerHandle> = style.layerHandles().values.iterator()
}

/** Provides structural commands for style images in the current loaded-style generation. */
@Stable
public class StyleImages internal constructor(private val style: MapStyleState) {
  /** Adds a style image. The command fails when [id] already exists. */
  public fun add(
    id: String,
    image: ImageBitmap,
    sdf: Boolean = false,
    stretch: ImageStretch? = null,
  ) {
    style.requireOwner().addStyleImage(id, image, sdf, stretch)
  }

  /** Removes [id] and reports whether it existed. */
  public fun remove(id: String): Boolean = style.requireOwner().removeStyleImage(id)
}

/**
 * Provides the global transition of the current loaded-style generation.
 *
 * A base-style reload replaces the transition with the one that the new style declares. A still
 * snapshot ignores the duration and delay.
 *
 * On Android, the system animator duration scale multiplies the transition that [set] writes. A
 * transition that the style JSON declares keeps its timing.
 */
@Stable
public class StyleTransition internal constructor(private val style: MapStyleState) {
  /**
   * Returns the loaded style's transition, or null while no style is ready.
   *
   * Reports the timing that [set] last declared for this loaded style, else the timing the style
   * holds. The engine holds a set transition under the animator duration scale.
   */
  public fun get(): TransitionOptions? = style.transitionOptions()

  /** Replaces the loaded style's transition. The command fails while no style is ready. */
  public fun set(options: TransitionOptions) {
    style.setTransitionOptions(options)
  }

  /**
   * Returns whether symbol placement changes cross-fade, or null while no style is ready.
   *
   * The cross-fade is engine behavior outside the style spec. MapLibre GL JS always reports true.
   */
  public fun placementTransitions(): Boolean? = style.placementTransitions()

  /**
   * Sets whether symbol placement changes cross-fade. A cleared cross-fade applies placement
   * changes to the next rendered frame, which suits features that move at pointer frequency.
   *
   * MapLibre GL JS logs a warning and keeps the cross-fade. The command fails while no style is
   * ready.
   */
  public fun setPlacementTransitions(enabled: Boolean) {
    style.setPlacementTransitions(enabled)
  }
}

/**
 * Provides the light of the current loaded-style generation.
 *
 * A base-style reload replaces the light with the one that the new style declares.
 */
@Stable
public class StyleLight internal constructor(private val style: MapStyleState) {
  /**
   * Returns the value of the style spec's light property [name], such as `anchor` or `color`, or
   * null when the style sets no value or no style is ready.
   */
  public fun getProperty(name: String): JsonElement? = style.lightProperty(name)

  /** Replaces the loaded style's light. The command fails while no style is ready. */
  public fun set(light: Light) {
    style.setLight(light)
  }
}

/**
 * Provides the sky of the current loaded-style generation.
 *
 * A base-style reload replaces the sky with the one that the new style declares. MapLibre Native
 * does not support the sky: every property reads null, and a write logs a warning.
 */
@Stable
public class StyleSky internal constructor(private val style: MapStyleState) {
  /**
   * Returns the value of the style spec's sky property [name], such as `sky-color`, or null when
   * the style sets no value or no style is ready.
   */
  public fun getProperty(name: String): JsonElement? = style.skyProperty(name)

  /**
   * Replaces the loaded style's sky, or removes it when [sky] is null. The command fails while no
   * style is ready.
   */
  public fun set(sky: Sky?) {
    style.setSky(sky)
  }
}

/**
 * Provides the projection of the current loaded-style generation.
 *
 * A base-style reload replaces the projection with the one that the new style declares. MapLibre
 * Native supports only the Mercator projection: every property reads null, and a write logs a
 * warning.
 */
@Stable
public class StyleProjection internal constructor(private val style: MapStyleState) {
  /**
   * Returns the value of the style spec's projection property [name], which is `type`, or null when
   * the style sets no value or no style is ready.
   */
  public fun getProperty(name: String): JsonElement? = style.projectionProperty(name)

  /** Replaces the loaded style's projection. The command fails while no style is ready. */
  public fun set(projection: Projection) {
    style.setProjection(projection)
  }
}
