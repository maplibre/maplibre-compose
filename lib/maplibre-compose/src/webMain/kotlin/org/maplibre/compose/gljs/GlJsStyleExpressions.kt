@file:JsModule("@maplibre/maplibre-gl-style-spec")

package org.maplibre.compose.gljs

/** The pinned style-spec evaluator, shared with GL JS rather than a second expression language. */
internal external fun createPropertyExpression(
  value: dynamic,
  rootKey: String,
  specification: dynamic,
): dynamic
