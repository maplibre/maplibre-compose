package org.maplibre.compose.style

/** Opaque identity for one loaded base-style generation. */
internal class StyleIdentity private constructor() {
  companion object {
    fun create(): StyleIdentity = StyleIdentity()
  }
}

/** Monotonic identity for one accepted base-style request. */
internal data class StyleRequestId(val value: Long)

internal sealed interface TrackedStyleLoadState {
  val desiredStyle: BaseStyle
  val appliedStyle: BaseStyle?

  data class Pending(
    override val desiredStyle: BaseStyle,
    override val appliedStyle: BaseStyle?,
  ) : TrackedStyleLoadState

  data class Loading(
    override val desiredStyle: BaseStyle,
    override val appliedStyle: BaseStyle?,
  ) : TrackedStyleLoadState

  data class Ready(
    override val desiredStyle: BaseStyle,
    override val appliedStyle: BaseStyle,
    val identity: StyleIdentity,
  ) : TrackedStyleLoadState

  data class Failed(
    override val desiredStyle: BaseStyle,
    override val appliedStyle: BaseStyle?,
    val stage: Stage,
    val reason: String,
  ) : TrackedStyleLoadState {
    enum class Stage {
      BASE_STYLE,
      RECONCILIATION,
    }
  }
}

/** Claims asynchronous style results for the latest accepted request only. */
internal class StyleLoadTracker(initialStyle: BaseStyle, engineAvailable: Boolean) {
  private var nextRequestId = 1L
  private var currentRequest = StyleRequestId(nextRequestId)
  private var loadedIdentity: StyleIdentity? = null

  var state: TrackedStyleLoadState =
    if (engineAvailable) TrackedStyleLoadState.Loading(initialStyle, appliedStyle = null)
    else TrackedStyleLoadState.Pending(initialStyle, appliedStyle = null)
    private set

  val requestId: StyleRequestId
    get() = currentRequest

  fun request(style: BaseStyle, engineAvailable: Boolean): StyleRequestId {
    if (style == state.desiredStyle) return currentRequest
    currentRequest = StyleRequestId(++nextRequestId)
    loadedIdentity = null
    state =
      if (engineAvailable) TrackedStyleLoadState.Loading(style, state.appliedStyle)
      else TrackedStyleLoadState.Pending(style, state.appliedStyle)
    return currentRequest
  }

  fun engineBecameAvailable(): StyleRequestId {
    currentRequest = StyleRequestId(++nextRequestId)
    loadedIdentity = null
    state = TrackedStyleLoadState.Loading(state.desiredStyle, state.appliedStyle)
    return currentRequest
  }

  fun beginLoading(): StyleRequestId =
    if (state is TrackedStyleLoadState.Pending) engineBecameAvailable() else currentRequest

  fun engineBecameUnavailable() {
    loadedIdentity = null
    state = TrackedStyleLoadState.Pending(state.desiredStyle, appliedStyle = null)
  }

  fun loaded(request: StyleRequestId, identity: StyleIdentity): Boolean {
    if (request != currentRequest) return false
    loadedIdentity = identity
    state = TrackedStyleLoadState.Loading(state.desiredStyle, state.appliedStyle)
    return true
  }

  fun reconciled(request: StyleRequestId, identity: StyleIdentity): Boolean {
    if (
      request != currentRequest ||
        state !is TrackedStyleLoadState.Loading ||
        identity !== loadedIdentity
    ) {
      return false
    }
    state = TrackedStyleLoadState.Ready(state.desiredStyle, state.desiredStyle, identity)
    return true
  }

  fun failed(
    request: StyleRequestId,
    stage: TrackedStyleLoadState.Failed.Stage,
    reason: String,
  ): Boolean {
    if (request != currentRequest || state !is TrackedStyleLoadState.Loading) return false
    state = TrackedStyleLoadState.Failed(state.desiredStyle, state.appliedStyle, stage, reason)
    return true
  }
}
