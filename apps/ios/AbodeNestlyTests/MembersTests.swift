import Contract
import Foundation
import Testing
@testable import Core

func makeRow(
    id: UUID = UUID(), name: String? = "Member One", role: Member.Role = .member, household: UUID = UUID()
) -> MemberRow {
    let now = Date(timeIntervalSince1970: 1_700_000_000)
    return MemberRow(
        member: Member(householdId: household, userId: id, role: role, joinedAt: now, leftAt: nil, updatedAt: now, syncSeq: 1),
        profile: name.map { MemberProfile(id: id, displayName: $0, avatarPath: nil) }
    )
}

/// Connectivity the model reads through its `isOnline` closure; tests flip it.
@MainActor
final class OnlineBox {
    var value: Bool
    init(_ value: Bool = true) { self.value = value }
}

@MainActor
struct MembersHarness {
    let householdId = UUID()
    let me = UUID()
    let api = FakeApiService()
    let sleeper = ManualSleeper()
    let online = OnlineBox()
    let cache = MembersCache(suiteName: "abodenestly.tests.members.\(UUID().uuidString)")
    let left = LeftCounter()
    let model: MembersModel

    @MainActor final class LeftCounter { var count = 0 }

    init(role: Member.Role = .owner, others: [MemberRow] = []) {
        let box = online, counter = left
        model = MembersModel(
            householdId: householdId, meId: me, api: api, cache: cache,
            isOnline: { box.value }, sleep: sleeper.sleep, onLeft: { counter.count += 1 }
        )
        api.setMembersResult(.success([makeRow(id: me, name: "Me Myself", role: role, household: householdId)] + others))
    }

    var removeCalls: [FakeApiService.Call] {
        api.calls.filter { if case .removeMember = $0 { true } else { false } }
    }
}

@MainActor
@Suite("CORE-04 members model")
struct MembersTests {
    // MARK: Pure helpers

    @Test("CORE-04: sortMembers puts owners first, then by name, keeping input order on ties")
    func sorting() {
        let rows = [
            makeRow(name: "Zed"), makeRow(name: "bea", role: .owner), makeRow(name: "Amy"),
            makeRow(name: "Cal", role: .owner), makeRow(name: nil), makeRow(name: "  "),
        ]
        let sorted = MembersModel.sortMembers(rows).map(MembersModel.displayName)
        #expect(sorted == ["bea", "Cal", "Amy", "Member", "Member", "Zed"])
        #expect(MembersModel.sortMembers(rows).map(\.id).suffix(3).first == rows[4].id) // stable tie
    }

    @Test("CORE-04: displayName falls back to Member and trims")
    func displayName() {
        #expect(MembersModel.displayName(makeRow(name: nil)) == "Member")
        #expect(MembersModel.displayName(makeRow(name: "   ")) == "Member")
        #expect(MembersModel.displayName(makeRow(name: " Anna Kovacs ")) == "Anna Kovacs")
    }

    @Test("CORE-04: initials", arguments: [
        ("Anna Kovacs", "AK"), ("anna", "A"), ("  anna   maria  kovacs ", "AK"), ("", "?"), ("   ", "?"), ("émile zola", "ÉZ"),
    ])
    func initials(name: String, expected: String) {
        #expect(MembersModel.initials(name) == expected)
    }

    @Test("CORE-04: viewerRole, canRemove and isLastOwner")
    func roles() {
        let me = UUID()
        let owner = makeRow(id: me, role: .owner), other = makeRow(role: .member), otherOwner = makeRow(role: .owner)
        #expect(MembersModel.viewerRole([owner, other], meId: me) == .owner)
        #expect(MembersModel.viewerRole([other], meId: me) == nil)
        #expect(MembersModel.isLastOwner([owner, other], meId: me))
        #expect(!MembersModel.isLastOwner([owner, otherOwner], meId: me))
        #expect(!MembersModel.isLastOwner([makeRow(id: me, role: .member), otherOwner], meId: me))
        #expect(MembersModel.canRemove(role: .owner, target: other, meId: me))
        #expect(!MembersModel.canRemove(role: .owner, target: owner, meId: me))
        #expect(!MembersModel.canRemove(role: .member, target: other, meId: me))
        #expect(!MembersModel.canRemove(role: nil, target: other, meId: me))
    }

