package org.maplibre.compose.demoapp

import androidx.compose.ui.window.singleWindowApplication

// Not working with heavyweight AWT components
// Try this later when we have off screen rendering
// System.setProperty("compose.interop.blending", "true")
// System.setProperty("compose.swing.render.on.graphics", "true")

// -8<- [start:main]
fun main() {
  singleWindowApplication { DemoApp() }
}

// -8<- [end:main]
