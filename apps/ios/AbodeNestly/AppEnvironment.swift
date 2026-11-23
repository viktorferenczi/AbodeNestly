import Contract
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
    /// The push/pull loop (SHOP-08); screens only `kick()` it.
    let sync: any SyncEngine
    /// Schedules the iOS background refresh that drains the outbox while the app is not running.
    let backgroundSync: any BackgroundSyncScheduler

    @MainActor
    static func live() -> AppEnvironment {
        let config = AppConfig.main
        let auth = SupabaseAuthService(config: config)
        var forceOffline = false
        var seedLists: [UUID: String] = [:]
        var seedOutbox: [SeedMutation] = []
        #if DEBUG
        // E2E hooks (Debug builds only): `-force-offline` pins the monitor offline; `E2E_LOCAL_LISTS` is JSON
        // `{householdId: listName}` and seeds one local list per household so isolation is visible in the UI.
        forceOffline = CommandLine.arguments.contains("-force-offline")
        if let json = ProcessInfo.processInfo.environment["E2E_LOCAL_LISTS"],
            let map = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: String] {
            seedLists = Dictionary(uniqueKeysWithValues: map.compactMap { key, name in UUID(uuidString: key).map { ($0, name) } })
        }
        // `E2E_OUTBOX` is a JSON array of `{table, op, row_id?, data?}`, queued once when a household file is first created,
        // so a UI test has something pending without a list screen.
        if let json = ProcessInfo.processInfo.environment["E2E_OUTBOX"],
            let items = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]] {
            seedOutbox = items.compactMap { item in
                guard let table = item["table"] as? String else { return nil }
                let data = (item["data"] as? [String: Any]).flatMap {
                    try? JSONDecoder().decode([String: JSONValue].self, from: JSONSerialization.data(withJSONObject: $0))
                }
                return SeedMutation(
                    table: table, op: item["op"] as? String ?? "upsert",
                    rowId: (item["row_id"] as? String).flatMap { UUID(uuidString: $0) } ?? UUID(), data: data
                )
            }
        }
        #endif
        // Force-unwrap-free: Application Support always exists on iOS; fall back to the temporary directory.
        let root = (try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )) ?? FileManager.default.temporaryDirectory
        let api = HttpApiService(config: config, auth: auth)
        let network: NetworkMonitor = forceOffline ? NetworkMonitor(isOnline: false) : .live()
        let scheduler = SystemBackgroundSyncScheduler()
        let database = HouseholdDatabase(rootURL: root, seedLists: seedLists, seedOutbox: seedOutbox)
        return AppEnvironment(
            config: config,
            auth: auth,
            api: api,
            network: network,
            lastHousehold: LastHouseholdStore(),
            database: database,
            sync: LiveSyncEngine(
                transport: api, database: database, isOnline: { network.isOnline },
                // After each run, ask for the next background refresh (the system decides the real time).
                onRunFinished: { scheduler.scheduleNext() }
            ),
            backgroundSync: scheduler
        )
    }
}

extension EnvironmentValues {
    /// `nil` only in previews that do not set one up.
    @Entry var appEnvironment: AppEnvironment?
}
