import Core
import Shared
import SwiftUI
import UI

/// Everything a screen needs from outside, built once at launch and injected through `@Environment`.
struct AppEnvironment: Sendable {
    let config: AppConfig
    let auth: any AuthService
    let network: NetworkMonitor
    let lastHousehold: LastHouseholdStore

    @MainActor
    static func live() -> AppEnvironment {
        let config = AppConfig.main
        return AppEnvironment(
            config: config,
            auth: SupabaseAuthService(config: config),
            network: .live(),
            lastHousehold: LastHouseholdStore()
        )
    }
}

extension EnvironmentValues {
    /// `nil` only in previews that do not set one up.
    @Entry var appEnvironment: AppEnvironment?
}
