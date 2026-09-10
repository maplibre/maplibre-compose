import CarPlay
import DemoApp
import UIKit

final class CarPlaySceneDelegate: UIResponder,
  CPTemplateApplicationSceneDelegate, CPMapTemplateDelegate
{
  private var host: CarPlayMapHost?
  private weak var interfaceController: CPInterfaceController?
  private let mapTemplate = CPMapTemplate()
  private var zoomScale: CGFloat = 1
  private var isPanning = false
  private var panTimer: Timer?

  func templateApplicationScene(
    _ scene: CPTemplateApplicationScene,
    didConnect interfaceController: CPInterfaceController,
    to window: CPWindow
  ) {
    let style: UIUserInterfaceStyle
    if #available(iOS 15.4, *) {
      style = scene.contentStyle
    } else {
      style = window.traitCollection.userInterfaceStyle
    }
    let host = CarPlayMapHost(initialDark: style == .dark)
    self.host = host
    self.interfaceController = interfaceController
    window.rootViewController = CarMapViewController(host: host)
    mapTemplate.mapDelegate = self
    mapTemplate.mapButtons = [
      mapButton("plus") { [weak host] in host?.zoom(levels: 1) },
      mapButton("minus") { [weak host] in host?.zoom(levels: -1) },
      mapButton("scope") { [weak host] in host?.recenter() },
      mapButton("hand.draw") { [weak self] in
        self?.mapTemplate.showPanningInterface(animated: true)
      },
    ]
    host.onChange = { [weak self] in self?.updateButtons() }
    updateButtons()
    interfaceController.setRootTemplate(
      mapTemplate,
      animated: false,
      completion: nil
    )
    host.setActive(active: scene.activationState != .background)
  }

  func templateApplicationScene(
    _: CPTemplateApplicationScene,
    didDisconnect _: CPInterfaceController,
    from window: CPWindow
  ) {
    stopPanning()
    host?.close()
    window.rootViewController = nil
    host = nil
    interfaceController = nil
  }

  func sceneWillEnterForeground(_: UIScene) {
    host?.setActive(active: true)
  }

  func sceneDidEnterBackground(_: UIScene) {
    stopPanning()
    host?.setActive(active: false)
  }

  func contentStyleDidChange(_ contentStyle: UIUserInterfaceStyle) {
    host?.updateDarkMode(dark: contentStyle == .dark)
  }

  private func updateButtons() {
    mapTemplate.leadingNavigationBarButtons = [
      CPBarButton(title: host?.status ?? "MapLibre", handler: nil),
    ]
    mapTemplate.trailingNavigationBarButtons = [
      CPBarButton(title: isPanning ? "Done" : "Credits") { [weak self] _ in
        guard let self else { return }
        if isPanning {
          mapTemplate.dismissPanningInterface(animated: true)
        } else {
          showCredits()
        }
      },
    ]
  }

  private func showCredits() {
    let items = (host?.credits ?? []).map { html in
      let attributed = try? NSAttributedString(
        data: Data(html.utf8),
        options: [.documentType: NSAttributedString.DocumentType.html],
        documentAttributes: nil
      )
      var links: [String] = []
      attributed?.enumerateAttribute(
        .link,
        in: NSRange(location: 0, length: attributed?.length ?? 0)
      ) {
        value, _, _ in
        if let url = value as? URL {
          links.append(url.absoluteString)
        }
      }
      return CPListItem(
        text: attributed?.string ?? html,
        detailText: links.joined(separator: "\n")
      )
    }
    interfaceController?.pushTemplate(
      CPListTemplate(
        title: "Map credits",
        sections: [CPListSection(items: items)]
      ),
      animated: true, completion: nil
    )
  }

  private func mapButton(_ symbol: String,
                         action: @escaping () -> Void) -> CPMapButton
  {
    let button = CPMapButton { _ in action() }
    button.image = UIImage(systemName: symbol)
    return button
  }

  func mapTemplateDidShowPanningInterface(_: CPMapTemplate) {
    isPanning = true
    updateButtons()
  }

  func mapTemplateDidDismissPanningInterface(_: CPMapTemplate) {
    isPanning = false
    stopPanning()
    updateButtons()
  }

  func mapTemplate(
    _: CPMapTemplate,
    panWith direction: CPMapTemplate.PanDirection
  ) {
    pan(direction)
  }

  func mapTemplate(
    _: CPMapTemplate,
    panBeganWith direction: CPMapTemplate.PanDirection
  ) {
    stopPanning()
    pan(direction)
    panTimer = Timer
      .scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
        self?.pan(direction)
      }
  }

  func mapTemplate(
    _: CPMapTemplate,
    panEndedWith _: CPMapTemplate.PanDirection
  ) {
    stopPanning()
  }

  private func pan(_ direction: CPMapTemplate.PanDirection) {
    let x = (direction.contains(.left) ? 40.0 : 0) -
      (direction.contains(.right) ? 40.0 : 0)
    let y = (direction.contains(.up) ? 40.0 : 0) -
      (direction.contains(.down) ? 40.0 : 0)
    host?.pan(x: x, y: y)
  }

  private func stopPanning() {
    panTimer?.invalidate()
    panTimer = nil
  }

  func mapTemplate(
    _: CPMapTemplate,
    didUpdatePanGestureWithTranslation translation: CGPoint,
    velocity _: CGPoint
  ) {
    host?.pan(x: translation.x, y: translation.y)
  }

  func mapTemplate(
    _: CPMapTemplate,
    didEndPanGestureWithVelocity velocity: CGPoint
  ) {
    host?.fling(x: velocity.x, y: velocity.y)
  }

  @available(iOS 26.0, *)
  func mapTemplateDidBeginZoomGesture(_: CPMapTemplate) {
    zoomScale = 1
  }

  @available(iOS 26.0, *)
  func mapTemplate(
    _: CPMapTemplate,
    didUpdateZoomGestureWithCenter center: CGPoint,
    scale: CGFloat,
    velocity _: CGFloat
  ) {
    host?.scale(factor: scale / zoomScale, x: center.x, y: center.y)
    zoomScale = scale
  }
}

private final class CarMapViewController: UIViewController {
  private let host: CarPlayMapHost

  init(host: CarPlayMapHost) {
    self.host = host
    super.init(nibName: nil, bundle: nil)
  }

  @available(*, unavailable)
  required init?(coder _: NSCoder) {
    fatalError("Use init(host:)")
  }

  override func loadView() {
    view = host.view
  }

  override func viewSafeAreaInsetsDidChange() {
    super.viewSafeAreaInsetsDidChange()
    let insets = view.safeAreaInsets
    host.updateInsets(
      top: insets.top,
      left: insets.left,
      bottom: insets.bottom,
      right: insets.right
    )
  }
}
