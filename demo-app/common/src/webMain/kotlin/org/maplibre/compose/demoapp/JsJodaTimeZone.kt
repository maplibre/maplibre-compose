package org.maplibre.compose.demoapp

import kotlin.js.JsAny
import kotlin.js.JsModule

/**
 * Side-effect import that registers IANA zone rules with js-joda.
 *
 * kotlinx-datetime TimeZone.of() in a browser reads this database. Production DCE drops a
 * library-only import, so [main] holds [jsJodaTz] and the published demo can resolve
 * America/Los_Angeles.
 */
@JsModule("@js-joda/timezone") external object JsJodaTimeZoneModule : JsAny

val jsJodaTz: JsAny = JsJodaTimeZoneModule
