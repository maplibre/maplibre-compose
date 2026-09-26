import ClassicBenchmark
import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  func application(
    _: UIApplication,
    didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? =
      nil
  ) -> Bool {
    let window = UIWindow(frame: UIScreen.main.bounds)
    window.rootViewController = ClassicIosBenchmarkKt
      .classicBenchmarkViewController()
    window.makeKeyAndVisible()
    self.window = window
    return true
  }
}
