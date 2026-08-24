package org.maplibre.compose.snippetdemos

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Inline demos that the documentation site embeds next to code snippets. The browser entry point
 * composes [SnippetDemoHost] instead of the demo app when the page URL carries a `snippet` query
 * parameter, e.g. `/demo/?snippet=camera`.
 *
 * Each demo stays close to the size of the docsnippet region it demonstrates, and draws its
 * controls as map overlay children rather than in a panel, because the embedded surface is small.
 */
val snippetDemos: Map<String, @Composable () -> Unit> =
  mapOf("camera" to { CameraSnippetDemo() }, "expressions" to { ExpressionsSnippetDemo() })

@Composable
fun SnippetDemoHost(id: String) {
  MaterialTheme {
    val demo = snippetDemos[id]
    if (demo != null) demo() else Text("Unknown snippet demo: $id")
  }
}
