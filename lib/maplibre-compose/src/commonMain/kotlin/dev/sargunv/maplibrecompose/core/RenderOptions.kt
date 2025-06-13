package dev.sargunv.maplibrecompose.core

/**
 * Configures platform-specific map rendering options.
 *
 * The companion object provides some presets available from common code, but fine-grained
 * customization on multiple platforms requires configuring these options in expect/actual code.
 */
public expect class RenderOptions {
  public companion object Companion {
    /** The recommended configuration for most use cases. */
    public val Standard: RenderOptions

    /** Standard but with some debug overlays enabled */
    public val Debug: RenderOptions
  }
}
