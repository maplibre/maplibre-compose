package org.maplibre.compose.desktop.bridge

import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget

/** A producer's view of a shared texture. Access and disposal run on the renderer thread. */
internal interface ImportedMapTexture : AutoCloseable {
  val storageExtent: MapExtent

  fun target(generation: Long): MlnFfiRenderTarget
}
