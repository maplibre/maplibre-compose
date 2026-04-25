import DemoApp
import SwiftUI
import UIKit

struct MainView: UIViewControllerRepresentable {
    func makeUIViewController(context _: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(
        _: UIViewController, context _: Context
    ) {}
}

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            MainView()
                .ignoresSafeArea(.keyboard) // Compose has own keyboard handler
                .ignoresSafeArea(edges: .all)
        }
    }
}
