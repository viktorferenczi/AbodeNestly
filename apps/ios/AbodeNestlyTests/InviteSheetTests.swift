import Contract
import Foundation
import Testing
@testable import Core

func makeInvite(
    id: UUID = UUID(), householdId: UUID = UUID(), code: String = "abc123def456", uses: Int = 0,
    maxUses: Int = 10, expiresAt: Date = Date(timeIntervalSince1970: 1_795_000_000)
) -> Invite {
    Invite(
        id: id, householdId: householdId, code: code, role: .member, createdBy: UUID(), expiresAt: expiresAt,
        maxUses: maxUses, uses: uses, createdAt: Date(), updatedAt: Date(), deletedAt: nil, syncSeq: 1
    )
}

@MainActor
@Suite("CORE-03 invite")
struct InviteSheetTests {
    let householdId = UUID()
    let api = FakeApiService()

    private func model(copy: @escaping @MainActor (String) -> Void = { _ in }) -> InviteSheetModel {
        InviteSheetModel(householdId: householdId, api: api, copy: copy)
    }

    @Test("CORE-03: AC4 a join with a live code lands on that household's Home and remembers it")
    func joinLandsOnHome() async {
        let suite = "InviteSheetTests-\(UUID())"
        let user = AuthSession(userId: UUID(), email: "bo@test.local")
        let auth = FakeAuthService(session: user)
        let session = SessionModel(
            auth: auth, lastHousehold: LastHouseholdStore(suiteName: suite),
            profileCache: ProfileCache(suiteName: suite), isOnline: { true }
        )
        let started = Task { await session.start() }
        while session.status == .loading { await Task.yield() }
        await session.waitUntilLoaded()

        let joined = UUID()
        auth.setMemberships(.success([Membership(
            householdId: joined, joinedAt: Date(),
            household: LandingHousehold(id: joined, moduleShopping: true, modulePantry: true, moduleMaintenance: true)
        )]))
        api.setAcceptResult(.success(joined))
        let welcome = WelcomeModel(api: api) { outcome in
            if case .joined(let id) = outcome { session.enterJoined(householdId: id) }
        }
        welcome.code = " ABC123def456 "
        await welcome.join()
        await session.waitUntilLoaded()

        #expect(api.calls == [.acceptInvite(code: "abc123def456")])
        #expect(session.destination == .household(id: joined, tab: .home))
        #expect(LastHouseholdStore(suiteName: suite).read() == joined)
        started.cancel()
    }

    @Test("CORE-03: a live invite is shown without creating another")
    func liveInviteShown() async {
        let live = makeInvite()
        api.setLiveInviteResult(.success(live))
        let model = model()
        await model.loadIfNeeded()
        #expect(model.state == .ready(live))
        #expect(api.calls == [.fetchLiveInvite(householdId: householdId)])
    }

    @Test("CORE-03: with no live invite one is created")
    func createsWhenNone() async {
        let created = makeInvite()
        api.setCreateInviteResult(.success(created))
        let model = model()
        await model.loadIfNeeded()
        #expect(model.state == .ready(created))
        #expect(api.calls == [.fetchLiveInvite(householdId: householdId), .createInvite(householdId: householdId)])
    }

    @Test("CORE-03: loadIfNeeded does nothing while an invite is shown")
    func loadSkipsWhenShown() async {
        api.setLiveInviteResult(.success(makeInvite()))
        let model = model()
        await model.loadIfNeeded()
        await model.loadIfNeeded()
        #expect(api.calls.count == 1)
    }

    @Test("CORE-03: a load failure is an error without an invite; loading again recovers")
    func loadFailure() async {
        api.setLiveInviteResult(.failure(.network))
        let model = model()
        await model.loadIfNeeded()
        #expect(model.state == .error(.network, nil))
        let live = makeInvite()
        api.setLiveInviteResult(.success(live))
        await model.loadIfNeeded()
        #expect(model.state == .ready(live))
    }

    @Test("CORE-03: AC2 New code creates the new invite, then retires the old one")
    func newCodeRetiresOld() async {
        let old = makeInvite(code: "aaaaaaaaaaaa"), new = makeInvite(code: "bbbbbbbbbbbb")
        api.setLiveInviteResult(.success(old))
        api.setCreateInviteResult(.success(new))
        let model = model()
        await model.loadIfNeeded()
        await model.newCode()
        #expect(model.state == .ready(new))
        #expect(!model.oldCodeMayWork)
        #expect(api.calls.suffix(2) == [.createInvite(householdId: householdId), .retireInvite(id: old.id)])
    }

    @Test("CORE-03: a failed retire still shows the new code and flags the old one")
    func retireFailure() async {
        let old = makeInvite(), new = makeInvite(code: "bbbbbbbbbbbb")
        api.setLiveInviteResult(.success(old))
        api.setCreateInviteResult(.success(new))
        api.setRetireResult(.failure(.unknown))
        let model = model()
        await model.loadIfNeeded()
        await model.newCode()
        #expect(model.state == .ready(new))
        #expect(model.oldCodeMayWork)
    }

    @Test("CORE-03: a failed create keeps the old invite and never retires it")
    func createFailureKeepsOld() async {
        let old = makeInvite()
        api.setLiveInviteResult(.success(old))
        api.setCreateInviteResult(.failure(.network))
        let model = model()
        await model.loadIfNeeded()
        await model.newCode()
        #expect(model.state == .error(.network, old))
        #expect(model.shownInvite == old)
        #expect(!api.calls.contains(.retireInvite(id: old.id)))
    }

    @Test("CORE-03: copyCode hands the code to the copier and says so")
    func copy() async {
        var copied: [String] = []
        api.setLiveInviteResult(.success(makeInvite(code: "abc123def456")))
        let model = model { copied.append($0) }
        await model.loadIfNeeded()
        model.copyCode()
        #expect(copied == ["abc123def456"])
        #expect(model.notice == "Code copied")
    }

    @Test("CORE-03: AC3 the share link is <base>/join/<code>, percent-encoded")
    func link() {
        let base = URL(string: "http://localhost:5173")!
        #expect(InviteSheetModel.link(base: base, code: "abc123").absoluteString == "http://localhost:5173/join/abc123")
        #expect(InviteSheetModel.link(base: URL(string: "https://x.test/")!, code: "a/b c").absoluteString
            == "https://x.test/join/a%2Fb%20c")
    }

    @Test("CORE-03: AC1 uses left and expiry read like web")
    func meta() {
        #expect(InviteSheetModel.usesLeft(makeInvite(uses: 3)) == 7)
        let locale = Locale(identifier: "en_GB"), utc = TimeZone(identifier: "UTC")!
        let date = Date(timeIntervalSince1970: 1_795_086_400) // 2026-11-19 11:06 UTC
        #expect(InviteSheetModel.expiryText(date, locale: locale, timeZone: utc) == "19 Nov")
        #expect(InviteSheetModel.metaText(makeInvite(expiresAt: date), locale: locale, timeZone: utc)
            == "10 uses left · Expires 19 Nov")
        #expect(InviteSheetModel.metaText(makeInvite(uses: 9, expiresAt: date), locale: locale, timeZone: utc)
            == "1 use left · Expires 19 Nov")
    }
}
