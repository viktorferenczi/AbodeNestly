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
            // A cold wake runs this before `session.start()` has opened any household file, and `kick()` with none open
            // returns at once, so the system could suspend the app unsynced. Open the last household first;
            // `kick()` returns only when its run (push, then pull) has finished.
            let environment = await MainActor.run { environment }
            if await environment.database.current == nil, let last = environment.lastHousehold.read() {
                _ = try? await environment.database.open(householdId: last)
            }
            await environment.sync.kick()
        }
    }
}
