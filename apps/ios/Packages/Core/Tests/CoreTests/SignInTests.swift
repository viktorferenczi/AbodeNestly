import Contract
import Foundation
import Testing
@testable import Core

private func household(shopping: Bool = true, pantry: Bool = true, maintenance: Bool = true) -> LandingHousehold {
    LandingHousehold(id: UUID(), moduleShopping: shopping, modulePantry: pantry, moduleMaintenance: maintenance)
}

private func membership(_ household: LandingHousehold, joined: TimeInterval = 0) -> Membership {
    Membership(householdId: household.id, joinedAt: Date(timeIntervalSince1970: joined), household: household)
}

@Suite("CORE-01 landing rule")
struct LandingTests {
    @Test(
        "CORE-01: start tab is the first enabled module",
        arguments: [
            (household(), AppTab.shopping),
            (household(shopping: false), .pantry),
            (household(shopping: false, pantry: false), .maintenance),
            (household(shopping: false, pantry: false, maintenance: false), .home),
        ]
    )
    func startTab(input: LandingHousehold, expected: AppTab) {
        #expect(Landing.startTab(input) == expected)
    }

    @Test("CORE-01: no household lands on welcome")
    func noHousehold() {
        #expect(Landing.destination(memberships: [], lastHouseholdId: nil) == .welcome)
    }

    @Test("CORE-01: one household lands on its start tab")
    func oneHousehold() {
        let home = household()
        #expect(
            Landing.destination(memberships: [membership(home)], lastHouseholdId: nil)
                == .household(id: home.id, tab: .shopping)
        )
    }

    @Test("CORE-01: a stale last id falls back to the earliest joined")
    func staleLastId() {
        let early = household(), late = household(shopping: false)
        let destination = Landing.destination(
            memberships: [membership(late, joined: 200), membership(early, joined: 100)], lastHouseholdId: UUID()
        )
        #expect(destination == .household(id: early.id, tab: .shopping))
    }

    @Test("CORE-01: the last used household is honoured")
    func lastIdHonoured() {
        let early = household(), late = household(shopping: false)
        let destination = Landing.destination(
            memberships: [membership(early, joined: 100), membership(late, joined: 200)], lastHouseholdId: late.id
        )
        #expect(destination == .household(id: late.id, tab: .pantry))
    }
}

@MainActor
@Suite("CORE-01 session model")
struct SessionModelTests {
    let user = AuthSession(userId: UUID(), email: "ada@test.local")

    private func makeModel(_ auth: FakeAuthService, online: Bool = true) -> SessionModel {
        SessionModel(
            auth: auth, lastHousehold: LastHouseholdStore(suiteName: "SessionModelTests-\(UUID())"),
            isOnline: { online }
        )
    }

    private func loaded(_ model: SessionModel) async -> Task<Void, Never> {
        let started = Task { await model.start() }
        while model.status == .loading { await Task.yield() }
        await model.waitUntilLoaded()
        return started
    }

    @Test("CORE-01: AC8 exposes the profile display name")
    func profileName() async {
        let auth = FakeAuthService(session: user)
        auth.setProfile(.success(Profile(id: user.userId, displayName: "ada", avatarPath: nil, createdAt: Date(), updatedAt: Date())))
        let model = makeModel(auth)
        let started = await loaded(model)
        #expect(model.profile?.displayName == "ada")
        #expect(model.destination == .welcome)
        started.cancel()
    }

    @Test("CORE-01: AC8 a missing profile row leaves the name nil")
    func missingProfile() async {
        let model = makeModel(FakeAuthService(session: user))
        let started = await loaded(model)
        #expect(model.status == .signedIn)
        #expect(model.profile == nil)
        started.cancel()
    }

    @Test("CORE-01: offline membership failure enters Home")
    func offlineFailure() async {
        let auth = FakeAuthService(session: user)
        auth.setMemberships(.failure(.network))
        let model = makeModel(auth, online: false)
        let started = await loaded(model)
        #expect(model.destination == .home)
        #expect(!model.loadFailed)
        started.cancel()
    }

    @Test("CORE-01: online membership failure is a blocking error")
    func onlineFailure() async {
        let auth = FakeAuthService(session: user)
        auth.setMemberships(.failure(.unknown))
        let model = makeModel(auth)
        let started = await loaded(model)
        #expect(model.loadFailed)
        #expect(model.destination == nil)
        started.cancel()
    }

    @Test("CORE-01: signing out clears the profile and destination")
    func signOut() async {
        let auth = FakeAuthService(session: user)
        let model = makeModel(auth)
        let started = await loaded(model)
        await model.signOut()
        while model.status != .signedOut { await Task.yield() }
        #expect(model.profile == nil && model.destination == nil)
        started.cancel()
    }
}

@Suite("CORE-01 plumbing")
struct PlumbingTests {
    @Test("CORE-01: decodes PostgREST microsecond timestamps")
    func microsecondTimestamps() throws {
        let json = """
        {"id":"\(UUID())","display_name":"ada","avatar_path":null,
         "created_at":"2026-11-09T20:31:12.123456+00:00","updated_at":"2026-11-09T20:31:12+00:00"}
        """
        let profile = try PostgrestJSON.decoder.decode(Profile.self, from: Data(json.utf8))
        #expect(profile.displayName == "ada")
        #expect(profile.avatarPath == nil)
    }

    @Test("CORE-01: maps transport errors like web toAuthError")
    func errorMapping() {
        #expect(SupabaseAuthService.map(URLError(.notConnectedToInternet)) == .network)
        #expect(SupabaseAuthService.map(URLError(.cancelled)) == .cancelled)
        #expect(SupabaseAuthService.map(CancellationError()) == .cancelled)
        #expect(SupabaseAuthService.map(URLError(.badServerResponse)) == .unknown)
    }

    @Test("CORE-01: nonce hash is SHA-256 hex")
    func nonceHash() {
        #expect(Nonce(raw: "abc").hashed == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
