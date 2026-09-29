package org.maplibre.compose.mlnffi

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.roundToInt
import org.maplibre.compose.map.MapExtent

/**
 * An in-memory [MlnFfiMapHost] that produces frames without a GPU, recording every call it
 * receives.
 */
internal class FakeMlnFfiMapHost(
  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)
) : MlnFfiMapHost {

  data class DrawRecord(
    val target: MlnFfiRenderTarget,
    val destinationLeft: Int,
    val destinationTop: Int,
    val destinationWidth: Int,
    val destinationHeight: Int,
    val scopeWidth: Int,
    val scopeHeight: Int,
  )

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

  /** Whether each acquired frame should use a fresh allocation and generation. */
  var rotateTargetsOnAcquire: Boolean = false

  /** Every target passed to [draw], in order. */
  val drawnTargets: MutableList<MlnFfiRenderTarget> = mutableListOf()

  /** Every target and destination size passed to [draw], in order. */
  val drawRecords: MutableList<DrawRecord> = mutableListOf()

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

  private val liveFrames = mutableListOf<MlnFfiMapFrame>()

  /** Frames acquired but never released; must be empty after a clean teardown. */
  val leakedFrames: List<MlnFfiMapFrame>
    get() = liveFrames

  override fun resize(extent: MapExtent) {
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
  }

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition {
    acquireCount++
    when (acquireOutcomes.removeFirstOrNull()) {
      AcquireOutcome.NOT_READY -> return MlnFfiMapFrameAcquisition.NotReady
      AcquireOutcome.FAILURE ->
        throw MlnFfiRecoverableFrameException(
          "fake host lost its device and cannot acquire frame $acquireCount",
          null,
        )
      AcquireOutcome.UNEXPECTED_FAILURE ->
        throw IllegalStateException("fake host has a programming error on frame $acquireCount")
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
        "fake host lost its device and cannot acquire frame $acquireCount",
        null,
      )
    }
    if (extent != currentExtent) {
      currentExtent = extent
      generation++
    }
    if (rotateTargetsOnAcquire) generation++
    acquiredFrames++
    val frame =
      MlnFfiMapFrame(
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
            image = NativeHandle(100 + generation),
            imageView = NativeHandle(200 + generation),
            format = 37,
            initialLayout = 0,
            finalLayout = 1,
            extent = extent,
            generation = generation,
          )
      )
    liveFrames += frame
    return MlnFfiMapFrameAcquisition.Acquired(frame)
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    completedFrames++
  }

  override fun releaseFrame(frame: MlnFfiMapFrame) {
    releasedFrames++
    liveFrames.indexOfFirst { it === frame }.takeIf { it >= 0 }?.let(liveFrames::removeAt)
  }

  override fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean {
    drawnTargets += target
    drawRecords +=
      DrawRecord(
        target = target,
        destinationLeft = destination.left,
        destinationTop = destination.top,
        destinationWidth = destination.width,
        destinationHeight = destination.height,
        scopeWidth = scope.size.width.roundToInt(),
        scopeHeight = scope.size.height.roundToInt(),
      )
    return true
  }

  override fun close() {
    closed = true
  }
}

/** A [MlnFfiMapHostFactory] producing [FakeMlnFfiMapHost]s. */
internal class FakeMlnFfiMapHostFactory(
  private val bridge: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
  override val description: String = "fake test host",
  private val result: ((MapRenderBackend) -> MlnFfiMapHostResult)? = null,
  /** Configures failures before the draw pass acquires the new host's first frame. */
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
