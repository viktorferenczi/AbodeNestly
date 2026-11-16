import Contract
import Foundation
import Observation
import Sync

/// App-wide auth state: bootstraps from the stored session (AC6), follows session changes, loads the
/// profile (AC8) and resolves where to land (AC4/AC5). Port of web `AuthProvider` + `Landing`.
@Observable
@MainActor
public final class SessionModel {
    public enum Status: Equatable, Sendable { case loading, signedIn, signedOut }

    public private(set) var status: Status = .loading
    public private(set) var profile: Profile?
    /// `nil` while memberships are loading.
    public private(set) var destination: Destination? {
        // The tab bar binds to `selectedTab`; a new landing (load, create, join) starts on its tab, and a switch
        // passes the kept tab through here, so the selection survives the destination change (CORE-07 AC2).
        didSet {
            switch destination {
            case .household(_, let tab)?: selectedTab = tab
            case .home?: selectedTab = .home
            case .welcome?, nil: break
            }
        }
    }
    /// The selected bottom tab.
    public var selectedTab: AppTab = .home
    /// Name of the household being switched to; non-`nil` for the whole switch (blocks a second one).
    public private(set) var switching: String?
    /// Name of the current household, from the cached household list; `nil` until it is known.
    public private(set) var householdName: String?
    /// A long-press in the switcher asks Home to push Settings (DESIGN B3 → B5); Home clears it.
    public var settingsRequested = false
    /// The module flags of the household in `destination`, for tab filtering.
    public private(set) var household: LandingHousehold?
    /// Online, memberships could not be read: a blocking error with retry.
    public private(set) var loadFailed = false

    @ObservationIgnored private let auth: any AuthService
    @ObservationIgnored private let lastHousehold: LastHouseholdStore
    @ObservationIgnored private let profileCache: ProfileCache
    @ObservationIgnored private let profileOutbox: ProfileOutbox
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let database: HouseholdDatabase?
    @ObservationIgnored private let householdsCache: HouseholdsCache
    @ObservationIgnored private let api: (any ApiService)?
    /// The signed-in user; `nil` when signed out.
    public private(set) var userId: UUID?
    /// The sign-in email from the auth session (not stored on the profile row); `nil` when signed out or hidden by the provider.
    public private(set) var email: String?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    /// A household just joined: the next load lands on its Home tab instead of its first module (DESIGN B2).
    @ObservationIgnored private var landOnHome: UUID?

    public init(
        auth: any AuthService,
        lastHousehold: LastHouseholdStore,
        profileCache: ProfileCache = ProfileCache(),
        profileOutbox: ProfileOutbox = ProfileOutbox(),
        database: HouseholdDatabase? = nil,
        householdsCache: HouseholdsCache = HouseholdsCache(),
        api: (any ApiService)? = nil,
        isOnline: @escaping @MainActor () -> Bool
    ) {
        self.database = database
        self.householdsCache = householdsCache
        self.api = api
        self.auth = auth
        self.lastHousehold = lastHousehold
        self.profileCache = profileCache
        self.profileOutbox = profileOutbox
        self.isOnline = isOnline
    }

    /// Reads the stored session, then follows changes for the life of the app. Run from `.task`.
    public func start() async {
        apply(await auth.currentSession())
        for await session in auth.sessionChanges() {
            apply(session)
        }
    }

    public func signOut() async {
        await auth.signOut()
    }

    /// The profile screen saved an edit locally: the TopBar and Welcome show it at once.
    public func setLocalProfile(_ updated: Profile) {
        guard updated.id == userId else { return }
        profile = updated
    }

    public func retry() {
        guard let userId else { return }
        load(for: userId)
    }

    /// Resolves once the profile and landing destination for the current session are in.
    public func waitUntilLoaded() async {
        await loadTask?.value
    }

    /// A household was just created (DESIGN B2 → B4): remember it and open its Home tab.
    public func enter(_ household: LandingHousehold) {
        guard let database else { publishEntered(household); return }
        let userId = self.userId
        // The file opens before the household is published; a failed open leaves what was shown in place.
        Task {
            let opened: Bool
            do { try await database.open(householdId: household.id); opened = true } catch { opened = false }
            guard self.userId == userId else { return }
            if opened { publishEntered(household) } else { loadFailed = true }
        }
    }

    private func publishEntered(_ household: LandingHousehold) {
        lastHousehold.write(household.id)
        self.household = household
        loadFailed = false
        updateHouseholdName(for: household.id)
        destination = .household(id: household.id, tab: .home)
    }

    /// Settings changed the module flags of the current household: the tab bar follows on the next render.
    public func updateHousehold(_ updated: LandingHousehold) {
        guard household?.id == updated.id else { return }
        household = updated
        updateHouseholdName(for: updated.id)
    }

    /// The household the person is in: the landed one, or (offline, memberships unknown) the last one used.
    public var currentHouseholdId: UUID? {
        if case .household(let id, _)? = destination { return id }
        return lastHousehold.read()
    }

    public enum SwitchError: Error, Equatable, Sendable {
        /// The target is not in the cached household list.
        case unknownHousehold
        /// The target's database could not be opened.
        case openFailed
        /// The person signed out (or changed) while the switch ran.
        case sessionEnded
    }

