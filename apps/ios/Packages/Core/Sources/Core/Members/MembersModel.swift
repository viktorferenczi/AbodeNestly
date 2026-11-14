import Contract
import Foundation
import Observation

/// DESIGN B6 list states (web `MembersView`).
public enum MembersLoadState: Equatable, Sendable {
    case loading
    case loaded
    /// Offline and nothing cached: "Connect to see the members."
    case offlineNoCache
    case error
}

/// A removal or leave waiting out the 5 s undo window; its row is hidden meanwhile.
public struct PendingRemoval: Equatable, Sendable {
    public let row: MemberRow
    public let leaving: Bool
}

/// Members screen logic: port of web `Members.ts` + the state in `Members.tsx`.
@Observable
@MainActor
public final class MembersModel {
    public static let undoDelay: Duration = .seconds(5)
    public static let lastOwnerMessage = "You're the last owner. Make someone else an owner before leaving."

    public private(set) var rows: [MemberRow] = []
    public private(set) var loadState: MembersLoadState = .loading
    public private(set) var pending: PendingRemoval?
    /// "Removed <name>" or "Leaving household" while the undo window is open.
    public private(set) var toast: String?
    /// Last-owner block or a failure message; the view shows it as an alert and clears it.
    public var alert: String?
    /// Bumped when a removal lands; a fetch that started earlier must not bring the member back.
    public private(set) var generation = 0

    public let householdId: UUID
    public let meId: UUID
    @ObservationIgnored private let api: any ApiService
    @ObservationIgnored private let cache: MembersCache
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let onLeft: @MainActor () -> Void
    /// The undo-window timer; non-nil exactly while the DELETE has not been sent yet.
    @ObservationIgnored private var waiting: Task<Void, Never>?

    /// `sleep` is injected so tests cross the undo window without waiting (same shape as `SignInModel`).
    public init(
        householdId: UUID, meId: UUID, api: any ApiService, cache: MembersCache = MembersCache(),
        isOnline: @escaping @MainActor () -> Bool = { true },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        onLeft: @escaping @MainActor () -> Void
    ) {
        self.householdId = householdId
        self.meId = meId
        self.api = api
        self.cache = cache
        self.isOnline = isOnline
        self.sleep = sleep
        self.onLeft = onLeft
    }

    // MARK: Derived

    /// Rows to render: sorted, with the pending removal hidden.
    public var visibleRows: [MemberRow] {
        Self.sortMembers(rows.filter { $0.id != pending?.row.id })
    }

    public var role: Member.Role? { Self.viewerRole(rows, meId: meId) }

    public var isBusy: Bool { pending != nil }

    // MARK: Loading

    /// Live list when online; the cached list when offline or the read fails.
    public func load() async {
        let started = generation
        guard isOnline() else { return fromCache() }
        let result = await api.fetchMembers(householdId: householdId)
        guard started == generation else { return }
        switch result {
        case .success(let fetched): show(fetched)
        case .failure: fromCache()
        }
    }

    private func show(_ fetched: [MemberRow]) {
        rows = fetched
        loadState = .loaded
        cache.write(fetched, householdId: householdId)
    }

    private func fromCache() {
        if let cached = cache.read(householdId: householdId) {
            rows = cached
            loadState = .loaded
        } else if loadState != .loaded {
            // A list already on screen stays; only a first load can end without one.
            loadState = isOnline() ? .error : .offlineNoCache
        }
    }

    // MARK: Actions (all no-ops offline or while a removal is pending)

    public func remove(_ row: MemberRow) {
        guard isOnline(), pending == nil, Self.canRemove(role: role, target: row, meId: meId) else { return }
        start(row, leaving: false)
    }

    /// AC4: the only owner is blocked before any call is made.
    public func leave() {
        guard isOnline(), pending == nil, loadState == .loaded,
            let me = rows.first(where: { $0.id == meId })
        else { return }
        if Self.isLastOwner(rows, meId: meId) {
            alert = Self.lastOwnerMessage
            return
        }
        start(me, leaving: true)
    }

