package org.maplibre.compose.style

/** The prefix of every id the library generates; application-chosen ids must not use it. */
internal const val GENERATED_ID_PREFIX = "__MAPLIBRE_COMPOSE_"

internal class IncrementingId(private val name: String) {
  private var nextId = 0

  fun next(): String = "$GENERATED_ID_PREFIX${name}_${nextId++}"
}
