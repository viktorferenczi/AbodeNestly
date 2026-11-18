import Contract
import Foundation
import Testing
@testable import Core
@testable import Sync

let homeNow = Date(timeIntervalSince1970: 1_800_000_000)
let homeDay: TimeInterval = 86_400

func makeActivity(
    _ summary: String, ago: TimeInterval, household: UUID = UUID(), id: UUID = UUID(), module: Activity.Module = .shopping,
    deleted: Bool = false
) -> Activity {
    let at = homeNow.addingTimeInterval(-ago)
    return Activity(
        id: id, householdId: household, actorId: nil, module: module, entity: "list_item", entityId: UUID(),
        action: "checked", summary: summary, createdAt: at, updatedAt: at, deletedAt: deleted ? at : nil, syncSeq: 1
    )
}

@Suite("CORE-08 home feed window")
struct HomeFeedWindowTests {
    @Test("CORE-08: AC2 the window keeps 30 days, drops older and soft-deleted rows")
    func cutoff() {
        let rows = [
            makeActivity("inside", ago: 30 * homeDay - 1),
            makeActivity("edge", ago: 30 * homeDay),
            makeActivity("old", ago: 30 * homeDay + 1),
            makeActivity("deleted", ago: 5, deleted: true),
        ]
        #expect(HomeFeed.window(rows, now: homeNow).map(\.summary).sorted() == ["edge", "inside"])
    }

    @Test("CORE-08: AC2 newest first, ties broken by id descending")
    func ordering() {
        let low = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
        let high = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
        let rows = [
            makeActivity("old", ago: 300), makeActivity("tie-low", ago: 100, id: low),
            makeActivity("new", ago: 10), makeActivity("tie-high", ago: 100, id: high),
        ]
        #expect(HomeFeed.window(rows, now: homeNow).map(\.summary) == ["new", "tie-high", "tie-low", "old"])
    }

    @Test("CORE-08: AC2 caps at 20 and keeps the newest")
    func cap() {
        let rows = (0..<25).map { makeActivity("r\($0)", ago: Double($0 + 1) * 60) }
        let feed = HomeFeed.window(rows, now: homeNow)
        #expect(feed.count == 20)
        #expect(feed.first?.summary == "r0")
        #expect(feed.last?.summary == "r19")
    }

    @Test("CORE-08: state is all-modules-off before empty or populated, and unknown flags never show the prompt",
          arguments: [
            (HomeModules?.some(HomeModules(shopping: true, pantry: false, maintenance: false)), 0, HomeState.empty),
            (.some(HomeModules(shopping: true, pantry: false, maintenance: false)), 1, .populated),
            (.some(HomeModules(shopping: false, pantry: false, maintenance: false)), 0, .allModulesOff),
            (.some(HomeModules(shopping: false, pantry: false, maintenance: false)), 1, .allModulesOff),
            (.none, 0, .empty),
          ])
    func state(modules: HomeModules?, count: Int, expected: HomeState) {
        let feed = (0..<count).map { makeActivity("r\($0)", ago: 10) }
        #expect(HomeFeed.state(feed: feed, modules: modules) == expected)
    }
}

@Suite("CORE-08 activity table")
struct ActivityDatabaseTests {
    let root = switcherTempRoot()
    let household = UUID()

    private func firstValue(_ stream: AsyncStream<[Activity]>) async -> [Activity]? {
        for await rows in stream { return rows }
        return nil
    }

    @Test("CORE-08: AC5 upserted rows read back newest first, with every column intact")
    func roundTrip() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        let other = makeActivity("someone else's", ago: 5, household: UUID())
        let older = makeActivity("Anna checked off Milk", ago: 600, household: household)
        var newer = makeActivity("Ben joined the household", ago: 60, household: household, module: .core)
        newer.actorId = UUID()
        try await db.upsertActivity([older, newer, other])

