package org.maplibre.compose.offline

/** Reports a failed operation on an [OfflineManager]. */
public class OfflineManagerException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
