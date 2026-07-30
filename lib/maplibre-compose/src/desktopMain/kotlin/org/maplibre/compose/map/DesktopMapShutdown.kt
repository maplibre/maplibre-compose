package org.maplibre.compose.map

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Closes live map sessions when the process exits without disposing their composition.
 *
 * Quitting a Compose Desktop application does not dispose it. Cmd+Q on macOS reaches neither
 * `DisposableEffect.onDispose` nor anything downstream of it — measured, by instrumenting both and
 * seeing nothing at all — so the map's native handles are still open when the JVM exits.
 *
 * That is fatal rather than untidy. MapLibre Native FFI keeps its handles in process-global tables
 * whose C++ destructors run during `exit()`, and destroying a live render session there destroys
 * `mbgl::Renderer`, which takes a `gfx::BackendScope` against a graphics backend that has already
 * gone. The result is a SIGSEGV in `BackendScope::BackendScope` on the VM thread, after the
 * application has otherwise quit cleanly.
 *
 * A JVM shutdown hook runs before the C runtime reaches those destructors, so closing here empties
 * the tables and there is nothing left for them to destroy. This is a net, not the design: an
 * application that disposes its composition unregisters long before the hook fires.
 */
internal object DesktopMapShutdown {

  private val live: MutableSet<DesktopMapSession> =
    Collections.newSetFromMap(ConcurrentHashMap<DesktopMapSession, Boolean>())

  private val hookInstalled = AtomicBoolean(false)

  fun register(session: DesktopMapSession) {
    live.add(session)
    if (hookInstalled.compareAndSet(false, true)) {
      Runtime.getRuntime().addShutdownHook(Thread(::closeLiveSessions, "maplibre-compose-shutdown"))
    }
  }

  fun unregister(session: DesktopMapSession) {
    live.remove(session)
  }

  private fun closeLiveSessions() {
    // Snapshotted because close() unregisters, and each is guarded because a session that cannot
    // close must not stop the others from trying. Nothing is reported: the logger belongs to a
    // composition that is already gone, and a stack trace during shutdown reads like a crash.
    live.toList().forEach { session -> runCatching { session.close() } }
  }
}
