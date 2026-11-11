import Contract
import Foundation
import Observation

/// App-wide auth state: bootstraps from the stored session (AC6), follows session changes, loads the
/// profile (AC8) and resolves where to land (AC4/AC5). Port of web `AuthProvider` + `Landing`.
@Observable
@MainActor
public final class SessionModel {
    public enum Status: Equatable, Sendable { case loading, signedIn, signedOut }

    public private(set) var status: Status = .loading
    public private(set) var profile: Profile?
    /// `nil` while memberships are loading.
    public private(set) var destination: Destination?
    /// The module flags of the household in `destination`, for tab filtering.
    public private(set) var household: LandingHousehold?
    /// Online, memberships could not be read: a blocking error with retry.
    public private(set) var loadFailed = false

    @ObservationIgnored private let auth: any AuthService
    @ObservationIgnored private let lastHousehold: LastHouseholdStore
    @ObservationIgnored private let profileCache: ProfileCache
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private var userId: UUID?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    /// A household just joined: the next load lands on its Home tab instead of its first module (DESIGN B2).
    @ObservationIgnored private var landOnHome: UUID?

    public init(
        auth: any AuthService,
        lastHousehold: LastHouseholdStore,
        profileCache: ProfileCache = ProfileCache(),
        isOnline: @escaping @MainActor () -> Bool
    ) {
        self.auth = auth
        self.lastHousehold = lastHousehold
        self.profileCache = profileCache
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
        lastHousehold.write(household.id)
        self.household = household
        destination = .household(id: household.id, tab: .home)
        loadFailed = false
    }

    /// A household was just joined: reload memberships (the module flags are not in the accept response)
    /// and land on that household's Home tab.
    public func enterJoined(householdId: UUID) {
        guard let userId else { return }
        lastHousehold.write(householdId)
        landOnHome = householdId
        load(for: userId)
    }

    private func apply(_ session: AuthSession?) {
        guard let session else {
            userId = nil
            loadTask?.cancel()
            status = .signedOut
            profile = nil
            destination = nil
            household = nil
            loadFailed = false
            return
        }
        status = .signedIn
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
            switch profileResult2 {
            case .success(let value):
                profile = value
                if let value { profileCache.write(value, userId: userId) }
            case .failure:
                // Offline reads fail; show the last known name.
                profile = profileCache.read(userId: userId)
            }
            switch membershipsResult2 {
            case .success(let memberships):
                let lastId = lastHousehold.read()
                if let chosen = Landing.pickHousehold(memberships, lastHouseholdId: lastId) {
                    lastHousehold.write(chosen.householdId)
                }
                household = Landing.pickHousehold(memberships, lastHouseholdId: lastId)?.household
                destination = Landing.destination(memberships: memberships, lastHouseholdId: lastId)
                if let joined = landOnHome, case .household(let id, _) = destination, id == joined {
                    destination = .household(id: id, tab: .home)
                    landOnHome = nil
                }
            case .failure:
                // Offline, an existing session stays usable (SPEC §6.5): enter the shell at Home.
                // Online, a failure is a real problem and stays a blocking error.
                if isOnline() { loadFailed = true } else { destination = .home }
            }
        }
    }
}
