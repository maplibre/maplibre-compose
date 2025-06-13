package dev.sargunv.maplibrecompose.core

public actual data class RenderOptions(
  /**
   * Select between [android.opengl.GLSurfaceView] and [android.view.TextureView] as rendered
   * surface. `TextureView` improves compatibility with certain Compose transformations (clipping,
   * alpha, etc.) but comes with a performance penalty when compared to `SurfaceView`. If you don't
   * need such transformations, use `SurfaceView`.
   *
   * See [org.maplibre.android.maps.MapLibreMapOptions.textureMode]
   */
  val renderMode: RenderMode = RenderMode.SurfaceView,
  val isDebugEnabled: Boolean = false,
  val maximumFps: Int? = null,
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions = RenderOptions(isDebugEnabled = true)
  }

  public enum class RenderMode {
    SurfaceView,
    TextureView,
  }
}
