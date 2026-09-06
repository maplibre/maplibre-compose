package org.maplibre.compose.map

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler

/** Layer knowledge stays outside input recognition; each queued click captures one loaded order. */
internal class MapInteractionDispatcher(
  private val state: MapState,
  private val desiredRevision: State<State<DesiredStyleRevision?>>,
  private val loadedStyle: State<StyleBinding?>,
  private val interactions: State<MapInteractions>,
  private val subscriptions: InteractionSubscriptions,
) : MapInteractionTarget {
  private var renderedRevision by mutableIntStateOf(0)

  fun presentationChanged(map: MapAdapter) {
    if (state.currentMapAttachment?.adapter === map) renderedRevision++
  }

  override val hoverRevision: Any
    get() =
      listOf(
        state.currentMapAttachment,
        state.currentMapAttachment?.isValid,
        state.isClosed,
        state.viewport,
        renderedRevision,
        loadedStyle.value,
        loadedStyle.value?.isLoaded,
        desiredRevision.value.value,
      )

  override fun captureHover(): HoverScene? {
    val attachment = state.currentMapAttachment ?: return null
    if (!attachment.isValid || state.isClosed) return null
    val style = loadedStyle.value
    val loaded = style?.isLoaded == true
    val nodes = desiredRevision.value.value?.layers?.associateBy { it.definition.id }.orEmpty()
    val layers =
      if (loaded)
        checkNotNull(style).getLayers().asReversed().mapNotNull { layer ->
          val node = nodes[layer.id] ?: return@mapNotNull null
          node.onHover?.let {
            HoverLayer(layer.id, node.hoverSubscription ?: node.registration ?: node, it)
          }
        }
      else emptyList()
    return HoverScene(
      attachment,
      listOf(attachment, style, loaded),
      layers,
      {
        !state.isClosed &&
          attachment.isValid &&
          state.currentMapAttachment === attachment &&
          loadedStyle.value === style &&
          (style?.isLoaded == true) == loaded
      },
    ) { layer, offset ->
      attachment.queryRenderedFeatures(offset, setOf(layer.id)).isNotEmpty()
    }
  }

  override val capabilities: Set<TapFamily>
    get() =
      TapFamily.entries.filterTo(mutableSetOf()) { family ->
        family == TapFamily.Tap && interactions.value.callbacks.unhandledClick != null ||
          desiredRevision.value.value?.layers?.any { it.handler(family) != null } == true
      }

  override fun capture(family: TapFamily): MapClickPath? {
    val attachment = state.currentMapAttachment ?: return null
    val style = loadedStyle.value
    val structure = interactions.value.structuralKey
    val unhandledSlot = subscriptions.unhandledClick.capture().takeIf { family == TapFamily.Tap }
    val nodes = desiredRevision.value.value?.layers?.associateBy { it.definition.id }.orEmpty()
    val candidates =
      style
        ?.takeIf { it.isLoaded }
        ?.getLayers()
        .orEmpty()
        .asReversed()
        .mapNotNull { nodes[it.id]?.takeIf { node -> node.handler(family) != null } }
    fun valid(): Boolean =
      interactions.value.structuralKey == structure &&
        !state.isClosed &&
        attachment.isValid &&
        state.currentMapAttachment === attachment &&
        loadedStyle.value === style &&
        (style == null || style.isLoaded)
    fun current(node: DesiredStyleLayer): DesiredStyleLayer? =
      desiredRevision.value.value?.layers?.firstOrNull {
        it.definition.id == node.definition.id &&
          it.registration === node.registration &&
          it.subscription(family) === node.subscription(family)
      }
    return MapClickPath(::valid) { event ->
      if (!valid()) return@MapClickPath ClickResult.Consume
      for (node in candidates) {
        if (current(node)?.handler(family) == null) continue
        val offset = event.screenOffset
        val padding = node.hitPadding
        val features =
          if (padding == 0.dp) attachment.queryRenderedFeatures(offset, setOf(node.definition.id))
          else
            attachment.queryRenderedFeatures(
              DpRect(
                offset.x - padding,
                offset.y - padding,
                offset.x + padding,
                offset.y + padding,
              ),
              setOf(node.definition.id),
            )
        if (!valid()) return@MapClickPath ClickResult.Consume
        val handler = current(node)?.handler(family) ?: continue
        if (features.isNotEmpty() && handler(features).consumed)
          return@MapClickPath ClickResult.Consume
        if (!valid()) return@MapClickPath ClickResult.Consume
      }
      if (subscriptions.unhandledClick.contains(unhandledSlot))
        interactions.value.callbacks.unhandledClick?.invoke(event as TapEvent) ?: ClickResult.Pass
      else ClickResult.Pass
    }
  }
}

private fun DesiredStyleLayer.handler(family: TapFamily): FeaturesClickHandler? =
  when (family) {
    TapFamily.Tap -> onClick
    TapFamily.DoubleTap -> onDoubleClick
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> onContextClick
    TapFamily.TwoFingerTap -> onTwoFingerClick
  }

private fun DesiredStyleLayer.subscription(family: TapFamily): Any? =
  when (family) {
    TapFamily.Tap -> clickSubscription
    TapFamily.DoubleTap -> doubleClickSubscription
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> contextClickSubscription
    TapFamily.TwoFingerTap -> twoFingerClickSubscription
  }
