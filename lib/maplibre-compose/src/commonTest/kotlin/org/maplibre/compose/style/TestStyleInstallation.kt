package org.maplibre.compose.style

import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.Source

/**
 * Runs [block] where this binding's synchronous operations may run, as production code does: on
 * MapLibre Native that is the map's owner thread.
 */
internal suspend fun <T> StyleBinding.onOwner(block: () -> T): T {
  var result: Result<T>? = null
  awaitOwner { result = runCatching(block) }
  return checkNotNull(result) { "The style unloaded before the test's owner call ran" }.getOrThrow()
}

internal suspend fun StyleBinding.install(source: Source): SourceInstallation = onOwner {
  SourceInstallation(this, source.definition())
}

internal suspend fun StyleBinding.install(definition: SourceDefinition): SourceInstallation =
  onOwner {
    SourceInstallation(this, definition)
  }

internal suspend fun StyleBinding.install(
  layer: TestLayer,
  beforeLayerId: String = "",
): LayerInstallation = onOwner { LayerInstallation(this, layer.definition(), beforeLayerId) }

internal suspend fun StyleBinding.install(
  definition: LayerDefinition,
  beforeLayerId: String = "",
): LayerInstallation = onOwner { LayerInstallation(this, definition, beforeLayerId) }

internal suspend fun StyleBinding.uninstall(source: Source) {
  onOwner { removeSource(source.id) }
}

internal suspend fun StyleBinding.uninstall(layer: TestLayer) {
  onOwner { removeLayer(layer.id) }
}
