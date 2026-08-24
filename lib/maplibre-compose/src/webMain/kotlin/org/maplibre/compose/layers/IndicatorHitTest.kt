package org.maplibre.compose.layers

internal data class IndicatorPoint(val x: Double, val y: Double)

/** Clips the two captured strip triangles to the viewport and the globe horizon in clip space. */
internal fun indicatorClipTriangles(
  corners: dynamic,
  width: Double,
  height: Double,
): List<List<IndicatorPoint>> =
  listOf(0, 3).map { start ->
    clipTriangle(
      (start until start + 3).map { i -> DoubleArray(4) { j -> corners[i * 4 + j] as Double } },
      width,
      height,
    )
  }

private fun clipTriangle(
  vertices: List<DoubleArray>,
  width: Double,
  height: Double,
): List<IndicatorPoint> {
  var polygon = vertices
  val planes: List<(DoubleArray) -> Double> =
    listOf(
      { it[3] + it[0] },
      { it[3] - it[0] },
      { it[3] + it[1] },
      { it[3] - it[1] },
      { it[3] + it[2] },
      { it[3] - it[2] },
      { it[3] - 1e-9 },
    )
  for (distance in planes) {
    val output = mutableListOf<DoubleArray>()
    if (polygon.isEmpty()) return emptyList()
    var previous = polygon.last()
    var pd = distance(previous)
    for (current in polygon) {
      val cd = distance(current)
      if ((pd >= 0) != (cd >= 0)) {
        val t = pd / (pd - cd)
        output += DoubleArray(4) { previous[it] + (current[it] - previous[it]) * t }
      }
      if (cd >= 0) output += current
      previous = current
      pd = cd
    }
    polygon = output
  }
  return polygon.map {
    IndicatorPoint((it[0] / it[3] + 1) * width / 2, (1 - it[1] / it[3]) * height / 2)
  }
}

/** Separating-axis test, including degenerate point queries and padded rectangular queries. */
internal fun indicatorIntersects(
  polygon: List<IndicatorPoint>,
  left: Double,
  top: Double,
  right: Double,
  bottom: Double,
): Boolean {
  if (polygon.size < 3) return false
  val box =
    listOf(
      IndicatorPoint(left, top),
      IndicatorPoint(right, top),
      IndicatorPoint(right, bottom),
      IndicatorPoint(left, bottom),
    )
  val axes = mutableListOf(IndicatorPoint(1.0, 0.0), IndicatorPoint(0.0, 1.0))
  polygon.indices.forEach { i ->
    val a = polygon[i]
    val b = polygon[(i + 1) % polygon.size]
    axes += IndicatorPoint(a.y - b.y, b.x - a.x)
  }
  return axes.all { axis ->
    val p = polygon.map { it.x * axis.x + it.y * axis.y }
    val q = box.map { it.x * axis.x + it.y * axis.y }
    p.max() >= q.min() && q.max() >= p.min()
  }
}
