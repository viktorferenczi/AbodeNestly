import Core
import SwiftUI

@main
struct AbodeNestlyApp: App {
    @State private var environment = AppEnvironment.shared

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
            // A cold wake runs this before `session.start()` has opened any household file, and `kick()` with none open
            // returns at once, so the system could suspend the app unsynced. Open the last household first;
            // `kick()` returns only when its run (push, then pull) has finished.
            let env: AppEnvironment = await MainActor.run { environment }
            if await env.database.current == nil, let last = env.lastHousehold.read() {
                _ = try? await env.database.open(householdId: last)
            }
            await env.sync.kick()
        }
    }
}
