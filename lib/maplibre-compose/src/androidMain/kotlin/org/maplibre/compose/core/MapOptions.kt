package org.maplibre.compose.core

public actual data class MapOptions(
  /**
   * Enable [android.view.TextureView] as rendered surface instead of
   * [android.opengl.GLSurfaceView]. This improves compatibility with certain Compose
   * transformations (clipping, alpha, etc.) but comes with a performance penalty.
   *
   * See [org.maplibre.android.maps.MapLibreMapOptions.textureMode]
   */
  val textureMode: Boolean = false
)

public actual fun defaultMapOptions(): MapOptions = MapOptions()
