import Contract
import Foundation
@testable import Sync
import Testing
@testable import Core

func switcherTempRoot() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("switcher-\(UUID().uuidString)", isDirectory: true)
}

@Suite("CORE-07 household database")
struct HouseholdDatabaseTests {
    let root = switcherTempRoot()
    let a = UUID()
    let b = UUID()

    @Test("CORE-07: AC2 opening another household changes the file and closes the old pool")
    func switchesFile() async throws {
        let db = HouseholdDatabase(rootURL: root)
        let first = try await db.open(householdId: a)
        #expect(await db.current == a)
        #expect(first.fileName == "\(a.uuidString.lowercased()).sqlite")
        #expect(first.isUsable)

        let second = try await db.open(householdId: b)

        #expect(await db.current == b)
        #expect(second.fileName == db.fileName(for: b))
        #expect(second.fileName != first.fileName)
        #expect(!first.isUsable)
        #expect(second.isUsable)
        let files = try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("households").path)
        #expect(files.contains(first.fileName) && files.contains(second.fileName))
    }

    @Test("CORE-07: close releases the pool")
    func closes() async throws {
        let db = HouseholdDatabase(rootURL: root)
        let handle = try await db.open(householdId: a)
        await db.close()
        #expect(await db.current == nil)
        #expect(!handle.isUsable)
        await #expect(throws: HouseholdDatabaseError.notOpen) { try await db.readCursor() }
    }

    @Test("CORE-07: AC4 a row inserted in A is not visible in B, and is back after switching to A")
    func isolation() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: a)
        try await db.insertList(ShoppingListRow(householdId: a, name: "Alpha list"))
        #expect(try await db.lists().map(\.name) == ["Alpha list"])

        try await db.open(householdId: b)
        #expect(try await db.lists().isEmpty)

        try await db.open(householdId: a)
        #expect(try await db.lists().map(\.name) == ["Alpha list"])
    }

    @Test("CORE-07: the cursor seeds at 0, survives a reopen and is separate per household")
    func cursor() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: a)
        #expect(try await db.readCursor() == 0)
        try await db.writeCursor(42)

        try await db.open(householdId: b)
        #expect(try await db.readCursor() == 0)

        await db.close()
        try await db.open(householdId: a)
        #expect(try await db.readCursor() == 42)
        // Reopening the current household does not reseed.
        try await db.open(householdId: a)
        #expect(try await db.readCursor() == 42)
    }

    @Test("CORE-07: pendingCount works on the open database and on a closed one")
    func pendingCount() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: a)
        try await db.enqueueForTesting()
        try await db.enqueueForTesting()
        try await db.open(householdId: b)
        try await db.enqueueForTesting()

        // b is open, a is closed.
        #expect(try await db.pendingCount(householdId: b) == 1)
        #expect(try await db.pendingCount(householdId: a) == 2)
        // The short-lived connection did not disturb the open one.
        #expect(await db.current == b)
        #expect(try await db.readCursor() == 0)
        // Never opened: nothing queued, and no file is created by asking.
        let never = UUID()
        #expect(try await db.pendingCount(householdId: never) == 0)
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("households/\(db.fileName(for: never))").path))
    }

    @Test("CORE-07: E2E seed inserts one list per household on first open only")
    func seed() async throws {
        let db = HouseholdDatabase(rootURL: root, seedLists: [a: "Alpha list"])
        try await db.open(householdId: a)
        try await db.open(householdId: b)
        try await db.open(householdId: a)
        #expect(try await db.lists().map(\.name) == ["Alpha list"])
    }
}

@Suite("CORE-07 switch logic")
struct SwitcherLogicTests {
    private func household(shopping: Bool = true, pantry: Bool = true, maintenance: Bool = true) -> LandingHousehold {
        LandingHousehold(id: UUID(), moduleShopping: shopping, modulePantry: pantry, moduleMaintenance: maintenance)
    }

    @Test("CORE-07: AC2 targetTab keeps the tab, or falls back to the start tab when that module is off")
    func targetTab() {
        let all = household()
        let noPantry = household(pantry: false)
        let onlyMaintenance = household(shopping: false, pantry: false)
        #expect(Switcher.targetTab(current: .shopping, target: all) == .shopping)
        #expect(Switcher.targetTab(current: .pantry, target: noPantry) == .shopping)
        #expect(Switcher.targetTab(current: .shopping, target: onlyMaintenance) == .maintenance)
        #expect(Switcher.targetTab(current: .home, target: noPantry) == .home)
        #expect(Switcher.targetTab(current: .maintenance, target: household(shopping: false, pantry: false, maintenance: false)) == .home)
    }

    @Test("CORE-07: state is switching, many or one-household")
    func state() {
        #expect(Switcher.state(householdCount: 3, switchingTo: "Beta") == .switching("Beta"))
        #expect(Switcher.state(householdCount: 2, switchingTo: nil) == .many)
        #expect(Switcher.state(householdCount: 1, switchingTo: nil) == .oneHousehold)
        #expect(Switcher.state(householdCount: 0, switchingTo: nil) == .oneHousehold)
    }
}