    /// Switches households (CORE-07): closes the current database, opens the target's, keeps the selected tab
    /// (or the target's start tab when that module is off there) and lands there. Reads only the cached household
    /// list, so it works offline (AC5). If the target cannot be opened the previous database is reopened and the
    /// error is thrown. `openSettings` then routes to Home → Settings (DESIGN B3 long-press).
    public func switchTo(_ id: UUID, openSettings: Bool = false) async throws {
        guard let userId, switching == nil else { return }
        let previous = currentHouseholdId
        if id == previous {
            if openSettings { requestSettings() }
            return
        }
        guard let target = householdsCache.read(userId: userId).first(where: { $0.household.id == id }) else {
            throw SwitchError.unknownHousehold
        }
        // Set before the first await so a second tap cannot start another switch.
        switching = target.household.name
        defer { switching = nil }
        if let database {
            do {
                try await database.open(householdId: id)
            } catch {
                if let previous { _ = try? await database.open(householdId: previous) }
                throw SwitchError.openFailed
            }
        }
        // Signed out meanwhile: write nothing for the next account, and fail so the sheet stays open.
        guard self.userId == userId else { throw SwitchError.sessionEnded }
        let landing = Self.landing(target.household)
        lastHousehold.write(id)
        household = landing
        householdName = target.household.name
        destination = .household(id: id, tab: Switcher.targetTab(current: selectedTab, target: landing))
        loadFailed = false
        if openSettings { requestSettings() }
    }

    private func requestSettings() {
        selectedTab = .home
        settingsRequested = true
    }

    private static func landing(_ household: Household) -> LandingHousehold {
        LandingHousehold(
            id: household.id, moduleShopping: household.moduleShopping,
            modulePantry: household.modulePantry, moduleMaintenance: household.moduleMaintenance
        )
    }

    private func updateHouseholdName(for id: UUID?) {
        guard let userId, let id else { householdName = nil; return }
        householdName = householdsCache.read(userId: userId).first { $0.household.id == id }?.household.name
    }

    /// Online: reads the household list once so the TopBar name and the switcher have it (the memberships read
    /// carries only module flags).
    private func primeHouseholds(for userId: UUID) async {
        guard let api, isOnline() else { return }
        if case .success(let list) = await api.fetchMyHouseholds(userId: userId), self.userId == userId {
            householdsCache.write(list, userId: userId)
            updateHouseholdName(for: currentHouseholdId)
        }
    }

    /// A household was just joined: reload memberships (the module flags are not in the accept response)
    /// and land on that household's Home tab.
    public func enterJoined(householdId: UUID) {
        guard let userId else { return }
        lastHousehold.write(householdId)
        landOnHome = householdId
        load(for: userId)
    }

    /// The person left their household (CORE-04): forget it and re-resolve where to land (another household, or Welcome).
    public func leftHousehold() {
        lastHousehold.clear()
        guard let userId else { return }
        load(for: userId)
    }

    private func apply(_ session: AuthSession?) {
        guard let session else {
            if let userId { householdsCache.clear(userId: userId) }
            Task { [database] in await database?.close() }
            householdName = nil
            userId = nil
            email = nil
            loadTask?.cancel()
            status = .signedOut
            profile = nil
            destination = nil
            household = nil
            loadFailed = false
            return
        }
        status = .signedIn
        email = session.email
        // Token refreshes re-emit the same user; do not reload or bounce the landing.
        guard userId != session.userId else { return }
        userId = session.userId
        load(for: session.userId)
    }

    private func load(for userId: UUID) {
        loadTask?.cancel()
        destination = nil
        household = nil
        loadFailed = false
        loadTask = Task {
            async let profileResult = auth.fetchProfile(userId: userId)
            async let membershipsResult = auth.fetchMemberships(userId: userId)
            let (profileResult2, membershipsResult2) = await (profileResult, membershipsResult)
            guard !Task.isCancelled, self.userId == userId else { return }
            // A missing row (trigger not run yet) leaves the name empty; a failed read falls back to the cache.
            // Unsynced edits are overlaid so a refetch does not revert them.
            let pending = profileOutbox.read(userId: userId)
            switch profileResult2 {
            case .success(let value):
                profile = value.map { ProfileEditing.withPending($0, pending) }
                if let profile { profileCache.write(profile, userId: userId) }
            case .failure:
                // Offline reads fail; show the last known name.
                profile = profileCache.read(userId: userId).map { ProfileEditing.withPending($0, pending) }
            }
            switch membershipsResult2 {
            case .success(let memberships):
                let lastId = lastHousehold.read()
                if let chosen = Landing.pickHousehold(memberships, lastHouseholdId: lastId) {
                    do { try await database?.open(householdId: chosen.householdId) } catch {
                        // Never publish a household whose file is not open.
                        guard !Task.isCancelled, self.userId == userId else { return }
                        loadFailed = true
                        return
                    }
                    guard !Task.isCancelled, self.userId == userId else { return }
                    lastHousehold.write(chosen.householdId)
                } else {
                    await database?.close()
                    guard !Task.isCancelled, self.userId == userId else { return }
                }
                household = Landing.pickHousehold(memberships, lastHouseholdId: lastId)?.household
                destination = Landing.destination(memberships: memberships, lastHouseholdId: lastId)
                updateHouseholdName(for: household?.id)
                if let joined = landOnHome, case .household(let id, _) = destination, id == joined {
                    destination = .household(id: id, tab: .home)
                    landOnHome = nil
                }
                await primeHouseholds(for: userId)
            case .failure:
                // Offline, an existing session stays usable (SPEC §6.5): enter the shell at Home.
                // Online, a failure is a real problem and stays a blocking error.
                if isOnline() {
                    loadFailed = true
                } else {
                    // The last household's database still opens; its name comes from the cached list.
                    if let last = lastHousehold.read() {
                        do { try await database?.open(householdId: last) } catch {
                            guard !Task.isCancelled, self.userId == userId else { return }
                            loadFailed = true
                            return
                        }
                    }
                    guard !Task.isCancelled, self.userId == userId else { return }
                    updateHouseholdName(for: lastHousehold.read())
                    destination = .home
                }
            }
        }
    }
}
