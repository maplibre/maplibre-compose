package dev.sargunv.maplibrecompose.core

public actual data class RenderOptions(val debugSettings: DebugSettings = DebugSettings()) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        debugSettings =
          DebugSettings(showCollisionBoxes = true, showTileBoundaries = true, showPadding = true)
      )
  }

  public data class DebugSettings(
    val showCollisionBoxes: Boolean = false,
    val showTileBoundaries: Boolean = false,
    val showPadding: Boolean = false,
    val showOverdrawInspector: Boolean = false,
  )
}