@MainActor
struct SwitchHarness {
    let root = switcherTempRoot()
    let db: HouseholdDatabase
    let user = AuthSession(userId: UUID(), email: "ada@test.local")
    let suite = "SwitcherTests-\(UUID())"
    let auth: FakeAuthService
    let cache: HouseholdsCache
    let session: SessionModel
    let alpha = makeHousehold(id: UUID(), name: "Alpha")
    var beta = makeHousehold(id: UUID(), name: "Beta")
    var gamma = makeHousehold(id: UUID(), name: "Gamma")

    /// Alpha (the earliest, all modules) is the landing household; Beta has Pantry off.
    init(online: Bool = true) {
        db = HouseholdDatabase(rootURL: root)
        auth = FakeAuthService(session: user)
        cache = HouseholdsCache(suiteName: suite)
        beta.modulePantry = false
        session = SessionModel(
            auth: auth, lastHousehold: LastHouseholdStore(suiteName: suite), profileCache: ProfileCache(suiteName: suite),
            database: db, householdsCache: cache, isOnline: { online }
        )
        cache.write([
            HouseholdWithRole(household: alpha, role: .owner),
            HouseholdWithRole(household: beta, role: .member),
            HouseholdWithRole(household: gamma, role: .member),
        ], userId: user.userId)
        auth.setMemberships(.success([alpha, beta, gamma].enumerated().map { index, h in
            Membership(
                householdId: h.id, joinedAt: Date(timeIntervalSince1970: Double(index)),
                household: LandingHousehold(
                    id: h.id, moduleShopping: h.moduleShopping, modulePantry: h.modulePantry, moduleMaintenance: h.moduleMaintenance
                )
            )
        }))
    }

    func start() async -> Task<Void, Never> {
        let started = Task { await session.start() }
        while session.status == .loading { await Task.yield() }
        await session.waitUntilLoaded()
        return started
    }
}

@MainActor
@Suite("CORE-07 session switching")
struct SessionSwitchTests {
    @Test("CORE-07: load opens the landing household's database and knows its name")
    func loadOpensDatabase() async {
        let h = SwitchHarness()
        let started = await h.start()
        #expect(await h.db.current == h.alpha.id)
        #expect(h.session.householdName == "Alpha")
        started.cancel()
    }

    @Test("CORE-07: AC2 switching keeps the selected tab and moves the database")
    func keepsTab() async throws {
        let h = SwitchHarness()
        let started = await h.start()
        h.session.selectedTab = .shopping

        try await h.session.switchTo(h.beta.id)

        #expect(h.session.destination == .household(id: h.beta.id, tab: .shopping))
        #expect(h.session.selectedTab == .shopping)
        #expect(h.session.householdName == "Beta")
        #expect(h.session.household?.id == h.beta.id)
        #expect(await h.db.current == h.beta.id)
        #expect(LastHouseholdStore(suiteName: h.suite).read() == h.beta.id)
        #expect(h.session.switching == nil)
        started.cancel()
    }

    @Test("CORE-07: AC2 the tab falls back to the start tab when its module is off in the target")
    func fallsBack() async throws {
        let h = SwitchHarness()
        let started = await h.start()
        h.session.selectedTab = .pantry

        try await h.session.switchTo(h.beta.id)

        #expect(h.session.selectedTab == .shopping)
        #expect(h.session.destination == .household(id: h.beta.id, tab: .shopping))
        started.cancel()
    }

    @Test("CORE-07: switching to the current household does nothing")
    func sameHousehold() async throws {
        let h = SwitchHarness()
        let started = await h.start()
        h.session.selectedTab = .pantry
        let before = h.session.destination
        try await h.session.switchTo(h.alpha.id)
        #expect(h.session.destination == before)
        #expect(h.session.selectedTab == .pantry)
        #expect(!h.session.settingsRequested)
        started.cancel()
    }

    @Test("CORE-07: a second switch while one is running is ignored")
    func doubleSwitch() async throws {
        let h = SwitchHarness()
        let started = await h.start()
        async let first: Void = h.session.switchTo(h.beta.id)
        async let second: Void = h.session.switchTo(h.gamma.id)
        _ = try await (first, second)
        #expect(h.session.destination == .household(id: h.beta.id, tab: h.session.selectedTab))
        #expect(await h.db.current == h.beta.id)
        started.cancel()
    }

    @Test("CORE-07: a failing open restores the previous database and rethrows")
    func restoresOnFailure() async throws {
        let h = SwitchHarness()
        let started = await h.start()
        // A directory where the file should be makes the open fail.
        try FileManager.default.createDirectory(
            at: h.root.appendingPathComponent("households/\(h.db.fileName(for: h.beta.id))"), withIntermediateDirectories: true
        )

        await #expect(throws: SessionModel.SwitchError.openFailed) { try await h.session.switchTo(h.beta.id) }

        #expect(await h.db.current == h.alpha.id)
        #expect(h.session.destination == .household(id: h.alpha.id, tab: .shopping))
        #expect(h.session.householdName == "Alpha")
        #expect(h.session.switching == nil)
        started.cancel()
    }

