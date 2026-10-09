package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ProjectionValue
import org.maplibre.compose.style.ProjectionTransition
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/** A [Literal] representing a [ProjectionTransition] value. */
@OptIn(ExperimentalMaplibreComposeApi::class)
internal data class ProjectionTransitionLiteral
private constructor(override val value: ProjectionTransition) :
  CompiledLiteral<ProjectionValue, ProjectionTransition> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    fun of(value: ProjectionTransition): ProjectionTransitionLiteral =
      ProjectionTransitionLiteral(value)
  }
}
