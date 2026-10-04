package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ProjectionValue
import org.maplibre.compose.style.ProjectionTransition

/** A [Literal] representing a [ProjectionTransition] value. */
internal data class ProjectionTransitionLiteral
private constructor(override val value: ProjectionTransition) :
  CompiledLiteral<ProjectionValue, ProjectionTransition> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  companion object {
    fun of(value: ProjectionTransition): ProjectionTransitionLiteral =
      ProjectionTransitionLiteral(value)
  }
}
