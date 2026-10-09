package org.maplibre.compose.util

/**
 * Marks an API whose contract or behavior is experimental. It may change in any minor release,
 * expose an unstable dependency such as Skiko or the MapLibre engine bindings, or use experimental
 * engine features.
 */
@RequiresOptIn(
  message =
    "This API is experimental. Its contract may change, its dependencies may be unstable, " +
      "or its engine behavior may be experimental.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_SETTER,
)
public annotation class ExperimentalMaplibreComposeApi
