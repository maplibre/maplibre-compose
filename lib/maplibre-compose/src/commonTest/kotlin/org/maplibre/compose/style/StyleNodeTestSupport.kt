package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Records a layer into the desired state the way the applier does. */
internal fun StyleNode.insertLayer(node: LayerNode<*>, index: Int) {
  children.add(index, node)
  onChildInserted(index, node)
}

/** Removes a layer from the desired state the way the applier does. */
internal fun StyleNode.removeLayerAt(index: Int) {
  children.removeAt(index)
}

/**
 * Collects every [StyleError] that [host] emits from now on into the returned list.
 *
 * The collector subscribes before this function returns, because the host's flow replays nothing.
 * The collection runs in the test's background scope, so the test never cancels it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.collectStyleErrors(host: StyleCompositionHost): List<StyleError> {
  val errors = mutableListOf<StyleError>()
  // Unconfined and undispatched: the collector subscribes on this call and takes each error at the
  // emit, which `advanceUntilIdle` would otherwise leave pending as background work.
  backgroundScope.launch(
    context = UnconfinedTestDispatcher(testScheduler),
    start = CoroutineStart.UNDISPATCHED,
  ) {
    host.styleErrors.collect { errors += it }
  }
  return errors
}
