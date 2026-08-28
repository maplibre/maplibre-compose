package org.maplibre.compose.map

/**
 * Marks API that exposes the MapLibre engine object underneath this library.
 *
 * The composition, the camera records, and the style collections assume that they are the only
 * writers of the map. Code behind this annotation can contradict them, and it binds to engine types
 * that change between engine releases.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message =
    "The platform map bypasses this library's contracts: reads and writes on it can conflict " +
      "with the composition, and the engine types can change between engine releases.",
)
@Retention(AnnotationRetention.BINARY)
public annotation class DelicateMapApi

/**
 * The map object of the MapLibre engine on this platform: the maplibre-native-ffi `MapHandle` on
 * Android, iOS, and Desktop, and the MapLibre GL JS `Map` on Web.
 *
 * [withPlatformMap] is the only route to an instance.
 */
public expect class PlatformMap

/**
 * Runs [block] with the live [PlatformMap] and returns its result, for engine capabilities that
 * this library's API does not cover.
 *
 * On Android, iOS, and Desktop, [block] runs on the map's owner thread, where every call on the
 * handle is legal; map work queues behind the block, so keep it brief. The loaded map survives
 * detach on these platforms, so the call works while no [MaplibreMap] is composed. The call fails
 * with [IllegalStateException] on a closed state, and on a state that never created a map — the map
 * is created at the first [MaplibreMap] attach or [MapState.captureStillImage].
 *
 * On Web, [block] runs on the calling thread. The live map exists only while a [MaplibreMap] is
 * composed. A call on an attached session waits until that session constructs its map, and a call
 * on a detached or closed state fails with [IllegalStateException].
 *
 * Use the map only inside [block]: a reference kept past the call can outlive the map it points at.
 */
@DelicateMapApi public expect suspend fun <T> MapState.withPlatformMap(block: (PlatformMap) -> T): T
