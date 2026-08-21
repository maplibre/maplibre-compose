package org.maplibre.compose.mlnffi

/**
 * One dedicated thread that runs [body] to completion and then exits.
 *
 * MapLibre binds a runtime to the thread that created it and allows one runtime per thread, so a
 * loop that owns a runtime owns a thread with it. An actual starts one real OS thread that carries
 * [name], runs [body] exactly once, and never migrates to another thread or returns to a pool. The
 * thread keeps nothing alive: a host that exits while [body] is still running leaves the thread
 * behind rather than waiting for it.
 *
 * A caller starts the thread once and joins it at most once; a fire-and-forget worker, such as one
 * resource read, is never joined.
 *
 * An actual over pthreads has three obligations that the JVM actual gets for free. Darwin's
 * `pthread_setname_np` names only the calling thread, so the thread applies [name] to itself as the
 * first thing it does, and a crash report from the moments before that shows an unnamed thread. The
 * `StableRef` that carries [body] into the thread belongs to the thread body, which disposes it
 * when [body] returns; [start] disposes it only when `pthread_create` fails, because no body will
 * run to do it then. Darwin also has no timed join, so [join] waits on a completion flag that the
 * thread publishes, and every such wait belongs in a loop over that flag, because a condition
 * variable returns from a spurious wakeup as readily as from a signal.
 *
 * MapLibre's thread-affine, host-pumped execution model is what puts the host on raw threads at
 * all. Upstream work to move execution into the native core (maplibre-native-ffi#631) would retire
 * this machinery.
 */
internal expect class MlnFfiOwnerThread(name: String, body: () -> Unit) {
  /** Starts the thread. Called once. */
  fun start()

  /** Whether the calling thread is this one. */
  fun isCurrent(): Boolean

  /**
   * Waits up to [timeoutMillis] for [body] to return, reporting whether it did.
   *
   * A false result leaves the thread running, and the caller reports the wedged loop rather than
   * waiting again. An actual that allocated a control block for the thread keeps it allocated in
   * that case, because the thread may still write to it.
   */
  fun join(timeoutMillis: Long): Boolean
}

/** The name of the calling thread, for diagnostics. */
internal expect fun currentMlnFfiThreadName(): String
