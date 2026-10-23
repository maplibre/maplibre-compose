package org.maplibre.compose.map

import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/** Provides callback-scoped access to the current platform engine map. */
@DelicateMaplibreComposeApi
// Exposes MapLibre engine bindings, which may change in minor releases.
@ExperimentalMaplibreComposeApi
public expect class PlatformMapScope

/**
 * Runs [block] with this logical map's engine map.
 *
 * The platform map is borrowed for [block]. Use the raw handle only during [block]. Kotlin cannot
 * prevent retention. The callback runs synchronously.
 *
 * Native platforms suspend until a map is presented, then run [block] on the runtime's main
 * dispatcher. A native map runs each command or query in call order and returns a `Deferred` that
 * completes once it has run, so [block] submits work without waiting for it. Web requires an
 * attached surface. Cancelling before [block] starts prevents it from running.
 *
 * The map is a maplibre-native-ffi `MapHandle` on Native platforms and a MapLibre GL JS `Map` on
 * web. Those engine types may change in any minor release, so this function also requires opt-in to
 * [ExperimentalMaplibreComposeApi].
 *
 * Sources and layers that [block] adds, removes, or moves are not reflected in [MapState.style]'s
 * sources and layers, and style content may replace them. Use the style API to change content that
 * the library tracks.
 *
 * @throws IllegalStateException if the map is already closed. Web also throws this exception if no
 *   surface is attached when the call starts.
 * @throws kotlinx.coroutines.CancellationException if the map, engine, or Web attachment changes
 *   before [block] starts.
 */
@DelicateMaplibreComposeApi
// Exposes MapLibre engine bindings, which may change in minor releases.
@ExperimentalMaplibreComposeApi
public expect suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T
