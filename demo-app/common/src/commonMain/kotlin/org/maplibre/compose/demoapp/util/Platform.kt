package org.maplibre.compose.demoapp.util

import androidx.compose.foundation.layout.PaddingValues
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.map.OrnamentOptions

expect object Platform {
  val name: String

  val version: String

  val extraDemos: List<Demo>

  fun padOrnaments(options: OrnamentOptions, padding: PaddingValues): OrnamentOptions
}
