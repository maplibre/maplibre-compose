package org.maplibre.compose.expr.compiler

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * Types [ExprEmit.lit] can freeze at composition time. The plugin uses this list to reject captures
 * during IR generation instead of throwing when the tree is built.
 */
internal object ConstableTypes {
  val names: Set<String> =
    setOf(
      "kotlin.Boolean",
      "kotlin.Byte",
      "kotlin.Short",
      "kotlin.Int",
      "kotlin.Long",
      "kotlin.Float",
      "kotlin.Double",
      "kotlin.Number",
      "kotlin.String",
      "kotlin.time.Duration",
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.ui.unit.Dp",
      "androidx.compose.ui.unit.DpOffset",
      "androidx.compose.ui.unit.DpSize",
      "androidx.compose.ui.unit.TextUnit",
      "androidx.compose.ui.geometry.Offset",
      "androidx.compose.ui.graphics.ImageBitmap",
      "androidx.compose.ui.graphics.painter.Painter",
      "androidx.compose.ui.graphics.ColorFilter",
      "org.maplibre.compose.util.DpPadding",
      "org.maplibre.compose.util.ImageStretch",
      "org.maplibre.compose.style.ProjectionTransition",
      "org.maplibre.compose.expressions.ast.Expression",
      "org.maplibre.spatialk.geojson.GeoJsonObject",
      "org.maplibre.spatialk.geojson.Geometry",
      "org.maplibre.spatialk.geojson.Feature",
      "org.maplibre.spatialk.geojson.FeatureCollection",
    )

  val listLike: Set<String> =
    setOf(
      "kotlin.collections.List",
      "kotlin.collections.MutableList",
      "kotlin.collections.Collection",
      "kotlin.Array",
    )

  val mapLike: Set<String> = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

  private val enumValueFq = "org.maplibre.compose.expressions.value.EnumValue"

  private val subtypeRoots =
    setOf(
      "org.maplibre.compose.expressions.ast.Expression",
      "org.maplibre.compose.expressions.value.EnumValue",
      "org.maplibre.spatialk.geojson.GeoJsonObject",
      "org.maplibre.spatialk.geojson.Geometry",
      "androidx.compose.ui.graphics.painter.Painter",
    )

  fun isConstableFqName(fqName: String): Boolean = fqName in names

  fun isListLikeFqName(fqName: String): Boolean = fqName in listLike

  fun isMapLikeFqName(fqName: String): Boolean = fqName in mapLike

  fun typeName(type: IrType): String =
    type.classFqName?.asString()
      ?: type.getClass()?.fqNameWhenAvailable?.asString()
      ?: type.toString()

  fun isConstable(type: IrType): Boolean {
    if (type.isUnit()) return false
    val unwrapped = if (type.isNullable()) type.makeNotNull() else type
    // `null` is typed as Nothing?; freeze it as nil.
    if (unwrapped.classFqName?.asString() == "kotlin.Nothing") return true
    val fq = unwrapped.classFqName?.asString()
    if (fq != null && fq in names) return true
    if (fq != null && fq in listLike) {
      val argument = unwrapped.singleTypeArgument() ?: return false
      return argument.isStarProjection() || isConstable(argument)
    }
    val irClass = unwrapped.getClass() ?: return false
    return irClass.inheritsAny(subtypeRoots + enumValueFq)
  }

  fun isMapLike(type: IrType): Boolean {
    val unwrapped = if (type.isNullable()) type.makeNotNull() else type
    val fq = unwrapped.classFqName?.asString() ?: return false
    return fq in mapLike
  }

  fun isListLike(type: IrType): Boolean {
    val unwrapped = if (type.isNullable()) type.makeNotNull() else type
    val fq = unwrapped.classFqName?.asString() ?: return false
    return fq in listLike
  }

  fun isStringLike(type: IrType): Boolean {
    val unwrapped = if (type.isNullable()) type.makeNotNull() else type
    return unwrapped.classFqName?.asString() == "kotlin.String"
  }

  fun isImageBitmap(type: IrType): Boolean =
    typeName(type) == "androidx.compose.ui.graphics.ImageBitmap"

  fun isPainter(type: IrType): Boolean {
    val fq = typeName(type)
    if (fq == "androidx.compose.ui.graphics.painter.Painter") return true
    return type.getClass()?.inheritsAny(setOf("androidx.compose.ui.graphics.painter.Painter")) ==
      true
  }

  private fun IrType.singleTypeArgument(): IrType? {
    val simple = this as? IrSimpleType ?: return null
    return simple.arguments.singleOrNull()?.typeOrNull
  }

  private fun IrTypeArgument.isStarProjection(): Boolean = typeOrNull == null

  private fun IrClass.inheritsAny(roots: Set<String>): Boolean {
    val seen = mutableSetOf<IrClass>()
    fun walk(cls: IrClass): Boolean {
      if (!seen.add(cls)) return false
      val fq = cls.fqNameWhenAvailable?.asString()
      if (fq != null && fq in roots) return true
      return cls.superTypes.any { superType ->
        val superFq = superType.classFqName?.asString()
        (superFq != null && superFq in roots) || superType.getClass()?.let(::walk) == true
      }
    }
    return walk(this)
  }
}
