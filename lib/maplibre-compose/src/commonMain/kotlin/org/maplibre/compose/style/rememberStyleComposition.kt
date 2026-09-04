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
import kotlinx.coroutines.awaitCancellation
import org.maplibre.compose.util.MaplibreComposable

/**
 * Hosts the map's content in a subcomposition tied to the style it draws into.
 *
 * The subcomposition follows the loaded style. The engine invalidates the binding before a style
 * switch. Effects from the outgoing composition cannot mutate the replacement style.
 */
@Composable
internal fun rememberStyleComposition(
  content: @Composable @MaplibreComposable () -> Unit,
  maybeStyle: StyleBinding?,
  replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
): State<DesiredStyleRevision?> {
  val revisionState = remember(content, maybeStyle) { mutableStateOf<DesiredStyleRevision?>(null) }
  val compositionContext = rememberCompositionContext()

  LaunchedEffect(content, maybeStyle) {
    val style = maybeStyle ?: return@LaunchedEffect
    if (!style.isLoaded) return@LaunchedEffect
    val rootNode =
      try {
        StyleNode(style, replaceableSourceIds, replaceableLayerIds)
      } catch (error: IllegalStateException) {
        if (!style.isLoaded) return@LaunchedEffect
        throw error
      }
    val evaluator = Composition(MapNodeApplier(rootNode), compositionContext)

    evaluator.setContent {
      StyleContent(rootNode = rootNode, publish = { revisionState.value = it }, content = content)
    }

    try {
      awaitCancellation()
    } finally {
      evaluator.dispose()
    }
  }

  return revisionState
}

@Composable
internal fun StyleContent(
  rootNode: StyleNode,
  publish: (DesiredStyleRevision) -> Unit = {},
  content: @Composable @MaplibreComposable () -> Unit,
) {
  CompositionLocalProvider(LocalStyleNode provides rootNode) { content() }
  key(rootNode.currentApplyGeneration) {
    // Side effects run after remember observers, so the evaluator publishes a complete revision.
    SideEffect { publish(rootNode.snapshotRevision()) }
  }
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }
