package org.maplibre.compose.map

/** Marks direct platform-map access with a callback-scoped lifetime. */
@RequiresOptIn(
  message =
    "The platform map is borrowed only for the withPlatformMap callback. " +
      "Do not retain it or run long work in the callback.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class DelicateMapApi

/** Provides callback-scoped access to the current platform engine map. */
@DelicateMapApi public expect class PlatformMapScope

/**
 * Runs [block] on this logical map's engine owner context.
 *
 * The platform map is borrowed for [block]. Use the raw handle only during [block]. Kotlin cannot
 * prevent retention. A long-running block stops the map from processing other work.
 *
 * Native platforms create the engine map when necessary and permit access without a presentation.
 * Web requires a current presentation. The call fails without running [block] if the map closes or
 * the engine changes before execution. On Web, the call also fails if the current presentation
 * detaches before execution.
 */
@DelicateMapApi
public expect suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T
