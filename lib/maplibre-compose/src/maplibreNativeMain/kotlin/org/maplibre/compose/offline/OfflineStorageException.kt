package org.maplibre.compose.offline

/** Reports a failed operation on an [OfflineStorage]. */
public class OfflineStorageException
internal constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
