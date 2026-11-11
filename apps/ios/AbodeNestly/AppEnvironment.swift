import Core
import Shared
import SwiftUI
import UI

/// Everything a screen needs from outside, built once at launch and injected through `@Environment`.
struct AppEnvironment: Sendable {
    let config: AppConfig
    let auth: any AuthService
    let api: any ApiService
    let network: NetworkMonitor
    let lastHousehold: LastHouseholdStore

    @MainActor
    static func live() -> AppEnvironment {
        let config = AppConfig.main
        let auth = SupabaseAuthService(config: config)
        return AppEnvironment(
            config: config,
            auth: auth,
            api: HttpApiService(config: config, auth: auth),
            network: .live(),
            lastHousehold: LastHouseholdStore()
        )
    }
}

extension EnvironmentValues {
    /// `nil` only in previews that do not set one up.
    @Entry var appEnvironment: AppEnvironment?
}