    /// AC3: Undo inside the window means DELETE is never sent.
    public func undo() {
        guard let timer = waiting else { return }
        timer.cancel()
        waiting = nil
        pending = nil
        toast = nil
    }

    /// The toast said "Removed", so leaving the screen does not cancel it: Undo is the only cancel.
    public func flushPending() {
        guard let timer = waiting else { return }
        timer.cancel()
        Task { await self.commit() }
    }

    public func makeOwner(_ row: MemberRow) async {
        guard isOnline(), pending == nil, role == .owner, row.member.role != .owner else { return }
        switch await api.makeOwner(householdId: householdId, userId: row.id) {
        case .success:
            alert = nil
            await load()
        case .failure:
            alert = "Couldn't make \(Self.displayName(row)) an owner. Try again."
        }
    }

    private func start(_ row: MemberRow, leaving: Bool) {
        alert = nil
        pending = PendingRemoval(row: row, leaving: leaving)
        toast = leaving ? "Leaving household" : "Removed \(Self.displayName(row))"
        let sleep = self.sleep
        waiting = Task { [weak self] in
            do { try await sleep(Self.undoDelay) } catch { return }
            await self?.commit()
        }
    }

    /// Sends the DELETE. Runs once per removal: the timer and `flushPending` can both reach it.
    private func commit() async {
        guard waiting != nil, let removal = pending else { return }
        waiting = nil
        toast = nil
        let name = removal.leaving ? "yourself" : Self.displayName(removal.row)
        switch await api.removeMember(householdId: householdId, userId: removal.row.id) {
        case .success, .failure(.notFound):
            // Already gone counts as done: the row should disappear either way.
            generation += 1
            pending = nil
            if removal.leaving {
                onLeft()
            } else {
                rows.removeAll { $0.id == removal.row.id }
                cache.write(rows, householdId: householdId)
            }
        case .failure(let failure):
            pending = nil
            switch failure {
            case .lastOwner: alert = Self.lastOwnerMessage
            case .forbidden: alert = "You don't have permission to remove \(name)."
            default:
                alert = removal.leaving
                    ? "Couldn't leave the household. Try again." : "Couldn't remove \(name). Try again."
            }
            // The list the person acted on may be stale (roles changed elsewhere); show what the server has now.
            await load()
        }
    }

    // MARK: Pure helpers (port of web `Members.ts`)

    /// The profile name, or "Member" when the profile is hidden or empty.
    public static func displayName(_ row: MemberRow) -> String {
        let name = row.profile?.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "Member" : name
    }

    /// Owners first, then by name; ties keep input order.
    public static func sortMembers(_ rows: [MemberRow]) -> [MemberRow] {
        func rank(_ row: MemberRow) -> Int { row.member.role == .owner ? 0 : 1 }
        return rows.enumerated().sorted { a, b in
            if rank(a.element) != rank(b.element) { return rank(a.element) < rank(b.element) }
            switch displayName(a.element).localizedCaseInsensitiveCompare(displayName(b.element)) {
            case .orderedAscending: return true
            case .orderedDescending: return false
            case .orderedSame: return a.offset < b.offset
            }
        }.map(\.element)
    }

    /// "Anna Kovacs" → "AK"; a single name gives one letter; nothing gives "?".
    public static func initials(_ displayName: String) -> String {
        let words = displayName.split(whereSeparator: \.isWhitespace)
        guard let first = words.first?.first else { return "?" }
        let last = words.count > 1 ? words.last?.first.map(String.init) ?? "" : ""
        return (String(first) + last).uppercased()
    }

    public static func viewerRole(_ rows: [MemberRow], meId: UUID) -> Member.Role? {
        rows.first { $0.id == meId }?.member.role
    }

    /// True when the viewer is an owner and no one else is.
    public static func isLastOwner(_ rows: [MemberRow], meId: UUID) -> Bool {
        guard viewerRole(rows, meId: meId) == .owner else { return false }
        return !rows.contains { $0.member.role == .owner && $0.id != meId }
    }

    /// Only owners remove others; leaving yourself is the separate Leave action.
    public static func canRemove(role: Member.Role?, target: MemberRow, meId: UUID) -> Bool {
        role == .owner && target.id != meId
    }
}
