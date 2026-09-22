package org.maplibre.compose.style

import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.Source

internal fun StyleBinding.install(source: Source): SourceInstallation =
  SourceInstallation(this, source.definition())

internal fun StyleBinding.install(definition: SourceDefinition): SourceInstallation =
  SourceInstallation(this, definition)

internal fun StyleBinding.install(layer: TestLayer, beforeLayerId: String = ""): LayerInstallation =
  LayerInstallation(this, layer.definition(), beforeLayerId)

internal fun StyleBinding.install(
  definition: ResolvedLayerDefinition,
  beforeLayerId: String = "",
): LayerInstallation = LayerInstallation(this, definition, beforeLayerId)

internal fun StyleBinding.uninstall(source: Source) {
  removeSource(source.id)
}

internal fun StyleBinding.uninstall(layer: TestLayer) {
  removeLayer(layer.id)
}
