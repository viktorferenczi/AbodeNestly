import Contract
import Foundation
import Observation
import Sync

/// One line of the B3 sheet.
public struct SwitcherRow: Equatable, Identifiable, Sendable {
    public let household: Household
    public let role: Member.Role
    public let isCurrent: Bool
    /// Queued writes of that household's local database.
    public let pending: Int

    public var id: UUID { household.id }
    /// Lowercase, like every other id the API and the tests handle.
    public var elementId: String { "switcher.row.\(household.id.uuidString.lowercased())" }
}

/// DESIGN B3. Rows start from the cached list (works offline, AC5), refresh from the API when online, and carry
/// the pending-sync count of each household's local database.
@Observable
@MainActor
public final class SwitcherModel {
    public static let switchErrorText = "Couldn't switch households. Try again."

    public private(set) var rows: [SwitcherRow]
    public private(set) var switchingName: String?
    public private(set) var errorText: String?

    public var state: SwitcherState { Switcher.state(householdCount: rows.count, switchingTo: switchingName) }

    @ObservationIgnored private let userId: UUID
    @ObservationIgnored private let currentId: UUID?
    @ObservationIgnored private let current: HouseholdWithRole?
    @ObservationIgnored private let api: any ApiService
    @ObservationIgnored private let cache: HouseholdsCache
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let perform: @MainActor (UUID, Bool) async throws -> Void
    @ObservationIgnored private var entries: [HouseholdWithRole]
    @ObservationIgnored private var pending: [UUID: Int] = [:]
    @ObservationIgnored private var pendingLoad: Task<Void, Never>?

    /// - Parameters:
    ///   - current: the open household, prepended when the cached list lacks it.
    ///   - perform: switches households (`SessionModel.switchTo`); the `Bool` asks to land on Settings.
    public init(
        userId: UUID, currentId: UUID?, current: HouseholdWithRole?, api: any ApiService, cache: HouseholdsCache,
        database: HouseholdDatabase, isOnline: @escaping @MainActor () -> Bool,
        perform: @escaping @MainActor (UUID, Bool) async throws -> Void
    ) {
        self.userId = userId
        self.currentId = currentId
        self.current = current
        self.api = api
        self.cache = cache
        self.database = database
        self.isOnline = isOnline
        self.perform = perform
        // Only the cached seed gets the current household prepended; a refresh shows what the server says.
        entries = Self.merge(cache.read(userId: userId), current: current)
        rows = []
        rebuild()
    }

    /// Cached rows are already there; counts first, then the online refresh, then counts for any new rows.
    public func load() async {
        await loadPending()
        guard isOnline() else { return }
        if case .success(let list) = await api.fetchMyHouseholds(userId: userId) {
            cache.write(list, userId: userId)
            entries = Self.merge(list, current: nil)
            rebuild()
            await loadPending()
        }
    }

    /// Row tap. Returns `true` when the sheet should close (switched, or it was the current household).
    public func select(_ id: UUID) async -> Bool {
        await switchTo(id, openSettings: false)
    }

    /// Row long-press: switch if needed, then land on Home → Settings (B5).
    public func openSettings(_ id: UUID) async -> Bool {
        await switchTo(id, openSettings: true)
    }

    private func switchTo(_ id: UUID, openSettings: Bool) async -> Bool {
        guard switchingName == nil else { return false }
        if id == currentId && !openSettings { return true }
        guard let target = entries.first(where: { $0.household.id == id }) else { return false }
        errorText = nil
        switchingName = target.household.name
        defer { switchingName = nil }
        // A count still reading the target's file must finish before that file is opened for the switch.
        await pendingLoad?.value
        do {
            try await perform(id, openSettings)
            return true
        } catch {
            errorText = Self.switchErrorText
            return false
        }
    }

    private func loadPending() async {
        let ids = entries.map(\.household.id)
        let database = database
        let task = Task { [weak self] in
            var counts: [UUID: Int] = [:]
            for id in ids { counts[id] = (try? await database.pendingCount(householdId: id)) ?? 0 }
            self?.pending = counts
            self?.rebuild()
        }
        pendingLoad = task
        await task.value
    }

    private func rebuild() {
        rows = entries.map {
            SwitcherRow(household: $0.household, role: $0.role, isCurrent: $0.household.id == currentId, pending: pending[$0.household.id] ?? 0)
        }
    }

    private static func merge(_ list: [HouseholdWithRole], current: HouseholdWithRole?) -> [HouseholdWithRole] {
        var list = list
        if let current, !list.contains(where: { $0.household.id == current.household.id }) { list.insert(current, at: 0) }
        return list.sorted { $0.household.name.localizedCaseInsensitiveCompare($1.household.name) == .orderedAscending }
    }
}
