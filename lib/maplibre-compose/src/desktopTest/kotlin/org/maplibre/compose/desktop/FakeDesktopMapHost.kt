package org.maplibre.compose.desktop

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * An in-memory [DesktopMapHost] that produces frames without a GPU.
 *
 * Lets everything above the graphics boundary — lifecycle ordering, backend negotiation, frame
 * invalidation, resize, surface loss — be tested headlessly. It records the calls it receives so
 * tests can assert on ordering rather than only on end state.
 */
internal class FakeDesktopMapHost(
  override val backends: DesktopBackendPair =
    DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
  override val capabilities: DesktopHostCapabilities =
    DesktopHostCapabilities(
      backends = backends,
      supportsExplicitSynchronization = true,
      // Matches the real borrowed-texture hosts, which cannot resize in place.
      supportsResizeWithoutRecreate = false,
    ),
) : DesktopMapHost {

  /**
   * How many of the next acquires should throw, decremented as each one does.
   *
   * Acquire is where a lost device presents in the shipped hosts: it is the first call in a frame
   * to touch the GPU, so it is the one that reports the target cannot be produced. A count rather
   * than a flag because the interesting cases are "fails once and then works", which must recover,
   * and [Int.MAX_VALUE] for "never works again", which must give up.
   */
  var failingAcquires: Int = 0

  /** Every call this host received, in order. */
  val calls: MutableList<String> = mutableListOf()

  var closed: Boolean = false
    private set

  var currentExtent: DesktopMapExtent = DesktopMapExtent.Empty
    private set

  /** Bumped whenever the target is reallocated, exactly as a real host does on resize. */
  var generation: Long = 0L
    private set

  /**
   * Acquires attempted, including the ones that threw.
   *
   * Distinct from [acquiredFrames] on purpose: a test bounding retries has to count the calls that
   * failed, which by definition produced no frame.
   */
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

  override fun resize(extent: DesktopMapExtent) {
    calls += "resize(${extent.width}x${extent.height}@${extent.scaleFactor})"
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
  }

  override fun acquireFrame(
    frameId: Long,
    extent: DesktopMapExtent,
    presentationTimeNanos: Long?,
  ): DesktopMapFrame {
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
    return FakeFrame(
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

  override fun completeProducerAccess(frame: DesktopMapFrame) {
    calls += "completeProducerAccess(${frame.frameId})"
    completedFrames++
  }

  override fun releaseFrame(frame: DesktopMapFrame) {
    calls += "releaseFrame(${frame.frameId})"
    releasedFrames++
    liveFrames -= frame.frameId
  }

  override fun <T> withProducerAccess(frame: DesktopMapFrame, action: () -> T): T {
    calls += "withProducerAccess(${frame.frameId})"
    return action()
  }

  override fun <T> withRendererAccess(action: () -> T): T {
    calls += "withRendererAccess"
    return action()
  }

  override fun draw(scope: DrawScope, target: DesktopRenderTarget): Boolean {
    calls += "draw(gen=${target.generation})"
    return true
  }

  override fun close() {
    calls += "close"
    closed = true
  }

  private class FakeFrame(
    override val frameId: Long,
    override val extent: DesktopMapExtent,
    override val target: DesktopRenderTarget,
    override val presentationTimeNanos: Long?,
  ) : DesktopMapFrame
}

/** A [DesktopMapHostFactory] producing [FakeDesktopMapHost]s. */
internal class FakeDesktopMapHostFactory(
  override val supportedBackends: Set<DesktopBackendPair> =
    setOf(DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
  override val description: String = "fake test host",
  private val result: ((MapRenderBackend) -> DesktopMapHostResult)? = null,
  /**
   * Applied to each host before it is handed out.
   *
   * The host is created during composition, and the surface acquires its first frame in the draw
   * pass right after, so there is no moment afterwards in which a test could arm a failure before
   * the frame that should hit it.
   */
  private val configureHost: (FakeDesktopMapHost) -> Unit = {},
) : DesktopMapHostFactory {

  val created: MutableList<FakeDesktopMapHost> = mutableListOf()

  override fun create(producer: MapRenderBackend): DesktopMapHostResult {
    result?.let {
      return it(producer)
    }
    val pair = supportedBackends.first { it.producer == producer }
    val host = FakeDesktopMapHost(backends = pair).also(configureHost)
    created += host
    return DesktopMapHostResult.Created(host)
  }
}
