package org.maplibre.compose.mlnffi

/** Raised when a map's GPU bridge cannot be built or used. */
internal class MlnFfiHostException(message: String, cause: Throwable? = null) :
  MlnFfiRecoverableFrameException(message, cause)
