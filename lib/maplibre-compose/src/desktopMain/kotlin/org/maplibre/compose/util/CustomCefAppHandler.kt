package org.maplibre.compose.util

import dev.datlag.kcef.KCEF
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefSchemeRegistrar
import org.cef.network.CefRequest

internal object CustomCefAppHandler : KCEF.AppHandler() {
  private val cefApp by lazy { CefApp.getInstance() }

  override fun onRegisterCustomSchemes(registrar: CefSchemeRegistrar) {
    registrar.addCustomScheme(
      /* schemeName = */ "jar",
      /* isStandard = */ false,
      /* isLocal = */ true,
      /* isDisplayIsolated = */ false,
      /* isSecure = */ false,
      /* isCorsEnabled = */ true,
      /* isCspBypassing = */ true,
      /* isFetchEnabled = */ true,
    )
  }

  override fun onContextInitialized() {
    super.onContextInitialized()
    cefApp.registerSchemeHandlerFactory(/* schemeName= */ "jar", /* domainName= */ "") {
      _: CefBrowser?,
      _: CefFrame?,
      _: String,
      _: CefRequest ->
      JarResourceHandler()
    }
  }
}
