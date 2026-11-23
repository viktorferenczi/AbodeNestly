import Core
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
        // SPEC §6.4: the system wakes the app to drain the outbox; `Info.plist` lists the identifier.
        .backgroundTask(.appRefresh(BackgroundSync.taskIdentifier)) {
            await environment.sync.kick()
        }
    }
}
