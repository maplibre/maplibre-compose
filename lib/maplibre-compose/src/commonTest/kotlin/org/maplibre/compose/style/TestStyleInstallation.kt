package org.maplibre.compose.style

import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.Source

internal suspend fun StyleBinding.install(source: Source): SourceInstallation = run {
  SourceInstallation(this, source.definition())
}

internal suspend fun StyleBinding.install(definition: SourceDefinition): SourceInstallation = run {
  SourceInstallation(this, definition)
}

internal suspend fun StyleBinding.install(
  layer: TestLayer,
  beforeLayerId: String = "",
): LayerInstallation = run { LayerInstallation(this, layer.definition(), beforeLayerId) }

internal suspend fun StyleBinding.install(
  definition: LayerDefinition,
  beforeLayerId: String = "",
): LayerInstallation = run { LayerInstallation(this, definition, beforeLayerId) }

internal suspend fun StyleBinding.uninstall(source: Source) {
  run { removeSource(source.id) }
}

internal suspend fun StyleBinding.uninstall(layer: TestLayer) {
  run { removeLayer(layer.id) }
}