        let rows = await firstValue(await db.observeActivity(householdId: household))
        #expect(rows?.map(\.summary) == ["Ben joined the household", "Anna checked off Milk"])
        #expect(rows?.first == newer)
    }

    @Test("CORE-08: observing re-emits when a pull stores new rows, and a replaced row updates in place")
    func reemits() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        var row = makeActivity("first", ago: 100, household: household)
        try await db.upsertActivity([row])
        var iterator = await db.observeActivity(householdId: household).makeAsyncIterator()
        #expect(await iterator.next()?.map(\.summary) == ["first"])

        row.summary = "edited"
        try await db.upsertActivity([row, makeActivity("second", ago: 10, household: household)])
        // The observation may deliver the intermediate state first; wait for the final one.
        var latest: [String] = []
        for _ in 0..<5 {
            latest = await iterator.next()?.map(\.summary) ?? []
            if latest.count == 2 { break }
        }
        #expect(latest == ["second", "edited"])
    }

    @Test("CORE-08: a household that is not open yields no stream, and rows survive a reopen")
    func notOpenAndReopen() async throws {
        let db = HouseholdDatabase(rootURL: root)
        #expect(await firstValue(await db.observeActivity(householdId: household)) == nil)
        try await db.open(householdId: household)
        try await db.upsertActivity([makeActivity("kept", ago: 5, household: household)])
        await db.close()
        try await db.open(householdId: household)
        #expect(await firstValue(await db.observeActivity(householdId: household))?.map(\.summary) == ["kept"])
    }

    @Test("CORE-08: soft-deleted rows are not observed")
    func skipsDeleted() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        try await db.upsertActivity([makeActivity("gone", ago: 5, household: household, deleted: true)])
        #expect(await firstValue(await db.observeActivity(householdId: household)) == [])
    }
}

@Suite("CORE-08 activity pull")
struct ActivityPullTests {
    let root = switcherTempRoot()
    let household = UUID()

    private func setup(online: Bool = true) async throws -> (ActivityPull, FakeApiService, HouseholdDatabase) {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        let api = FakeApiService()
        api.setActivityResult(.success([makeActivity("pulled", ago: 5, household: household)]))
        return (ActivityPull(api: api, database: db, isOnline: { online }, now: { homeNow }), api, db)
    }

    private func stored(_ db: HouseholdDatabase) async -> [String] {
        for await rows in await db.observeActivity(householdId: household) { return rows.map(\.summary) }
        return []
    }

    @Test("CORE-08: kick asks for the last 30 days and stores what comes back")
    func pulls() async throws {
        let (pull, api, db) = try await setup()
        await pull.kick()
        #expect(api.calls == [.fetchActivity(householdId: household, since: homeNow.addingTimeInterval(-30 * homeDay), limit: HomeFeed.pullLimit)])
        #expect(await stored(db) == ["pulled"])
    }

    @Test("CORE-08: AC5 offline kick is a no-op and keeps the cached rows")
    func offline() async throws {
        let (pull, api, db) = try await setup(online: false)
        try await db.upsertActivity([makeActivity("cached", ago: 5, household: household)])
        await pull.kick()
        #expect(api.calls.isEmpty)
        #expect(await stored(db) == ["cached"])
    }

    @Test("CORE-08: a failed read keeps the cache")
    func failure() async throws {
        let (pull, api, db) = try await setup()
        try await db.upsertActivity([makeActivity("cached", ago: 5, household: household)])
        api.setActivityResult(.failure(.network))
        await pull.kick()
        #expect(await stored(db) == ["cached"])
    }

    @Test("CORE-08: concurrent kicks share one read")
    func coalesces() async throws {
        let (pull, api, _) = try await setup()
        api.hold()
        async let first: Void = pull.kick()
        async let second: Void = pull.kick()
        // Let both reach the actor before the read completes.
        for _ in 0..<50 { await Task.yield() }
        api.release()
        _ = await (first, second)
        #expect(api.calls.count == 1)
    }

    @Test("CORE-08: a tombstone replaces the cached row, and an empty page is still a read")
    func tombstones() async throws {
        let (pull, api, db) = try await setup()
        let row = makeActivity("shown", ago: 5, household: household)
        try await db.upsertActivity([row])
        var retired = row
        retired.deletedAt = homeNow
        api.setActivityResult(.success([retired]))
        await pull.kick()
        #expect(await stored(db) == [])
        api.setActivityResult(.success([]))
        await pull.kick()
        #expect(api.calls.count == 2)
    }

    @Test("CORE-08: a switch during the read is pulled again for the household now open")
    func switchDuringRead() async throws {
        let (pull, api, db) = try await setup()
        let other = UUID()
        api.hold()
        async let kicked: Void = pull.kick()
        for _ in 0..<50 { await Task.yield() }
        try await db.open(householdId: other)
        api.setActivityResult(.success([makeActivity("other", ago: 5, household: other)]))
        api.release()
        await kicked
        let fetched: [UUID] = api.calls.compactMap {
            if case .fetchActivity(let id, _, _) = $0 { id } else { nil }
        }
        #expect(fetched == [household, other])
        for await rows in await db.observeActivity(householdId: other) {
            #expect(rows.map(\.summary) == ["other"])
            break
        }
    }

    @Test("CORE-08: with no household open there is nothing to pull")
    func noHousehold() async throws {
        let db = HouseholdDatabase(rootURL: root)
        let api = FakeApiService()
        await ActivityPull(api: api, database: db, isOnline: { true }).kick()
        #expect(api.calls.isEmpty)
    }
}
