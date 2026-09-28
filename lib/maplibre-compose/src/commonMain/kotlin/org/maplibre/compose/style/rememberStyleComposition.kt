package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.maplibre.compose.util.MaplibreComposable

/** Owns the live style composition and submits its committed snapshots to the loaded map. */
@Composable
internal fun rememberStyleComposition(
  content: @Composable @MaplibreComposable () -> Unit,
  maybeStyle: StyleBinding?,
  replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
  compositionLocals: CompositionLocalContext = currentCompositionLocalContext,
  applyRevision: suspend (StyleBinding, StyleSnapshot) -> Unit = { _, _ -> },
): State<StyleSnapshot?> {
  // This is observed by click dispatch, not by the reconciliation scheduler.
  val revisionState = remember(content, maybeStyle) { mutableStateOf<StyleSnapshot?>(null) }
  val compositionContext = rememberCompositionContext()
  val scope = rememberCoroutineScope()
  val currentLocals by rememberUpdatedState(compositionLocals)
  val apply by rememberUpdatedState(applyRevision)

  DisposableEffect(content, maybeStyle) {
    val style = maybeStyle ?: return@DisposableEffect onDispose {}
    if (!style.isLoaded) return@DisposableEffect onDispose {}
    val revisions = Channel<StyleSnapshot>(Channel.CONFLATED)
    val rootNode =
      StyleNode(
        style,
        scope,
        replaceableSourceIds,
        replaceableLayerIds,
        publish = { revisions.trySend(it).getOrThrow() },
      )
    val composition = Composition(MapNodeApplier(rootNode), compositionContext)
    fun dispose() {
      // Stop declarations first so disposal cannot clear the retained map's content.
      rootNode.close()
      revisions.cancel()
      composition.dispose()
    }
    try {
      composition.setContent {
        CompositionLocalProvider(currentLocals) { StyleContent(rootNode, content) }
      }
    } catch (error: Throwable) {
      dispose()
      throw error
    }
    val installer = scope.launch {
      try {
        for (revision in revisions) {
          if (style.isLoaded) {
            revisionState.value = revision
            apply(style, revision)
          }
        }
      } finally {
        dispose()
      }
    }
    onDispose {
      installer.cancel()
      // Accepted commits can outlive cancellation; live composition effects cannot.
      dispose()
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
