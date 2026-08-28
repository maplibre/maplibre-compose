package org.maplibre.compose.map

private val JsThread = Any()

internal actual fun newSessionLock(): SessionLock = SessionLock.None

internal actual fun currentThreadToken(): Any = JsThread

internal actual fun newIdleGate(): IdleGate =
  object : IdleGate {
    override fun open() = Unit

    override fun await() = Unit
  }
