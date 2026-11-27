import SwiftUI
import UIKit

// Мост между SwiftUI и Compose Multiplatform
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Вызов входной точки Compose Multiplatform (MainViewController)
        // MainViewControllerKt.MainViewController()
        let vc = UIViewController()
        vc.view.backgroundColor = UIColor(red: 0.08, green: 0.08, blue: 0.10, alpha: 1.0)
        
        let label = UILabel()
        label.text = "Xiaomi TV & Universal Remote (iOS)"
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 18, weight: .bold)
        label.translatesAutoresizingMaskIntoConstraints = false
        
        let subLabel = UILabel()
        subLabel.text = "Compose Multiplatform Engine Ready"
        subLabel.textColor = UIColor(red: 0.4, green: 0.7, blue: 1.0, alpha: 1.0)
        subLabel.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        subLabel.translatesAutoresizingMaskIntoConstraints = false

        vc.view.addSubview(label)
        vc.view.addSubview(subLabel)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor, constant: -15),
            subLabel.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
            subLabel.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 8)
        ])

        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
