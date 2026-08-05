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
 * `androidx.lifecycle` decides which thread is the main one by running a block on
 * `Dispatchers.Main`; the only desktop implementation anyone ships is `kotlinx-coroutines-swing`,
 * which answers "the AWT event thread", and compose-glfw publishes no dispatcher of its own. A
 * `MainDispatcherFactory` in compose-glfw would make this unnecessary.
 */
@OptIn(InternalCoroutinesApi::class)
internal object GlfwMainDispatcher : MainCoroutineDispatcher() {

  /**
   * The thread GLFW owns, which on macOS is the process's first thread. Captured rather than
   * discovered, so "am I already on the UI thread?" is answerable before anything is dispatched.
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

  /** Points this dispatcher at the running Compose scene. */
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
     * False on the UI thread: `androidx.lifecycle` probes with
     * `runBlocking(Dispatchers.Main.immediate)`, which deadlocks unless it runs inline.
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
 * Call this above any content that uses lifecycle-aware APIs. It runs during composition, not from
 * an effect: navigation registers its lifecycle observers as it composes.
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
