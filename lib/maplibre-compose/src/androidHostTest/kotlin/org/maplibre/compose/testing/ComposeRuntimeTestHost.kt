package org.maplibre.compose.testing

// Android host tests link SDK stubs whose Compose trace calls throw at runtime.
internal actual val supportsComposeRuntimeTests: Boolean = false
