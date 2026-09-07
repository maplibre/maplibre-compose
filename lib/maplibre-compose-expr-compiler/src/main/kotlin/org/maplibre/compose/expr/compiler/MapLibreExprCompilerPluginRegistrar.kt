package org.maplibre.compose.expr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class MapLibreExprCompilerPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String = PLUGIN_ID
  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    IrGenerationExtension.registerExtension(MapLibreExprIrGenerationExtension())
  }

  companion object {
    const val PLUGIN_ID: String = "org.maplibre.compose.expr"
  }
}
