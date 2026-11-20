import Foundation
import Observation
import Sync
import UserNotifications

/// DESIGN B9. Reads and writes the local database only; the change reaches the server through the outbox.
/// `authorization` is injected so tests do not touch the system permission.
@Observable
@MainActor
public final class NotificationPrefsModel {
    /// The flags of the household; `nil` when unknown, which shows only Activity.
    public var modules: HomeModules?
    /// `false` until the first read, so the switches do not flash their defaults.
    public private(set) var loaded = false
    public private(set) var status: UNAuthorizationStatus = .notDetermined
    /// Set when a change could not be written.
    public private(set) var saveFailed = false

    private var rows: [NotificationPrefRow] = []
    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let userId: UUID
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let kick: @Sendable () async -> Void
    @ObservationIgnored private let authorization: @MainActor () async -> UNAuthorizationStatus
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private var observeTask: Task<Void, Never>?

    public init(
        householdId: UUID, userId: UUID, modules: HomeModules?, database: HouseholdDatabase,
        kick: @escaping @Sendable () async -> Void,
        authorization: @escaping @MainActor () async -> UNAuthorizationStatus = {
            await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        },
        now: @escaping @MainActor () -> Date = { Date() }
    ) {
        self.householdId = householdId
        self.userId = userId
        self.modules = modules
        self.database = database
        self.kick = kick
        self.authorization = authorization
        self.now = now
        startObserving()
    }

    public var types: [NotificationType] { NotificationPrefs.visibleTypes(modules) }
    public var permissionDenied: Bool { status == .denied }
    public func isOn(_ type: NotificationType) -> Bool { NotificationPrefs.enabled(rows, type) }

    private func startObserving() {
        let (householdId, userId, database) = (householdId, userId, database)
        observeTask = Task { [weak self] in
            for await found in await database.observeNotificationPrefs(householdId: householdId, userId: userId) {
                guard let self else { return }
                self.rows = found
                self.loaded = true
            }
            // A stream that ended at once (database not open): the defaults are all there is.
            self?.loaded = true
        }
    }

    /// The person may change the system setting and come back, so the view calls this on every foreground.
    public func refreshAuthorization() async {
        status = await authorization()
    }

    /// Switch tapped: one local write (row + outbox), then ask sync to push it.
    public func set(_ type: NotificationType, enabled: Bool) async {
        do {
            try await database.setNotificationPref(
                userId: userId, householdId: householdId, type: type.rawValue, enabled: enabled, now: now()
            )
            saveFailed = false
        } catch {
            saveFailed = true
            return
        }
        await kick()
    }
}
