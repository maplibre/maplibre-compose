package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.map.MapExtent

/**
 * An in-memory [MlnFfiMapHost] that produces frames without a GPU, recording every call it
 * receives.
 */
internal class FakeMlnFfiMapHost(
  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)
) : MlnFfiMapHost {

  enum class AcquireOutcome {
    ACQUIRED,
    NOT_READY,
    FAILURE,
    UNEXPECTED_FAILURE,
  }

  /** Optional deterministic outcome script, consumed before the counter-based controls below. */
  val acquireOutcomes: ArrayDeque<AcquireOutcome> = ArrayDeque()

  /**
   * How many of the next acquires should throw, decremented as each one does. [Int.MAX_VALUE] for
   * "never works again".
   */
  var failingAcquires: Int = 0

  /** How many acquires should report that the consumer context does not exist yet. */
  var notReadyAcquires: Int = 0

  /** How many producer calls should remain pending until [completePendingProduction]. */
  var pendingProductions: Int = 0

  /** Every call this host received, in order. */
  val calls: MutableList<String> = mutableListOf()

  var closed: Boolean = false
    private set

  var currentExtent: MapExtent = MapExtent.Empty
    private set

  /** Bumped whenever the target is reallocated, as a real host does on resize. */
  var generation: Long = 0L
    private set

  /** Acquires attempted, including the ones that threw; [acquiredFrames] counts only successes. */
  var acquireCount: Int = 0
    private set

  var acquiredFrames: Int = 0
    private set

  var completedFrames: Int = 0
    private set

  var releasedFrames: Int = 0
    private set

  private val liveFrames = mutableSetOf<Long>()
  private var pendingProduction: PendingProduction? = null
  private var readyProduction: MlnFfiMapFrameProduction.Completed? = null

  private data class PendingProduction(
    val frame: MlnFfiMapFrame,
    val requestFrame: () -> Unit,
    val action: () -> MlnFfiFrameResult,
  )

  /** Frames acquired but never released; must be empty after a clean teardown. */
  val leakedFrames: Set<Long>
    get() = liveFrames

  override fun resize(extent: MapExtent) {
    calls += "resize(${extent.width}x${extent.height}@${extent.scaleFactor})"
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition {
    calls += "acquireFrame($frameId)"
    acquireCount++
    when (acquireOutcomes.removeFirstOrNull()) {
      AcquireOutcome.NOT_READY -> return MlnFfiMapFrameAcquisition.NotReady
      AcquireOutcome.FAILURE ->
        throw MlnFfiRecoverableFrameException(
          "fake host lost its device and cannot acquire frame $frameId",
          null,
        )
      AcquireOutcome.UNEXPECTED_FAILURE ->
        throw IllegalStateException("fake host has a programming error on frame $frameId")
      AcquireOutcome.ACQUIRED,
      null -> Unit
    }
    if (notReadyAcquires > 0) {
      notReadyAcquires--
      return MlnFfiMapFrameAcquisition.NotReady
    }
    if (failingAcquires > 0) {
      failingAcquires--
      throw MlnFfiRecoverableFrameException(
        "fake host lost its device and cannot acquire frame $frameId",
        null,
      )
    }
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
    acquiredFrames++
    liveFrames += frameId
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        frameId = frameId,
        extent = extent,
        target =
          VulkanImageTarget(
            context =
              VulkanContextHandles(
                instance = NativeHandle(1),
                physicalDevice = NativeHandle(2),
                device = NativeHandle(3),
                graphicsQueue = NativeHandle(4),
                graphicsQueueFamilyIndex = 0,
                getInstanceProcAddr = NativeHandle(5),
                getDeviceProcAddr = NativeHandle(6),
              ),
            image = NativeHandle(100 + frameId),
            imageView = NativeHandle(200 + frameId),
            format = 37,
            initialLayout = 0,
            finalLayout = 1,
            queueFamilyIndex = 0,
            extent = extent,
            generation = generation,
          ),
        presentationTimeNanos = presentationTimeNanos,
      )
    )
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    calls += "completeProducerAccess(${frame.frameId})"
    completedFrames++
  }

  override fun produceFrame(
    frame: MlnFfiMapFrame,
    requestFrame: () -> Unit,
    action: () -> MlnFfiFrameResult,
  ): MlnFfiMapFrameProduction {
    calls += "produceFrame(${frame.frameId})"
    readyProduction?.let { ready ->
      readyProduction = null
      releaseFrame(frame)
      return ready
    }
    if (pendingProduction != null) {
      releaseFrame(frame)
      return MlnFfiMapFrameProduction.Pending
    }
    if (pendingProductions > 0) {
      pendingProductions--
      pendingProduction = PendingProduction(frame, requestFrame, action)
      return MlnFfiMapFrameProduction.Pending
    }
    return super<MlnFfiMapHost>.produceFrame(frame, requestFrame, action)
  }

  /** Completes the producer work that [produceFrame] deferred. */
  fun completePendingProduction() {
    val pending = checkNotNull(pendingProduction) { "No fake producer work is pending" }
    pendingProduction = null
    try {
      val result = pending.action()
      if (result == MlnFfiFrameResult.RENDERED) completeProducerAccess(pending.frame)
      readyProduction = MlnFfiMapFrameProduction.Completed(result, pending.frame.target)
    } finally {
      releaseFrame(pending.frame)
    }
    pending.requestFrame()
  }

  override fun releaseFrame(frame: MlnFfiMapFrame) {
    calls += "releaseFrame(${frame.frameId})"
    releasedFrames++
    liveFrames -= frame.frameId
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T {
    calls += "withProducerAccess(${frame.frameId})"
    return action()
  }

  override fun <T> withRendererAccess(action: () -> T): T {
    calls += "withRendererAccess"
    return action()
  }

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    calls += "draw(gen=${target.generation})"
    return true
  }

  override fun close() {
    calls += "close"
    closed = true
  }
}

/** A [MlnFfiMapHostFactory] producing [FakeMlnFfiMapHost]s. */
internal class FakeMlnFfiMapHostFactory(
  private val bridge: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
  override val description: String = "fake test host",
  private val result: ((MapRenderBackend) -> MlnFfiMapHostResult)? = null,
  /**
   * Applied to each host before it is handed out — the only chance to arm a failure, since the
   * first frame is acquired in the draw pass right after the host is created.
   */
  private val configureHost: (FakeMlnFfiMapHost) -> Unit = {},
) : MlnFfiMapHostFactory {

  override val bridges: List<RenderBackendPair> = listOf(bridge)

  val created: MutableList<FakeMlnFfiMapHost> = mutableListOf()

  override fun create(backends: RenderBackendPair): MlnFfiMapHostResult {
    check(backends == bridge) { "The fake factory was asked for $backends, not $bridge" }
    result?.let {
      return it(backends.producer)
    }
    val host = FakeMlnFfiMapHost(backends = backends).also(configureHost)
    created += host
    return MlnFfiMapHostResult.Created(host)
  }
}
