package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MapRenderBackend

internal actual fun packagedRenderBackends(): Set<MapRenderBackend> =
  MapRenderBackend::class
    .java
    .classLoader
    .getResources(PackagedRenderBackendResource)
    .toList()
    .flatMapTo(mutableSetOf()) { url ->
      url.readText().lineSequence().mapNotNull { name ->
        MapRenderBackend.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
      }
    }
