@file:JsModule("@maplibre/maplibre-gl-style-spec")

package org.maplibre.compose.gljs

import kotlin.js.JsAny

/** The pinned style-spec evaluator, shared with GL JS rather than a second expression language. */
internal external fun createPropertyExpression(
  value: JsAny,
  rootKey: String,
  specification: JsAny,
): JsAny
