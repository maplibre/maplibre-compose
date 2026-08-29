package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.maplibre.compose.util.MaplibreComposable

/** A reusable definition of application-owned sources, layers, and images. */
@Immutable
public class StyleComposition(content: @Composable @MaplibreComposable () -> Unit) {
  internal val content: @Composable @MaplibreComposable () -> Unit = content

  public companion object {
    /** A style composition that declares no application-owned resources. */
    public val Empty: StyleComposition = StyleComposition {}
  }
}