    // MARK: Loading and the offline cache

    @Test("CORE-04: load shows the live list and caches it; a network failure falls back to the cache")
    func loadAndCache() async {
        let h = MembersHarness(others: [makeRow(name: "Bea")])
        await h.model.load()
        #expect(h.model.loadState == .loaded)
        #expect(h.model.rows.count == 2)
        #expect(h.cache.read(householdId: h.householdId)?.count == 2)

        let fresh = MembersModel(
            householdId: h.householdId, meId: h.me, api: h.api, cache: h.cache, sleep: h.sleeper.sleep, onLeft: {}
        )
        h.api.setMembersResult(.failure(.network))
        await fresh.load()
        #expect(fresh.loadState == .loaded)
        #expect(fresh.rows.count == 2)
    }

    @Test("CORE-04: AC6 offline loads from the cache without calling the API")
    func offlineUsesCache() async {
        let h = MembersHarness(others: [makeRow(name: "Bea")])
        await h.model.load()
        let fresh = MembersModel(
            householdId: h.householdId, meId: h.me, api: h.api, cache: h.cache, isOnline: { false },
            sleep: h.sleeper.sleep, onLeft: {}
        )
        await fresh.load()
        #expect(fresh.loadState == .loaded)
        #expect(fresh.rows.count == 2)
        #expect(h.api.calls == [.fetchMembers(householdId: h.householdId)])
    }

    @Test("CORE-04: offline with no cache says to connect; online failure with no cache is an error")
    func noCache() async {
        let h = MembersHarness()
        h.online.value = false
        await h.model.load()
        #expect(h.model.loadState == .offlineNoCache)
        #expect(h.api.calls.isEmpty)

        h.online.value = true
        h.api.setMembersResult(.failure(.unknown))
        await h.model.load()
        #expect(h.model.loadState == .error)
    }

    @Test("CORE-04: a failed reload keeps the list that is already on screen")
    func failedReloadKeepsList() async {
        let h = MembersHarness(others: [makeRow(name: "Bea")])
        await h.model.load()
        h.api.setMembersResult(.failure(.unknown))
        await h.model.load()
        #expect(h.model.loadState == .loaded)
        #expect(h.model.rows.count == 2)
    }

