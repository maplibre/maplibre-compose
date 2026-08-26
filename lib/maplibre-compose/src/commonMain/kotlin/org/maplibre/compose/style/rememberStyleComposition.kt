package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.util.MaplibreComposable

/**
 * Hosts the map's content in a [StyleCompositionHost] owned here rather than in the UI composition.
 *
 * The host, the root [StyleNode], and the content composition all live as long as the map: a style
 * swap re-points the node at the new binding and asks the host to sync, which reapplies the whole
 * desired state to the new style. The content follows the style the application has *selected*
 * while the node targets the *loaded* style; during a switch those differ and nothing here
 * reconciles them. The binding dropping writes after unload is what makes that survivable.
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
  val currentContent by rememberUpdatedState(content)

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
  val rootNode = remember { StyleNode(StyleBinding.UNLOADED, logger) }
  DisposableEffect(host) { onDispose { host.close() } }

  LaunchedEffect(host, rootNode) { host.setContent(rootNode) { currentContent() } }

  LaunchedEffect(styleState, maybeBinding, host, rootNode) {
    rootNode.binding = maybeBinding ?: StyleBinding.UNLOADED
    styleState.attach(rootNode)
    host.requestApplyChanges()
    nodeState.value = if (maybeBinding != null) rootNode else null
  }

  SideEffect {
    host.density = density
    host.layoutDirection = layoutDirection
    host.inheritedLocals = locals
    rootNode.logger = logger
  }

  return nodeState
}
