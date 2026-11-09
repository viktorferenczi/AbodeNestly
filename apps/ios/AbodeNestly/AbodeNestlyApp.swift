import SwiftUI

@main
struct AbodeNestlyApp: App {
    @State private var environment = AppEnvironment.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.appEnvironment, environment)
                // Magic-link callback: abodenestly[-dev]://auth/callback?code=…
                .onOpenURL { url in
                    Task { await environment.auth.handle(url: url) }
                }
        }
    }
}
