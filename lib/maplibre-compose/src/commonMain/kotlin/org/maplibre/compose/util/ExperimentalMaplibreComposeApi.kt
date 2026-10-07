package org.maplibre.compose.util

/**
 * Marks an API that may change in any minor release. Marked APIs are new and have seen little use,
 * or expose a dependency that is not expected to become stable before this library does, such as
 * Skiko or the MapLibre engine bindings.
 */
@RequiresOptIn(
  message =
    "This API may change in any minor release. It is new, or it exposes a dependency that is " +
      "not stable.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_SETTER)
public annotation class ExperimentalMaplibreComposeApi