    @Test("CORE-07: a household that is not in the cached list is refused")
    func unknown() async {
        let h = SwitchHarness()
        let started = await h.start()
        await #expect(throws: SessionModel.SwitchError.unknownHousehold) { try await h.session.switchTo(UUID()) }
        started.cancel()
    }

    @Test("CORE-07: long-press routing lands on Home with Settings requested")
    func opensSettings() async throws {
        let h = SwitchHarness()
        let started = await h.start()
        try await h.session.switchTo(h.beta.id, openSettings: true)
        #expect(h.session.selectedTab == .home)
        #expect(h.session.settingsRequested)
        started.cancel()
    }

    @Test("CORE-07: AC5 the switch completes while the API is failing")
    func offlineSwitch() async throws {
        let h = SwitchHarness(online: false)
        let api = FakeApiService()
        api.setMyHouseholdsResult(.failure(.network))
        let started = await h.start()
        let model = SwitcherModel(
            userId: h.user.userId, currentId: h.alpha.id, current: nil, api: api, cache: h.cache, database: h.db,
            isOnline: { false }, perform: { id, settings in try await h.session.switchTo(id, openSettings: settings) }
        )
        await model.load()

        #expect(await model.select(h.beta.id))

        #expect(h.session.destination == .household(id: h.beta.id, tab: h.session.selectedTab))
        #expect(await h.db.current == h.beta.id)
        #expect(api.calls.isEmpty)
        started.cancel()
    }

    @Test("CORE-07: signing out clears the household list cache and closes the database")
    func signOutClears() async {
        let h = SwitchHarness()
        let started = await h.start()
        #expect(h.cache.read(userId: h.user.userId).count == 3)
        h.auth.emit(nil)
        while h.session.status != .signedOut { await Task.yield() }
        #expect(h.cache.read(userId: h.user.userId).isEmpty)
        while await h.db.current != nil { await Task.yield() }
        started.cancel()
    }

    @Test("CORE-07: a successful load primes the household list from the API")
    func primesList() async {
        let h = SwitchHarness()
        let api = FakeApiService()
        let fresh = makeHousehold(id: h.alpha.id, name: "Alpha renamed")
        api.setMyHouseholdsResult(.success([HouseholdWithRole(household: fresh, role: .owner)]))
        let session = SessionModel(
            auth: h.auth, lastHousehold: LastHouseholdStore(suiteName: h.suite), profileCache: ProfileCache(suiteName: h.suite),
            database: h.db, householdsCache: h.cache, api: api, isOnline: { true }
        )
        let started = Task { await session.start() }
        while session.status == .loading { await Task.yield() }
        await session.waitUntilLoaded()
        #expect(session.householdName == "Alpha renamed")
        #expect(api.calls == [.fetchMyHouseholds(userId: h.user.userId)])
        started.cancel()
    }
}

@Suite("CORE-07 households cache")
struct HouseholdsCacheTests {
    let suite = "HouseholdsCacheTests-\(UUID())"
    let user = UUID()

    @Test("CORE-07: write, upsert, remove and clear")
    func roundTrip() {
        let cache = HouseholdsCache(suiteName: suite)
        let a = HouseholdWithRole(household: makeHousehold(name: "A"), role: .owner)
        var b = HouseholdWithRole(household: makeHousehold(name: "B"), role: .member)
        #expect(cache.read(userId: user).isEmpty)
        cache.write([a], userId: user)
        cache.upsert(b, userId: user)
        #expect(cache.read(userId: user).map(\.household.name) == ["A", "B"])
        b.household.name = "B2"
        cache.upsert(b, userId: user)
        #expect(cache.read(userId: user).map(\.household.name) == ["A", "B2"])
        cache.remove(householdId: a.household.id, userId: user)
        #expect(cache.read(userId: user).map(\.household.name) == ["B2"])
        cache.clear(userId: user)
        #expect(cache.read(userId: user).isEmpty)
        // Another account never sees it.
        cache.write([a], userId: user)
        #expect(cache.read(userId: UUID()).isEmpty)
    }

    @Test("CORE-07: single-household cache writes also upsert into the list, and clearing removes it")
    func singleCacheFeedsList() {
        let single = HouseholdCache(suiteName: suite)
        let list = HouseholdsCache(suiteName: suite)
        let entry = HouseholdWithRole(household: makeHousehold(name: "Solo"), role: .owner)
        single.write(entry, userId: user)
        #expect(list.read(userId: user).map(\.household.name) == ["Solo"])
        single.clear(userId: user, householdId: entry.household.id)
        #expect(list.read(userId: user).isEmpty)
    }
}
