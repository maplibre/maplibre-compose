package dev.sargunv.maplibrecompose.compose.engine

internal class IncrementingId(private val name: String) {
  private var nextId = 0

  fun next(): String = "__MAPLIBRE_COMPOSE_${name}_${nextId++}"
}
