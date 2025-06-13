package dev.sargunv.maplibrecompose.core

public actual data class RenderOptions(
  public val maximumFps: Int? = null,
  public val debugSettings: DebugSettings = DebugSettings(),
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        debugSettings =
          DebugSettings(
            isTileBoundariesEnabled = true,
            isTileInfoEnabled = true,
            isTimestampsEnabled = true,
            isCollisionBoxesEnabled = true,
          )
      )
  }

  public data class DebugSettings(
    val isTileBoundariesEnabled: Boolean = false,
    val isTileInfoEnabled: Boolean = false,
    val isTimestampsEnabled: Boolean = false,
    val isCollisionBoxesEnabled: Boolean = false,
    val isOverdrawVisualizationEnabled: Boolean = false,
  )
}
