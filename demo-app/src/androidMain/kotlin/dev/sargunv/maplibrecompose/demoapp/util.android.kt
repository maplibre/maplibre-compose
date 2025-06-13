package dev.sargunv.maplibrecompose.demoapp

import dev.sargunv.maplibrecompose.core.RenderOptions

actual fun RenderOptions.withMaxFps(maxFps: Int) = copy(maximumFps = maxFps)
