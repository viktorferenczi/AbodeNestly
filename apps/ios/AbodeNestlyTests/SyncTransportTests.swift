import Contract
import Foundation
import Shared
import Testing
import os
@testable import Core
@testable import Sync

/// Like `StubURLProtocol`, with its own handler so the two suites can run side by side.
class SyncStubProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) throws -> (Int, Data)
    static let handler = OSAllocatedUnfairLock<Handler?>(initialState: nil)
    /// Where `startLoading` looks; a subclass answers from its own handler so suites that run side by side do not clash.
    class var store: OSAllocatedUnfairLock<Handler?> { handler }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            let (status, data) = try Self.store.withLock { $0 }!(request)
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

/// For `SyncSheetOfflineTests`, which runs in parallel with `SyncTransportTests`.
final class OfflineStubProtocol: SyncStubProtocol, @unchecked Sendable {
    static let own = OSAllocatedUnfairLock<Handler?>(initialState: nil)
    override class var store: OSAllocatedUnfairLock<Handler?> { own }
}

/// The body of a request, which `URLProtocol` exposes as a stream.
func bodyData(of request: URLRequest) -> Data {
    if let body = request.httpBody { return body }
    guard let stream = request.httpBodyStream else { return Data() }
    stream.open()
    defer { stream.close() }
    var data = Data()
    var buffer = [UInt8](repeating: 0, count: 4096)
    while stream.hasBytesAvailable {
        let count = stream.read(&buffer, maxLength: buffer.count)
        if count <= 0 { break }
        data.append(buffer, count: count)
    }
    return data
}

func makeSyncHttpService(auth: FakeAuthService = FakeAuthService(), stub: AnyClass = SyncStubProtocol.self) -> HttpApiService {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [stub]
    return HttpApiService(
        config: AppConfig(
            apiBaseURL: URL(string: "http://api.test")!, supabaseURL: URL(string: "http://supabase.test")!,
            supabaseAnonKey: "anon", authURLScheme: "abodenestly-dev", webBaseURL: URL(string: "http://localhost:5173")!
        ),
        auth: auth, urlSession: URLSession(configuration: configuration)
    )
}

@Suite("SHOP-08 sync transport", .serialized)
struct SyncTransportTests {
    private let household = UUID().uuidString.lowercased()

    @Test("SHOP-08: push sends POST /sync with the bearer and the SPEC §6.2 envelope")
    func push() async throws {
        let seen = OSAllocatedUnfairLock<[(URLRequest, Data)]>(initialState: [])
        SyncStubProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0.append((request, bodyData(of: request))) }
                return (200, Data(#"{"applied":["m1"],"rejected":[{"id":"m2","reason":"module_disabled","detail":"off"},{"id":"m3","reason":"from_the_future","detail":"x"}]}"#.utf8))
            }
        }
        let mutation = SyncMutation(id: "m1", table: "store", op: "upsert", rowId: "r1", data: ["name": .string("Lidl")], clientTs: "2026-11-23T10:00:00.000Z")
        let result = try await makeSyncHttpService().push(SyncPushBody(householdId: household, mutations: [mutation])).get()
        #expect(result.applied == ["m1"])
        #expect(result.rejected.map(\.reason) == [.moduleDisabled, .unknown("from_the_future")])
        let (request, body) = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "POST" && request.url?.absoluteString == "http://api.test/sync")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fake-token")
        let json = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect(json["household_id"] as? String == household)
        let sent = try #require((json["mutations"] as? [[String: Any]])?.first)
        #expect(sent["id"] as? String == "m1" && sent["row_id"] as? String == "r1" && sent["client_ts"] as? String == "2026-11-23T10:00:00.000Z")
        #expect((sent["data"] as? [String: Any])?["name"] as? String == "Lidl")
    }

    @Test("SHOP-08: a delete goes out without a data key")
    func deleteHasNoData() throws {
        let mutation = SyncMutation(id: "m", table: "store", op: "delete", rowId: "r", data: nil, clientTs: "t")
        let json = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(mutation)) as? [String: Any])
        #expect(json["data"] == nil)
    }

    @Test("SHOP-08: pull sends GET /sync with household, since and limit and decodes the page")
    func pull() async throws {
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        SyncStubProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (200, Data(#"{"cursor":12,"has_more":true,"changes":{"store":[{"id":"a","name":"Lidl","sync_seq":3,"deleted_at":null}]}}"#.utf8))
            }
        }
        let page = try await makeSyncHttpService().pull(householdId: household, since: 7, limit: 500).get()
        #expect(page.cursor == 12 && page.hasMore)
        #expect(page.changes["store"]?.first?["name"] == .string("Lidl"))
        #expect(page.changes["store"]?.first?["deleted_at"] == JSONValue.null)
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "GET")
        #expect(request.url?.absoluteString == "http://api.test/sync?household_id=\(household)&since=7&limit=500")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fake-token")
    }

    @Test("SHOP-08: 503 and the retryable code map to retryable, other failures to other")
    func statusMapping() async {
        let service = makeSyncHttpService()
        for (status, body, expected) in [
            (503, "", SyncFailure.retryable), (500, #"{"error":{"code":"retryable"}}"#, .retryable), (500, "", .other), (400, "", .other),
            (200, "not json", .other),
        ] {
            SyncStubProtocol.handler.withLock { $0 = { _ in (status, Data(body.utf8)) } }
            let result = await service.pull(householdId: household, since: 0, limit: 1)
            #expect(result.syncFailureValue == expected, "status \(status)")
        }
    }

    @Test("SHOP-08: an unreachable server maps to network")
    func network() async {
        SyncStubProtocol.handler.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let result = await makeSyncHttpService().push(SyncPushBody(householdId: household, mutations: []))
        #expect(result.syncFailureValue == .network)
    }

    @Test("SHOP-08: a 401 refreshes the token once and retries")
    func refreshOnce() async {
        let attempts = OSAllocatedUnfairLock(initialState: 0)
        SyncStubProtocol.handler.withLock {
            $0 = { _ in
                let attempt = attempts.withLock { n -> Int in n += 1; return n }
                return attempt == 1 ? (401, Data()) : (200, Data(#"{"cursor":0,"has_more":false,"changes":{}}"#.utf8))
            }
        }
        let auth = FakeAuthService()
        let result = await makeSyncHttpService(auth: auth).pull(householdId: household, since: 0, limit: 1)
        #expect(result.syncFailureValue == nil)
        #expect(auth.tokenRequests == [false, true])
    }

    @Test("SHOP-08: refreshSession forces a token refresh")
    func refreshSession() async {
        let auth = FakeAuthService()
        await makeSyncHttpService(auth: auth).refreshSession()
        #expect(auth.tokenRequests == [true])
    }
}

private extension Result {
    var syncFailureValue: Failure? {
        if case .failure(let error) = self { error } else { nil }
    }
}
