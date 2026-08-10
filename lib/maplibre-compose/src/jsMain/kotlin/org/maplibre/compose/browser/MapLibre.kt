package org.maplibre.compose.browser

import org.maplibre.compose.gljs.SkikoGpuBridge

/** Process-wide entry point for configuring MapLibre Compose in the browser. */
public object MapLibre {
  /**
   * Prepares the page to composite maps, and must be called before Compose starts.
   *
   * The map is drawn inside the Compose scene, which means creating GPU images on the graphics
   * context Compose renders with. Nothing in Compose or skiko hands that context out, so it has to
   * be caught as it is created.
   *
   * ```kt
   * fun main() {
   *   onWasmReady {
   *     MapLibre.initialize()
   *     ComposeViewport(document.body!!) { App() }
   *   }
   * }
   * ```
   *
   * Repeat calls are ignored. A map composed without this raises the reason it cannot composite
   * rather than drawing nothing.
   *
   * @throws IllegalStateException if skiko is not loaded yet. Call this inside `onWasmReady`.
   */
  public fun initialize() {
    check(SkikoGpuBridge.install()) {
      "MapLibre.initialize() ran before skiko finished loading, so Compose's graphics context " +
        "cannot be reached. Call it inside onWasmReady, immediately before ComposeViewport."
    }
  }
}
