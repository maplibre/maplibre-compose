package org.maplibre.compose.testing

import kotlinx.coroutines.runBlocking

// The test worker thread is not the UI thread, so blocking it starves nothing.
internal actual fun runMapTest(block: suspend () -> Unit): MapTestResult = runBlocking { block() }
