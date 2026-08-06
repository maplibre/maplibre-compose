package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * An in-memory [MlnFfiMapHost] that produces frames without a GPU. Records the calls it receives,
 * so tests can assert on ordering rather than only on end state.
 */
internal class FakeMlnFfiMapHost(
  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)
) : MlnFfiMapHost {

  /**
   * How many of the next acquires should throw, decremented as each one does. A count so a test can
   * arm "fails once, then recovers" or [Int.MAX_VALUE] for "never works again".
   */
  var failingAcquires: Int = 0

  /** Every call this host received, in order. */
  val calls: MutableList<String> = mutableListOf()

  var closed: Boolean = false
    private set

  var currentExtent: MlnFfiMapExtent = MlnFfiMapExtent.Empty
    private set

  /** Bumped whenever the target is reallocated, exactly as a real host does on resize. */
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

  /** Frames acquired but never released; must be empty after a clean teardown. */
  val leakedFrames: Set<Long>
    get() = liveFrames

  override fun resize(extent: MlnFfiMapExtent) {
    calls += "resize(${extent.width}x${extent.height}@${extent.scaleFactor})"
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MlnFfiMapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrame {
    calls += "acquireFrame($frameId)"
    acquireCount++
    if (failingAcquires > 0) {
      failingAcquires--
      throw IllegalStateException("fake host lost its device and cannot acquire frame $frameId")
    }
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
    acquiredFrames++
    liveFrames += frameId
    return MlnFfiMapFrame(
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
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    calls += "completeProducerAccess(${frame.frameId})"
    completedFrames++
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
internal class FakeDesktopMapHostFactory(
  override val supportedBackends: Set<RenderBackendPair> =
    setOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
  override val description: String = "fake test host",
  private val result: ((MapRenderBackend) -> MlnFfiMapHostResult)? = null,
  /**
   * Applied to each host before it is handed out — the only chance to arm a failure, since the
   * first frame is acquired in the draw pass right after the host is created.
   */
  private val configureHost: (FakeMlnFfiMapHost) -> Unit = {},
) : MlnFfiMapHostFactory {

  val created: MutableList<FakeMlnFfiMapHost> = mutableListOf()

  override fun create(producer: MapRenderBackend): MlnFfiMapHostResult {
    result?.let {
      return it(producer)
    }
    val pair = supportedBackends.first { it.producer == producer }
    val host = FakeMlnFfiMapHost(backends = pair).also(configureHost)
    created += host
    return MlnFfiMapHostResult.Created(host)
  }
}
