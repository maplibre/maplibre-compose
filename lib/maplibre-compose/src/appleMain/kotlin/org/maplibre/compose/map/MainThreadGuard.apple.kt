package org.maplibre.compose.map

import platform.Foundation.NSThread

internal actual fun currentThreadIdentity(): Any = NSThread.currentThread
