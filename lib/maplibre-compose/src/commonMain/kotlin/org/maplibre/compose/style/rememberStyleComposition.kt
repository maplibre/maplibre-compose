package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import kotlinx.coroutines.awaitCancellation
import org.maplibre.compose.util.MaplibreComposable

/**
 * Hosts the map's content in a [StyleCompositionHost] owned here rather than in the UI composition.
 *
 * The host outlives any one style: a binding swap rebuilds the content composition inside it, and
 * only leaving the composition closes it. The content follows the style the application has
 * *selected* while the composition targets the *loaded* style; during a switch those differ and
 * nothing here reconciles them. The binding dropping writes after unload is what makes that
 * survivable.
 */
@Composable
internal fun rememberStyleComposition(
  styleState: StyleState,
  maybeBinding: StyleBinding?,
  logger: Logger?,
  content: @Composable @MaplibreComposable () -> Unit,
): State<StyleNode?> {
  val nodeState = remember { mutableStateOf<StyleNode?>(null) }
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val locals = currentCompositionLocalContext

  val host = remember {
    val hostDispatcher = styleHostDispatcher()
    StyleCompositionHost(
        dispatcher = hostDispatcher.dispatcher,
        density = density,
        layoutDirection = layoutDirection,
        logger = logger,
        onClosed = hostDispatcher::close,
      )
      .also { it.inheritedLocals = locals }
  }
  DisposableEffect(host) { onDispose { host.close() } }

  LaunchedEffect(styleState, maybeBinding, host) {
    val binding = maybeBinding
    if (binding == null) {
      host.clearContent()
      return@LaunchedEffect
    }
    val rootNode = StyleNode(binding, logger).also { nodeState.value = it }
    styleState.attach(rootNode)
    host.setContent(rootNode, content)

    try {
      awaitCancellation()
    } finally {
      nodeState.value = null
    }
  }

  SideEffect {
    host.density = density
    host.layoutDirection = layoutDirection
    host.inheritedLocals = locals
    nodeState.value?.logger = logger
  }

  return nodeState
}
