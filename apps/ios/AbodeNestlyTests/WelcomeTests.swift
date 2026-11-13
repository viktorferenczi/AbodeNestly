import Contract
import Foundation
import Shared
import Testing
import os
@testable import Core

func makeHousehold(id: UUID = UUID(), name: String = "Test Home") -> Household {
    Household(
        id: id, name: name, createdBy: UUID(), moduleShopping: true, modulePantry: true,
        moduleMaintenance: true, currency: "EUR", createdAt: Date(), updatedAt: Date(), deletedAt: nil, syncSeq: 1
    )
}

private func profile(_ userId: UUID, name: String) -> Profile {
    Profile(id: userId, displayName: name, avatarPath: nil, createdAt: Date(), updatedAt: Date())
}

@MainActor
@Suite("CORE-02 welcome logic")
struct WelcomeTests {
    let user = AuthSession(userId: UUID(), email: "ada@test.local")
    let suite = "WelcomeTests-\(UUID())"

    private func makeSession(_ auth: FakeAuthService, online: Bool = true, cache: String? = nil) -> SessionModel {
        SessionModel(
            auth: auth, lastHousehold: LastHouseholdStore(suiteName: suite),
            profileCache: ProfileCache(suiteName: cache ?? suite), isOnline: { online }
        )
    }

    private func loaded(_ model: SessionModel) async -> Task<Void, Never> {
        let started = Task { await model.start() }
        while model.status == .loading { await Task.yield() }
        await model.waitUntilLoaded()
        return started
    }

    @Test("CORE-02: AC2 landing for a new household is its Home tab")
    func landing() {
        let household = makeHousehold()
        #expect(WelcomeModel.landing(for: household) == .household(id: household.id, tab: .home))
    }

    @Test("CORE-02: AC2 after a create success the session lands on Home and remembers the household")
    func createLandsOnHome() async {
        let auth = FakeAuthService(session: user)
        let session = makeSession(auth)
        let started = await loaded(session)
        #expect(session.destination == .welcome)

        let api = FakeApiService()
        let created = makeHousehold()
        api.setCreateResult(.success(created))
        let welcome = WelcomeModel(api: api) { outcome in
            guard case .created(let household) = outcome else { return }
            session.enter(LandingHousehold(
                id: household.id, moduleShopping: household.moduleShopping,
                modulePantry: household.modulePantry, moduleMaintenance: household.moduleMaintenance
            ))
        }
        welcome.name = "  Test Home  "
        await welcome.create()

        #expect(api.calls == [.createHousehold(name: "Test Home")])
        #expect(session.destination == .household(id: created.id, tab: .home))
        #expect(session.destination == WelcomeModel.landing(for: created))
        #expect(LastHouseholdStore(suiteName: suite).read() == created.id)
        started.cancel()
    }

    @Test("CORE-02: enterJoined reloads memberships and lands on that household's Home tab")
    func enterJoinedReloads() async {
        let auth = FakeAuthService(session: user)
        let session = makeSession(auth)
        let started = await loaded(session)
        #expect(session.destination == .welcome)

        let joinedId = UUID()
        let household = LandingHousehold(id: joinedId, moduleShopping: true, modulePantry: false, moduleMaintenance: false)
        auth.setMemberships(.success([Membership(householdId: joinedId, joinedAt: Date(), household: household)]))
        session.enterJoined(householdId: joinedId)
        await session.waitUntilLoaded()

        // Shopping is enabled, so the plain landing rule would pick it; a fresh join opens on Home.
        #expect(session.destination == .household(id: joinedId, tab: .home))
        #expect(session.household == household)
        #expect(LastHouseholdStore(suiteName: suite).read() == joinedId)
        started.cancel()
    }

    @Test("CORE-02: a failed profile read falls back to the cached name")
    func profileCacheFallback() async {
        let cache = "WelcomeCache-\(UUID())"
        let first = FakeAuthService(session: user)
        first.setProfile(.success(profile(user.userId, name: "Ada")))
        var started = await loaded(makeSession(first, cache: cache))
        started.cancel()

        let offline = FakeAuthService(session: user)
        offline.setProfile(.failure(.network))
        offline.setMemberships(.failure(.network))
        let session = makeSession(offline, online: false, cache: cache)
        started = await loaded(session)
        #expect(session.profile?.displayName == "Ada")
        #expect(session.destination == .home)
        started.cancel()
    }

