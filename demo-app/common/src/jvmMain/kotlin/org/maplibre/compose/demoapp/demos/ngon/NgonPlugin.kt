package org.maplibre.compose.demoapp.demos.ngon

/**
 * MapLibre Native's sample n-gon layer plugin, built by `mise run deps:ngon-plugin`.
 *
 * MapLibre Native FFI built on mln-rs has no plugin API to register the `ngon` layer type with, so
 * the earthquake demo draws its hexbins with built-in layers.
 */
internal object NgonPlugin {
  /** Whether the `ngon` layer type is registered. */
  const val isRegistered: Boolean = false
}
