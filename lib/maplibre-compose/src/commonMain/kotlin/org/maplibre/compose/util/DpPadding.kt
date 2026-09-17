package org.maplibre.compose.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Physical left, top, right, and bottom padding in dp. */
@Serializable(with = DpPaddingSerializer::class)
@Immutable
public data class DpPadding(
  val left: Dp = 0.dp,
  val top: Dp = 0.dp,
  val right: Dp = 0.dp,
  val bottom: Dp = 0.dp,
) {
  public companion object {
    public val Zero: DpPadding = DpPadding()
  }
}

@Serializable
private data class SerializedDpPadding(
  val left: Float = 0f,
  val top: Float = 0f,
  val right: Float = 0f,
  val bottom: Float = 0f,
)

internal object DpPaddingSerializer : KSerializer<DpPadding> {
  override val descriptor: SerialDescriptor = SerializedDpPadding.serializer().descriptor

  override fun serialize(encoder: Encoder, value: DpPadding) {
    encoder.encodeSerializableValue(
      SerializedDpPadding.serializer(),
      SerializedDpPadding(
        value.left.value,
        value.top.value,
        value.right.value,
        value.bottom.value,
      ),
    )
  }

  override fun deserialize(decoder: Decoder): DpPadding {
    val value = decoder.decodeSerializableValue(SerializedDpPadding.serializer())
    return DpPadding(value.left.dp, value.top.dp, value.right.dp, value.bottom.dp)
  }
}
