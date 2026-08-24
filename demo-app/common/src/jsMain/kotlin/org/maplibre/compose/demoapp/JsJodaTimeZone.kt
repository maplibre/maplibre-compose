package org.maplibre.compose.demoapp

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsModule
import kotlin.js.JsNonModule

/**
 * Side-effect import that registers IANA zone rules with js-joda.
 *
 * kotlinx-datetime TimeZone.of() on JS reads this database. Production DCE drops a library-only
 * import, so [main] holds [jsJodaTz] and the published demo can resolve America/Los_Angeles.
 */
@JsModule("@js-joda/timezone") @JsNonModule external object JsJodaTimeZoneModule

@OptIn(ExperimentalJsExport::class) @JsExport val jsJodaTz = JsJodaTimeZoneModule