    @Test("CORE-02: the profile cache is per user")
    func profileCachePerUser() {
        let cache = ProfileCache(suiteName: "WelcomeCache-\(UUID())")
        let ada = UUID(), bob = UUID()
        cache.write(profile(ada, name: "Ada"), userId: ada)
        #expect(cache.read(userId: ada)?.displayName == "Ada")
        #expect(cache.read(userId: bob) == nil)
    }

    @Test(
        "CORE-02: accept-invite errors map like web toAcceptFailure",
        arguments: [
            (400, "invalid_invite" as String?, ApiFailure.invalidCode),
            (404, "not_found", .invalidCode),
            (410, "gone", .invalidCode),
            (400, "validation", .unknown),
            (500, "internal", .unknown),
            (404, nil, .invalidCode),
            (410, nil, .invalidCode),
            (500, nil, .unknown),
            (400, nil, .unknown),
        ]
    )
    func acceptFailure(status: Int, code: String?, expected: ApiFailure) {
        #expect(HttpApiService.acceptFailure(status: status, code: code) == expected)
    }
}

/// Answers every request from `handler`, so the HTTP client runs without a network.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) throws -> (Int, Data)
    static let handler = OSAllocatedUnfairLock<Handler?>(initialState: nil)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            let (status, data) = try Self.handler.withLock { $0 }!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

@Suite("CORE-02 http api service", .serialized)
struct HttpApiServiceTests {
    private let config = AppConfig(
        apiBaseURL: URL(string: "http://api.test")!, supabaseURL: URL(string: "http://supabase.test")!,
        supabaseAnonKey: "anon", authURLScheme: "abodenestly-dev",
        webBaseURL: URL(string: "http://localhost:5173")!
    )

