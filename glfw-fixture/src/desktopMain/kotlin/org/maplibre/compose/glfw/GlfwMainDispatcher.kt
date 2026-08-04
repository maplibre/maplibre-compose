package org.maplibre.compose.glfw

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory

/**
 * `Dispatchers.Main`, pointed at the thread compose-glfw runs its UI on.
 *
 * Nothing about maps needs this. It is here because running the *demo* rather than a lone map drags
 * in `androidx.lifecycle`, and that library decides which thread is "the main thread" by running a
 * block on `Dispatchers.Main` and remembering where it lands. On desktop the only implementation
 * anyone ships is `kotlinx-coroutines-swing`, which answers "the AWT event thread" — so under a
 * GLFW host every `addObserver` call throws, and navigation-compose cannot complete a single
 * transition. Removing that dependency instead leaves `Dispatchers.Main` missing, which
 * `repeatOnLifecycle` treats as fatal, and MapLibre Compose's own `rememberUserLocationState` uses
 * `repeatOnLifecycle`. Both ends of that are measured, not assumed.
 *
 * So a Compose host that is not AWT has to bring its own main dispatcher, and compose-glfw does not
 * publish one: it has a perfectly good UI dispatcher internally, but nothing registers it with
 * kotlinx-coroutines. This is the smallest thing that closes the gap from outside, and it is the
 * clearest candidate to push upstream — a `MainDispatcherFactory` belongs in compose-glfw, where it
 * would need no installation step at all.
 */
@OptIn(InternalCoroutinesApi::class)
internal object GlfwMainDispatcher : MainCoroutineDispatcher() {

  /**
   * The thread GLFW owns, which on macOS is the process's first thread.
   *
   * Captured rather than discovered, because the interesting question — "am I already on the UI
   * thread?" — has to be answerable before anything has been dispatched.
   */
  @Volatile private var uiThread: Thread? = null

  /** The Compose scene's own dispatcher, once a composition has handed it over. */
  @Volatile private var delegate: CoroutineDispatcher? = null

  /** Work that arrived before [install]. Drained in order the moment a delegate exists. */
  private val pending = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()

  override val immediate: MainCoroutineDispatcher
    get() = Immediate

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    val target = delegate
    if (target != null) target.dispatch(context, block) else pending.add(context to block)
  }

  /**
   * Points this dispatcher at the running Compose scene.
   *
   * The scene's dispatcher is reached through the ordinary `rememberCoroutineScope()` rather than
   * through anything private: Compose runs effects on the same dispatcher it runs the frame on, so
   * the interceptor in that scope's context *is* compose-glfw's UI dispatcher, one wrapper deep.
   */
  fun install(dispatcher: CoroutineDispatcher, thread: Thread) {
    uiThread = thread
    delegate = dispatcher
    while (true) {
      val (context, block) = pending.poll() ?: return
      dispatcher.dispatch(context, block)
    }
  }

  private object Immediate : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher
      get() = this

    /**
     * False on the UI thread, which is the whole reason this variant exists.
     *
     * `androidx.lifecycle` learns the main thread with `runBlocking(Dispatchers.Main.immediate)`.
     * Dispatching that would park the UI thread waiting for work only the UI thread can run, so it
     * has to execute inline; answering false here is what makes it do so, and what makes the
     * library conclude that the GLFW thread is the main one.
     */
    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
      Thread.currentThread() !== uiThread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      GlfwMainDispatcher.dispatch(context, block)
    }
  }
}

/** Registers [GlfwMainDispatcher] as `Dispatchers.Main`; see `META-INF/services`. */
@OptIn(InternalCoroutinesApi::class)
internal class GlfwMainDispatcherFactory : MainDispatcherFactory {
  // Above kotlinx-coroutines-swing's 0, so that this wins if both ever end up on a classpath.
  override val loadPriority: Int = 10

  override fun createDispatcher(
    allFactories: List<MainDispatcherFactory>
  ): MainCoroutineDispatcher = GlfwMainDispatcher

  override fun hintOnError(): String =
    "The compose-glfw fixture's main dispatcher was not installed; see GlfwMainDispatcher."
}

/**
 * Hands the running Compose scene's dispatcher to [GlfwMainDispatcher].
 *
 * Call this above any content that uses lifecycle-aware APIs. It runs during composition rather
 * than from an effect on purpose: navigation registers its lifecycle observers as it composes, so
 * an effect would run too late by exactly one pass.
 */
@Composable
internal fun InstallGlfwMainDispatcher() {
  val scope = rememberCoroutineScope()
  remember(scope) {
    val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
    checkNotNull(dispatcher) { "The Compose scene has no dispatcher to use as Dispatchers.Main" }
    GlfwMainDispatcher.install(dispatcher, Thread.currentThread())
  }
}
