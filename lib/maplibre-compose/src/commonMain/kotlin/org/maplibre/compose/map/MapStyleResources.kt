package org.maplibre.compose.map

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceHandle
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