    private func makeService(_ auth: FakeAuthService) -> HttpApiService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return HttpApiService(config: config, auth: auth, urlSession: URLSession(configuration: configuration))
    }

    private static func householdJSON(_ household: Household) -> Data {
        Data("""
        {"id":"\(household.id)","name":"\(household.name)","created_by":"\(household.createdBy)",
         "module_shopping":true,"module_pantry":true,"module_maintenance":true,"currency":"EUR",
         "created_at":"2026-11-09T20:31:12.123456+00:00","updated_at":"2026-11-09T20:31:12+00:00",
         "deleted_at":null,"sync_seq":1}
        """.utf8)
    }

    @Test("CORE-02: create posts to /households with the bearer token and decodes the 201 household")
    func createSuccess() async throws {
        let household = makeHousehold()
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        StubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (201, Self.householdJSON(household))
            }
        }
        let result = await makeService(FakeAuthService()).createHousehold(name: "Test Home")
        #expect(try result.get().id == household.id)
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path == "/households")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fake-token")
    }

    @Test("CORE-02: a 401 refreshes the token once and retries")
    func refreshesOnce() async {
        let household = makeHousehold()
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        StubURLProtocol.handler.withLock {
            $0 = { _ in
                let attempt = attempts.withLock { n -> Int in n += 1; return n }
                return attempt == 1 ? (401, Data()) : (201, Self.householdJSON(household))
            }
        }
        let auth = FakeAuthService()
        let result = await makeService(auth).createHousehold(name: "Test Home")
        #expect((try? result.get().id) == household.id)
        #expect(auth.tokenRequests == [false, true])
    }

    @Test("CORE-02: create errors — validation code or 400, transport failure, anything else")
    func createErrors() async {
        let cases: [(Int, String, ApiFailure)] = [
            (400, #"{"error":{"code":"validation","message":"x"}}"#, .validation),
            (400, "", .validation),
            (422, #"{"error":{"code":"validation"}}"#, .validation),
            (500, #"{"error":{"code":"internal"}}"#, .unknown),
        ]
        for (status, body, expected) in cases {
            StubURLProtocol.handler.withLock { $0 = { _ in (status, Data(body.utf8)) } }
            let result = await makeService(FakeAuthService()).createHousehold(name: "x")
            #expect(result == .failure(expected), "\(status) \(body)")
        }
        StubURLProtocol.handler.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        #expect(await makeService(FakeAuthService()).createHousehold(name: "x") == .failure(.network))
    }

    @Test("CORE-02: accept posts to /invites/{code}/accept and returns the household id")
    func acceptSuccess() async throws {
        let id = UUID()
        let seen = OSAllocatedUnfairLock<URL?>(initialState: nil)
        StubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0 = request.url }
                return (200, Data(#"{"household_id":"\#(id)"}"#.utf8))
            }
        }
        let result = await makeService(FakeAuthService()).acceptInvite(code: "AB/12 x")
        #expect(try result.get() == id)
        // The slash in a typed code must not add a path segment.
        #expect(seen.withLock { $0 }?.path(percentEncoded: true) == "/invites/AB%2F12%20x/accept")
    }

    private static func inviteJSON(_ invite: Invite) -> String {
        """
        {"id":"\(invite.id)","household_id":"\(invite.householdId)","code":"\(invite.code)","role":"member",
         "created_by":"\(invite.createdBy)","expires_at":"2026-11-19T09:46:40.123456+00:00","max_uses":10,"uses":\(invite.uses),
         "created_at":"2026-11-12T09:46:40.5Z","updated_at":"2026-11-12T09:46:40Z","deleted_at":null,"sync_seq":1}
        """
    }

    @Test("CORE-03: createInvite posts {} to /households/{id}/invites and decodes the 201")
    func createInviteHttp() async throws {
        let invite = makeInvite()
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        StubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (201, Data(Self.inviteJSON(invite).utf8))
            }
        }
        let result = await makeService(FakeAuthService()).createInvite(householdId: invite.householdId)
        #expect(try result.get().code == invite.code)
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path == "/households/\(invite.householdId.uuidString.lowercased())/invites")
        StubURLProtocol.handler.withLock { $0 = { _ in (400, Data()) } }
        #expect(await makeService(FakeAuthService()).createInvite(householdId: UUID()) == .failure(.unknown))
        StubURLProtocol.handler.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        #expect(await makeService(FakeAuthService()).createInvite(householdId: UUID()) == .failure(.network))
    }

    @Test("CORE-03: fetchLiveInvite queries PostgREST with apikey and picks the first invite with uses left")
    func fetchLiveHttp() async throws {
        let used = makeInvite(code: "used00000000", uses: 10), free = makeInvite(code: "free00000000", uses: 2)
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        StubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (200, Data("[\(Self.inviteJSON(used)),\(Self.inviteJSON(free))]".utf8))
            }
        }
        let result = await makeService(FakeAuthService()).fetchLiveInvite(householdId: free.householdId)
        #expect(try result.get()?.code == "free00000000")
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "GET")
        #expect(request.url?.path == "/rest/v1/invite")
        let query = request.url?.query ?? ""
        #expect(query.contains("household_id=eq.\(free.householdId.uuidString.lowercased())"))
        #expect(query.contains("deleted_at=is.null") && query.contains("expires_at=gt.") && query.contains("order=created_at.desc"))
        #expect(request.value(forHTTPHeaderField: "apikey") == "anon")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fake-token")

        StubURLProtocol.handler.withLock { $0 = { _ in (200, Data("[]".utf8)) } }
        #expect(try await makeService(FakeAuthService()).fetchLiveInvite(householdId: UUID()).get() == nil)
        StubURLProtocol.handler.withLock { $0 = { _ in (500, Data()) } }
        #expect(await makeService(FakeAuthService()).fetchLiveInvite(householdId: UUID()) == .failure(.unknown))
    }

    @Test("CORE-03: retireInvite patches deleted_at; an empty result (RLS) is a failure")
    func retireHttp() async throws {
        let id = UUID()
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        StubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (200, Data(#"[{"id":"x"}]"#.utf8))
            }
        }
        #expect(await makeService(FakeAuthService()).retireInvite(id: id).failure == nil)
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "PATCH")
        #expect(request.url?.query == "id=eq.\(id.uuidString.lowercased())")
        #expect(request.value(forHTTPHeaderField: "Prefer") == "return=representation")

        StubURLProtocol.handler.withLock { $0 = { _ in (200, Data("[]".utf8)) } }
        #expect(await makeService(FakeAuthService()).retireInvite(id: id).failure == .unknown)
    }

    @Test("CORE-02: an unknown code is invalidCode")
    func acceptInvalid() async {
        StubURLProtocol.handler.withLock { $0 = { _ in (404, Data(#"{"error":{"code":"not_found"}}"#.utf8)) } }
        #expect(await makeService(FakeAuthService()).acceptInvite(code: "nope") == .failure(.invalidCode))
    }
}


extension Result {
    /// The failure, if any; lets tests compare `Result<Void, _>`, which is not `Equatable`.
    var failure: Failure? { if case .failure(let error) = self { error } else { nil } }
}
