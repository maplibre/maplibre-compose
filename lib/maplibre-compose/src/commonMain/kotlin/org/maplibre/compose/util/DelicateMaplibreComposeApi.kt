package org.maplibre.compose.util

/**
 * Marks an API with a lifetime or threading contract that the compiler cannot check. Read the
 * documentation of the marked API before opting in.
 */
@RequiresOptIn(
  message =
    "This API has a lifetime or threading contract that the compiler cannot check. " +
      "Read its documentation before opting in.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_SETTER)
public annotation class DelicateMaplibreComposeApi
