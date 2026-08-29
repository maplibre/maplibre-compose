package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger
import kotlinx.coroutines.awaitCancellation
import org.maplibre.compose.util.MaplibreComposable

/**
 * Hosts the map's content in a subcomposition tied to the style it draws into.
 *
 * The subcomposition follows the loaded style. The engine invalidates the binding before a style
 * switch, so effects from the outgoing composition cannot mutate the replacement style.
 */
@Composable
internal fun rememberStyleComposition(
  styleState: StyleState,
  maybeStyle: StyleBinding?,
  logger: Logger?,
  content: @Composable @MaplibreComposable () -> Unit,
): State<StyleNode?> {
  val nodeState = remember { mutableStateOf<StyleNode?>(null) }
  val compositionContext = rememberCompositionContext()

  LaunchedEffect(styleState, maybeStyle, compositionContext) {
    val style = maybeStyle ?: return@LaunchedEffect
    val rootNode = StyleNode(style, logger).also { nodeState.value = it }
    styleState.attach(rootNode)
    val composition = Composition(MapNodeApplier(rootNode), compositionContext)

    composition.setContent { StyleContent(rootNode, content) }

    try {
      awaitCancellation()
    } finally {
      nodeState.value = null
      composition.dispose()
    }
  }

  SideEffect { nodeState.value?.logger = logger }

  return nodeState
}

@Composable
internal fun StyleContent(
  rootNode: StyleNode,
  content: @Composable @MaplibreComposable () -> Unit,
) {
  CompositionLocalProvider(LocalStyleNode provides rootNode) { content() }
  key(rootNode.currentApplyGeneration) {
    // Side effects run after remember observers, so source effects attach before layers do.
    SideEffect { rootNode.applyChanges() }
  }
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }
