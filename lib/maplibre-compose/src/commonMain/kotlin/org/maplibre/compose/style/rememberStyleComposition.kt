package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.channels.Channel
import org.maplibre.compose.util.MaplibreComposable

/** Evaluates committed declarations and serially submits the latest complete snapshot. */
@Composable
internal fun rememberStyleComposition(
  content: @Composable @MaplibreComposable () -> Unit,
  maybeStyle: StyleBinding?,
  replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
  compositionLocals: CompositionLocalContext = currentCompositionLocalContext,
  applyRevision: suspend (StyleBinding, DesiredStyleRevision) -> Unit = { _, _ -> },
): State<DesiredStyleRevision?> {
  // This is observed by click dispatch, not by the reconciliation scheduler.
  val revisionState = remember(content, maybeStyle) { mutableStateOf<DesiredStyleRevision?>(null) }
  val compositionContext = rememberCompositionContext()
  val currentLocals by rememberUpdatedState(compositionLocals)
  val apply by rememberUpdatedState(applyRevision)

  LaunchedEffect(content, maybeStyle) {
    val style = maybeStyle ?: return@LaunchedEffect
    if (!style.isLoaded) return@LaunchedEffect
    val revisions = Channel<DesiredStyleRevision>(Channel.CONFLATED)
    val rootNode =
      try {
        StyleNode(style, replaceableSourceIds, replaceableLayerIds) {
          revisionState.value = it
          revisions.trySend(it).getOrThrow()
        }
      } catch (error: IllegalStateException) {
        if (!style.isLoaded) return@LaunchedEffect
        throw error
      }
    val evaluator = Composition(MapNodeApplier(rootNode), compositionContext)
    try {
      evaluator.setContent {
        CompositionLocalProvider(currentLocals) { StyleContent(rootNode, content) }
      }
      // A newer commit replaces pending work; it does not cancel an already submitted mutation.
      for (revision in revisions) {
        if (!style.isLoaded) break
        apply(style, revision)
      }
    } finally {
      rootNode.close()
      revisions.cancel()
      evaluator.dispose()
    }
  }
  return revisionState
}

@Composable
internal fun StyleContent(
  rootNode: StyleNode,
  content: @Composable @MaplibreComposable () -> Unit,
) {
  val fontScale = LocalDensity.current.fontScale
  val animatorDurationScale = rootNode.style.animatorDurationScale
  ComposeNode<StyleEnvironmentNode, MapNodeApplier>(
    factory = ::StyleEnvironmentNode,
    update = {
      set(fontScale) { this.fontScale = it }
      set(animatorDurationScale) { this.animatorDurationScale = it }
    },
  )
  CompositionLocalProvider(
    LocalStyleNode provides rootNode,
    LocalStyleFontScale provides fontScale,
  ) {
    content()
  }
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }
