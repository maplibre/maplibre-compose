package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
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
  compositionLocals: CompositionLocalContext = currentCompositionLocalContext,
): State<DesiredStyleRevision?> {
  val revisionState = remember(content, maybeStyle) { mutableStateOf<DesiredStyleRevision?>(null) }
  val compositionContext = rememberCompositionContext()
  val currentLocals by rememberUpdatedState(compositionLocals)

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

    try {
      evaluator.setContent {
        // A child composition reads its parent's locals only when it composes from the root. The
        // parent publishes each new context through this state, so a density or configuration
        // change recomposes the content without restarting its effects.
        CompositionLocalProvider(currentLocals) {
          StyleContent(
            rootNode = rootNode,
            publish = { revisionState.value = it },
            content = content,
          )
        }
      }
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
  // Read in composition, not in the side effect, so a scale change republishes the revision.
  val animatorDurationScale = rootNode.style.animatorDurationScale
  key(rootNode.currentApplyGeneration) {
    // Side effects run after remember observers, so the evaluator publishes a complete revision.
    SideEffect { publish(rootNode.snapshotRevision(animatorDurationScale)) }
  }
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }
