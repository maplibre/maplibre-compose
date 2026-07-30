package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
 * This is the seam where the map's two clocks meet, and where a rewrite would start. [maybeStyle]
 * is the style that has *loaded*, so the subcomposition's lifetime follows an asynchronous native
 * event — while [content] is an ordinary composable reading ordinary application state, so its
 * anchors, sources, and layers follow the style the application has *selected*. Those are not the
 * same style during a switch, and nothing here reconciles them: the content simply recomposes into
 * whichever node is current, correct or not.
 *
 * What makes that survivable is `SafeStyle.unload`, and the platform-side timing it depends on —
 * both described there. What would make it unnecessary is binding the content to the requested
 * style rather than the loaded one, so that composing against a style the application has left
 * cannot be expressed. That is a public-API change, not a local one: it decides what a caller's
 * content means while a style is in flight.
 */
@Composable
internal fun rememberStyleComposition(
  styleState: StyleState,
  maybeStyle: SafeStyle?,
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

    composition.setContent {
      CompositionLocalProvider(LocalStyleNode provides rootNode) { content() }
    }

    try {
      awaitCancellation()
    } finally {
      nodeState.value = null
      composition.dispose()
    }
  }

  return nodeState
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }
