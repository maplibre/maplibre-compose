import DemoApp
import SwiftUI
import UIKit

struct MainView: UIViewControllerRepresentable {
  func makeUIViewController(context: Context) -> UIViewController {
    MainViewControllerKt.MainViewController()
  }

  func updateUIViewController(
    _ uiViewController: UIViewController, context: Context
  ) {}
}
@main
struct iOSApp: App {
  var body: some Scene {
    WindowGroup {
      MainView()
        .ignoresSafeArea(.keyboard)  // Compose has own keyboard handler
        .ignoresSafeArea(edges: .all)
    }
  }
}