    @Test("CORE-04: a fetch that started before a removal landed does not bring the member back")
    func staleFetch() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.model.remove(bea)
        await h.sleeper.advance()
        // Both the DELETE and the next fetch now wait on the API; the DELETE was first, so it lands first.
        h.api.hold()
        let fetch = Task { await h.model.load() }
        await waitFor { h.api.calls.contains(.removeMember(householdId: h.householdId, userId: bea.id)) }
        await waitFor { h.api.calls.filter { $0 == .fetchMembers(householdId: h.householdId) }.count == 2 }
        h.api.release()
        await fetch.value
        await waitFor { h.model.pending == nil }
        #expect(h.model.generation == 1)
        #expect(!h.model.rows.contains { $0.id == bea.id })
    }

    // MARK: Remove, undo, leave

    @Test("CORE-04: AC2 remove hides the row and shows the toast; DELETE is only sent after 5 s")
    func removeWaits() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.model.remove(bea)
        #expect(h.model.toast == "Removed Bea")
        #expect(!h.model.visibleRows.contains { $0.id == bea.id })
        #expect(h.removeCalls.isEmpty)

        await h.sleeper.advance()
        await waitFor { h.model.pending == nil }
        #expect(h.removeCalls == [.removeMember(householdId: h.householdId, userId: bea.id)])
        #expect(h.model.toast == nil)
        #expect(!h.model.rows.contains { $0.id == bea.id })
        #expect(h.cache.read(householdId: h.householdId)?.contains { $0.id == bea.id } == false)
    }

    @Test("CORE-04: AC3 undo within 5 s does not call DELETE and restores the row")
    func undoWithinWindow() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.model.remove(bea)
        h.model.undo()
        #expect(h.model.toast == nil)
        #expect(h.model.pending == nil)
        #expect(h.model.visibleRows.contains { $0.id == bea.id })
        // Give a would-be timer every chance to fire.
        try? await Task.sleep(for: .milliseconds(50))
        #expect(h.removeCalls.isEmpty)
    }

    @Test("CORE-04: AC4 the last owner is blocked before any call")
    func lastOwnerBlocked() async {
        let h = MembersHarness(others: [makeRow(name: "Bea")])
        await h.model.load()
        let calls = h.api.calls
        h.model.leave()
        #expect(h.model.alert == MembersModel.lastOwnerMessage)
        #expect(h.model.toast == nil)
        #expect(h.model.pending == nil)
        #expect(h.api.calls == calls)
    }

    @Test("CORE-04: leaving with another owner waits 5 s, sends DELETE for me and calls onLeft")
    func leaveFlow() async {
        let h = MembersHarness(others: [makeRow(name: "Bea", role: .owner)])
        await h.model.load()
        h.model.leave()
        #expect(h.model.toast == "Leaving household")
        #expect(h.removeCalls.isEmpty)
        await h.sleeper.advance()
        await waitFor { h.left.count == 1 }
        #expect(h.removeCalls == [.removeMember(householdId: h.householdId, userId: h.me)])
        #expect(h.left.count == 1)
    }

    @Test("CORE-04: a member can leave; a member cannot remove others")
    func memberRoles() async {
        let bea = makeRow(name: "Bea", role: .owner)
        let h = MembersHarness(role: .member, others: [bea])
        await h.model.load()
        h.model.remove(bea)
        #expect(h.model.pending == nil)
        h.model.leave()
        #expect(h.model.toast == "Leaving household")
        h.model.undo()
    }

    @Test("CORE-04: not_found counts as success")
    func notFoundIsSuccess() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.api.setRemoveResult(.failure(.notFound))
        h.model.remove(bea)
        await h.sleeper.advance()
        await waitFor { h.model.pending == nil }
        #expect(!h.model.rows.contains { $0.id == bea.id })
        #expect(h.model.alert == nil)
    }

    @Test("CORE-04: a last_owner reply from the server shows the last-owner alert and restores the row")
    func serverLastOwner() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.api.setRemoveResult(.failure(.lastOwner))
        h.model.remove(bea)
        await h.sleeper.advance()
        await waitFor { h.model.pending == nil }
        #expect(h.model.alert == MembersModel.lastOwnerMessage)
        #expect(h.model.visibleRows.contains { $0.id == bea.id })
    }

    @Test("CORE-04: other failures restore the row with a message", arguments: [
        (ApiFailure.forbidden, "You don't have permission to remove Bea."),
        (ApiFailure.network, "Couldn't remove Bea. Try again."),
        (ApiFailure.unknown, "Couldn't remove Bea. Try again."),
    ])
    func removeFailures(failure: ApiFailure, message: String) async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.api.setRemoveResult(.failure(failure))
        h.model.remove(bea)
        await h.sleeper.advance()
        await waitFor { h.model.pending == nil }
        #expect(h.model.alert == message)
        #expect(h.model.visibleRows.contains { $0.id == bea.id })
        #expect(h.left.count == 0)
    }

    @Test("CORE-04: a failed leave stays on the screen with a message")
    func leaveFailure() async {
        let h = MembersHarness(others: [makeRow(name: "Bea", role: .owner)])
        await h.model.load()
        h.api.setRemoveResult(.failure(.network))
        h.model.leave()
        await h.sleeper.advance()
        await waitFor { h.model.pending == nil }
        #expect(h.model.alert == "Couldn't leave the household. Try again.")
        #expect(h.left.count == 0)
    }

    @Test("CORE-04: flushPending sends the DELETE now instead of dropping it")
    func flushOnDisappear() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.model.remove(bea)
        h.model.flushPending()
        await waitFor { h.model.pending == nil }
        #expect(h.removeCalls == [.removeMember(householdId: h.householdId, userId: bea.id)])
        // The cancelled timer must not send it a second time.
        try? await Task.sleep(for: .milliseconds(50))
        #expect(h.removeCalls.count == 1)
    }

    @Test("CORE-04: flushPending with nothing pending does nothing")
    func flushNothing() async {
        let h = MembersHarness(others: [makeRow(name: "Bea")])
        await h.model.load()
        h.model.flushPending()
        try? await Task.sleep(for: .milliseconds(20))
        #expect(h.removeCalls.isEmpty)
    }

    @Test("CORE-04: actions are no-ops offline and while a removal is pending")
    func guards() async {
        let bea = makeRow(name: "Bea"), cal = makeRow(name: "Cal")
        let h = MembersHarness(others: [bea, cal])
        await h.model.load()

        h.online.value = false
        h.model.remove(bea)
        h.model.leave()
        await h.model.makeOwner(bea)
        #expect(h.model.pending == nil)
        #expect(h.api.calls == [.fetchMembers(householdId: h.householdId)])

        h.online.value = true
        h.model.remove(bea)
        h.model.remove(cal)
        #expect(h.model.pending?.row.id == bea.id)
        await h.model.makeOwner(cal)
        #expect(!h.api.calls.contains(.makeOwner(householdId: h.householdId, userId: cal.id)))
        h.model.undo()
    }

    // MARK: Make owner

    @Test("CORE-04: make owner runs at once, clears the alert and refetches")
    func makeOwnerSuccess() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.model.alert = "old"
        h.api.setMembersResult(.success([
            makeRow(id: h.me, name: "Me", role: .owner, household: h.householdId),
            makeRow(id: bea.id, name: "Bea", role: .owner),
        ]))
        await h.model.makeOwner(bea)
        #expect(h.api.calls.contains(.makeOwner(householdId: h.householdId, userId: bea.id)))
        #expect(h.model.alert == nil)
        #expect(h.model.rows.first { $0.id == bea.id }?.member.role == .owner)
    }

    @Test("CORE-04: a failed make owner shows the message")
    func makeOwnerFailure() async {
        let bea = makeRow(name: "Bea")
        let h = MembersHarness(others: [bea])
        await h.model.load()
        h.api.setMakeOwnerResult(.failure(.unknown))
        await h.model.makeOwner(bea)
        #expect(h.model.alert == "Couldn't make Bea an owner. Try again.")
    }

    @Test("CORE-04: make owner is not offered on an owner or by a member")
    func makeOwnerGuards() async {
        let owner = makeRow(name: "Bea", role: .owner)
        let h = MembersHarness(others: [owner])
        await h.model.load()
        await h.model.makeOwner(owner)
        let m = MembersHarness(role: .member, others: [makeRow(name: "Cal")])
        await m.model.load()
        await m.model.makeOwner(m.model.rows.last!)
        #expect(!h.api.calls.contains { if case .makeOwner = $0 { true } else { false } })
        #expect(!m.api.calls.contains { if case .makeOwner = $0 { true } else { false } })
    }

    // MARK: Cache and HTTP mapping

    @Test("CORE-04: MembersCache round-trips and ignores unreadable data")
    func cacheRoundTrip() {
        let suite = "abodenestly.tests.members.\(UUID().uuidString)"
        let cache = MembersCache(suiteName: suite)
        let household = UUID()
        #expect(cache.read(householdId: household) == nil)
        let rows = [makeRow(name: "Bea", role: .owner), makeRow(name: nil)]
        cache.write(rows, householdId: household)
        #expect(cache.read(householdId: household) == rows)
        UserDefaults(suiteName: suite)!.set(Data("nope".utf8), forKey: MembersCache.key(household))
        #expect(cache.read(householdId: household) == nil)
    }

    nonisolated private static let mappingCases: [(Int, String?, ApiFailure)] = [
        (409, "last_owner", .lastOwner), (403, "forbidden", .forbidden), (404, "not_found", .notFound),
        (500, "boom", .unknown), (409, nil, .lastOwner), (403, nil, .forbidden), (404, nil, .notFound),
        (500, nil, .unknown), (404, "boom", .unknown),
    ]

    @Test("CORE-04: remove errors map by body code, then by status", arguments: mappingCases)
    func removeFailureMapping(status: Int, code: String?, expected: ApiFailure) {
        #expect(HttpApiService.removeFailure(status: status, code: code) == expected)
    }

    @Test("CORE-04: LastHouseholdStore.clear forgets the household")
    func clearLastHousehold() {
        let store = LastHouseholdStore(suiteName: "abodenestly.tests.last.\(UUID().uuidString)")
        store.write(UUID())
        store.clear()
        #expect(store.read() == nil)
    }
}
