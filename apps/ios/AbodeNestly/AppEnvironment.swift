import Core
import Shared
import Foundation
import SwiftUI
import Sync
import UI

/// Everything a screen needs from outside, built once at launch and injected through `@Environment`.
struct AppEnvironment: Sendable {
    let config: AppConfig
    let auth: any AuthService
    let api: any ApiService
    let network: NetworkMonitor
    let lastHousehold: LastHouseholdStore
    let database: HouseholdDatabase

    @MainActor
    static func live() -> AppEnvironment {
        let config = AppConfig.main
        let auth = SupabaseAuthService(config: config)
        var forceOffline = false
        var seedLists: [UUID: String] = [:]
        #if DEBUG
        // E2E hooks (Debug builds only): `-force-offline` pins the monitor offline; `E2E_LOCAL_LISTS` is JSON
        // `{householdId: listName}` and seeds one local list per household so isolation is visible in the UI.
        forceOffline = CommandLine.arguments.contains("-force-offline")
        if let json = ProcessInfo.processInfo.environment["E2E_LOCAL_LISTS"],
            let map = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: String] {
            seedLists = Dictionary(uniqueKeysWithValues: map.compactMap { key, name in UUID(uuidString: key).map { ($0, name) } })
        }
        #endif
        // Force-unwrap-free: Application Support always exists on iOS; fall back to the temporary directory.
        let root = (try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )) ?? FileManager.default.temporaryDirectory
        return AppEnvironment(
            config: config,
            auth: auth,
            api: HttpApiService(config: config, auth: auth),
            network: forceOffline ? NetworkMonitor(isOnline: false) : .live(),
            lastHousehold: LastHouseholdStore(),
            database: HouseholdDatabase(rootURL: root, seedLists: seedLists)
        )
    }
}

extension EnvironmentValues {
    /// `nil` only in previews that do not set one up.
    @Entry var appEnvironment: AppEnvironment?
}
