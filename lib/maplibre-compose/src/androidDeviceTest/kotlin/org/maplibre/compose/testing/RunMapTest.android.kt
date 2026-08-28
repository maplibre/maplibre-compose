package org.maplibre.compose.testing

import kotlinx.coroutines.runBlocking

// The instrumentation thread is not the main looper, so blocking it starves nothing.
internal actual fun runMapTest(block: suspend () -> Unit): MapTestResult = runBlocking { block() }
