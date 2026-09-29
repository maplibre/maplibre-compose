package org.maplibre.compose.mlnffi

/** Creates the [MlnFfiMapHost] that backs a map. */
internal interface MlnFfiMapHostFactory {
  /** A short description of this factory, used in diagnostics. */
  val description: String

  /**
   * The producer/consumer combinations this factory can bridge on the current machine, in
   * preference order. The bridge the map uses is the first whose producer the packaged FFI runtime
   * provides; the runtime artifact the application packaged is what chooses between them.
   */
  val bridges: List<RenderBackendPair>

  /**
   * Creates a host for [backends], one of [bridges]. Prefer returning [MlnFfiMapHostResult.Failed]
   * over throwing, so the failure reaches the user as a diagnostic.
   */
  fun create(backends: RenderBackendPair): MlnFfiMapHostResult
}

/** The outcome of [MlnFfiMapHostFactory.create]. */
internal sealed interface MlnFfiMapHostResult {
  /** A usable host. */
  data class Created(val host: MlnFfiMapHost) : MlnFfiMapHostResult

  /** This factory should have been able to create a host, but failed. */
  data class Failed(val diagnostic: String, val cause: Throwable? = null) : MlnFfiMapHostResult
}
