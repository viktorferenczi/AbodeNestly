import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync
@testable import UI

let shareWebBase = URL(string: "http://localhost:5173")!

/// A list detail on a real local database, a share sheet on it, and a fake API: SHOP-13 without a network.
@MainActor
final class ShareHarness {
    let detail: DetailHarness
    let api: FakeApiService
    let network: NetworkMonitor
    let copied: CopiedValues
    let sheet: ShareSheetModel

    final class CopiedValues { var values: [String] = [] }

    init(
        token: String? = nil, online: Bool = true, afterKick: (@Sendable (HouseholdDatabase, UUID) async -> Void)? = nil
    ) async throws {
        let seed: (HouseholdDatabase, UUID) async throws -> Void = { db, list in
            if let token { try await db.setShareToken(listId: list, token: token) }
        }
        let detail = try await DetailHarness(seed: seed, afterKick: afterKick)
        await detail.settle { detail.model.snapshot.list?.shareToken == token }
        let api = FakeApiService()
        let network = NetworkMonitor(isOnline: online)
        let copied = CopiedValues()
        self.sheet = detail.model.makeShare(
            webBase: shareWebBase,
            share: { listId in
                switch await api.shareList(listId: listId) {
                case .success(let url): return .success(url)
                case .failure: return .failure(.failed)
                }
            },
            stop: { listId in
                switch await api.stopSharing(listId: listId) {
                case .success: return .success(())
                case .failure: return .failure(.failed)
                }
            },
            isOnline: { network.isOnline }, copy: { copied.values.append($0) }
        )
        self.detail = detail
        self.api = api
        self.network = network
        self.copied = copied
    }

    var list: UUID { detail.list }

    func localToken() async throws -> String? {
        let rows = try await detail.db.queryForTesting(
            "SELECT share_token FROM shopping_list WHERE id = '\(list.uuidString.lowercased())'"
        )
        guard let text = rows.first?["share_token"], text != "NULL" else { return nil }
        return text
    }

    func outboxCount() async throws -> Int { try await detail.db.outboxForTesting().count }
}

@Suite("SHOP-13 share logic")
struct ShareLogicTests {
    @Test("SHOP-13: the token is the last path segment of the API's URL, percent-decoded", arguments: [
        ("http://api.test/shared/abc123", "abc123"),
        ("https://api.abodenestly.app/shared/abc123/", "abc123"),
        ("http://api.test/shared/a%20b%2Fc", "a b/c"),
        ("/shared/xyz", "xyz"),
    ])
    func token(url: String, expected: String) {
        #expect(Share.token(fromURL: url) == expected)
    }

    @Test("SHOP-13: a URL with no path has no token", arguments: ["http://api.test", "http://api.test/", "", "not a url"])
    func noToken(url: String) {
        #expect(Share.token(fromURL: url) == nil)
    }

    @Test("SHOP-13: the link is WEB_BASE_URL/shared/<token>, whatever the base's trailing slash")
    func link() {
        #expect(Share.link(webBase: shareWebBase, token: "tok").absoluteString == "http://localhost:5173/shared/tok")
        #expect(Share.link(webBase: URL(string: "https://app.test/")!, token: "tok").absoluteString == "https://app.test/shared/tok")
        #expect(Share.link(webBase: shareWebBase, token: "a b/c").absoluteString == "http://localhost:5173/shared/a%20b%2Fc")
    }

    @Test("SHOP-13: the state moves off, busy, on, busy, off and keeps the old answer on a failure")
    func reducer() {
        let off = ShareState(token: nil)
        #expect(!off.isOn && !off.isBusy)
        let turning = off.begin(.turningOn)
        #expect(turning.isBusy && !turning.isOn)
        let on = turning.turnedOn(token: "tok")
        #expect(on.isOn && !on.isBusy && on.error == nil)
        #expect(on.begin(.stopping).isBusy && on.begin(.stopping).isOn)
        #expect(on.begin(.stopping).failed(.stop) == ShareState(token: "tok", busy: nil, error: .stop))
        #expect(turning.failed(.turnOn) == ShareState(token: nil, busy: nil, error: .turnOn))
        #expect(on.stopped() == off)
    }

    @Test("SHOP-13: the error texts match the web twin")
    func messages() {
        #expect(ShareError.turnOn.message == "Could not turn on sharing. Try again.")
        #expect(ShareError.stop.message == "Could not stop sharing. Try again.")
    }
}

@MainActor
@Suite("SHOP-13 share sheet", .serialized)
struct ShareSheetTests {
    @Test("SHOP-13: AC1 with no local share_token the sheet starts off with no link")
    func startsOff() async throws {
        let h = try await ShareHarness()
        #expect(!h.sheet.state.isOn)
        #expect(h.sheet.link == nil)
    }

    @Test("SHOP-13: a local share_token starts the sheet on, with the link built from WEB_BASE_URL")
    func startsOn() async throws {
        let h = try await ShareHarness(token: "tok123")
        #expect(h.sheet.state.isOn)
        #expect(h.sheet.link?.absoluteString == "http://localhost:5173/shared/tok123")
    }

    @Test("SHOP-13: AC4 offline the switch does nothing and the API is not called")
    func offline() async throws {
        let h = try await ShareHarness(online: false)
        #expect(!h.sheet.canChange)
        await h.sheet.setOn(true)
        #expect(h.api.calls.isEmpty)
        #expect(!h.sheet.state.isOn && h.sheet.state.error == nil)
    }

    @Test("SHOP-13: AC4 offline Stop sharing does nothing, and the known link stays and can be copied")
    func offlineStop() async throws {
        let h = try await ShareHarness(token: "tok123", online: false)
        await h.sheet.turnOff()
        #expect(h.api.calls.isEmpty)
        #expect(h.sheet.state.isOn)
        h.sheet.copyLink()
        #expect(h.copied.values == ["http://localhost:5173/shared/tok123"])
    }

    @Test("SHOP-13: AC2 turning on POSTs, shows the web link and writes share_token locally with no outbox row and one kick")
    func turnOn() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.success("http://api.test/shared/tok123"))
        let before = try await h.outboxCount()
        await h.sheet.setOn(true)
        #expect(h.api.calls == [.shareList(listId: h.list)])
        #expect(h.sheet.state == ShareState(token: "tok123"))
        #expect(h.sheet.link?.absoluteString == "http://localhost:5173/shared/tok123")
        #expect(try await h.localToken() == "tok123")
        #expect(try await h.outboxCount() == before)
        #expect(await h.detail.kickCount == 1)
        await h.detail.settle { h.detail.model.snapshot.list?.shareToken == "tok123" }
        #expect(h.detail.model.snapshot.list?.shareToken == "tok123")
    }

    @Test("SHOP-13: a pull that writes the old token during the kick does not have the last word")
    func stalePullDuringKick() async throws {
        // A page read before the POST committed writes share_token = NULL back while the kick runs.
        let h = try await ShareHarness(afterKick: { db, list in try? await db.setShareToken(listId: list, token: nil) })
        h.api.setShareResult(.success("http://api.test/shared/tok123"))
        await h.sheet.setOn(true)
        #expect(await h.detail.kickCount == 1)
        #expect(try await h.localToken() == "tok123")
    }

    @Test("SHOP-13: a pull that writes the old token back after Stop sharing does not have the last word")
    func stalePullDuringStop() async throws {
        let h = try await ShareHarness(token: "tok123", afterKick: { db, list in try? await db.setShareToken(listId: list, token: "tok123") })
        await h.sheet.turnOff()
        #expect(try await h.localToken() == nil)
    }

    @Test("SHOP-13: AC3 Stop sharing DELETEs, the switch goes off and the local token is cleared with no outbox row and one kick")
    func turnOff() async throws {
        let h = try await ShareHarness(token: "tok123")
        let before = try await h.outboxCount()
        await h.sheet.turnOff()
        #expect(h.api.calls == [.stopSharing(listId: h.list)])
        #expect(h.sheet.state == ShareState(token: nil))
        #expect(h.sheet.link == nil)
        #expect(try await h.localToken() == nil)
        #expect(try await h.outboxCount() == before)
        #expect(await h.detail.kickCount == 1)
    }

    @Test("SHOP-13: turning the switch off is the same as Stop sharing")
    func switchOff() async throws {
        let h = try await ShareHarness(token: "tok123")
        await h.sheet.setOn(false)
        #expect(h.api.calls == [.stopSharing(listId: h.list)])
        #expect(!h.sheet.state.isOn)
    }

    @Test("SHOP-13: a failed POST returns the switch to off with the error, and writes nothing locally")
    func turnOnFails() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.failure(.unknown))
        await h.sheet.setOn(true)
        #expect(h.sheet.state == ShareState(token: nil, busy: nil, error: .turnOn))
        #expect(try await h.localToken() == nil)
        #expect(await h.detail.kickCount == 0)
    }

    @Test("SHOP-13: a POST whose URL has no token is a failure too")
    func turnOnNoToken() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.success("http://api.test/"))
        await h.sheet.setOn(true)
        #expect(h.sheet.state.error == .turnOn && !h.sheet.state.isOn)
        #expect(try await h.localToken() == nil)
    }

    @Test("SHOP-13: a failed DELETE keeps the switch on with the error, and keeps the local token")
    func turnOffFails() async throws {
        let h = try await ShareHarness(token: "tok123")
        h.api.setStopResult(.failure(.network))
        await h.sheet.turnOff()
        #expect(h.sheet.state == ShareState(token: "tok123", busy: nil, error: .stop))
        #expect(try await h.localToken() == "tok123")
        #expect(await h.detail.kickCount == 0)
    }

    @Test("SHOP-13: a new attempt clears the old error")
    func retryClearsError() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.failure(.unknown))
        await h.sheet.setOn(true)
        #expect(h.sheet.state.error == .turnOn)
        h.api.setShareResult(.success("http://api.test/shared/tok9"))
        await h.sheet.setOn(true)
        #expect(h.sheet.state == ShareState(token: "tok9"))
    }

    @Test("SHOP-13: the switch is not usable while a call runs, so a second tap makes no second call")
    func busy() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.success("http://api.test/shared/tok1"))
        h.api.hold()
        let first = Task { await h.sheet.setOn(true) }
        await h.detail.settle { h.sheet.state.isBusy }
        #expect(h.sheet.state.busy == .turningOn && !h.sheet.canChange)
        await h.sheet.setOn(true)
        h.api.release()
        await first.value
        #expect(h.api.calls == [.shareList(listId: h.list)])
    }

    @Test("SHOP-13: Copy link copies the web link and shows Link copied until the window ends")
    func copy() async throws {
        let copied = ShareHarness.CopiedValues()
        let model = ShareSheetModel(
            initialToken: "tok123", webBase: shareWebBase, share: { .failure(.failed) }, stop: { .failure(.failed) },
            record: { _ in }, isOnline: { true }, copy: { copied.values.append($0) }, copiedWindow: .milliseconds(30)
        )
        model.copyLink()
        #expect(copied.values == ["http://localhost:5173/shared/tok123"])
        #expect(model.copied)
        for _ in 0..<400 where model.copied { try await Task.sleep(for: .milliseconds(5)) }
        #expect(!model.copied)
    }

    @Test("SHOP-13: with no link there is nothing to copy")
    func copyNothing() async throws {
        let h = try await ShareHarness()
        h.sheet.copyLink()
        #expect(h.copied.values.isEmpty && !h.sheet.copied)
    }

    @Test("SHOP-13: setShareToken throws for a list that is not here")
    func missingRow() async throws {
        let h = try await ShareHarness()
        await #expect(throws: SyncWriteError.self) { try await h.detail.db.setShareToken(listId: UUID(), token: "x") }
    }
}

/// Its own handler, so this suite can run beside the other URLProtocol suites.
final class ShareStubProtocol: SyncStubProtocol, @unchecked Sendable {
    static let own = OSAllocatedUnfairLock<Handler?>(initialState: nil)
    override class var store: OSAllocatedUnfairLock<Handler?> { own }
}

@Suite("SHOP-13 share transport", .serialized)
struct ShareTransportTests {
    private let list = UUID()

    @Test("SHOP-13: shareList sends POST /lists/{id}/share and returns the URL")
    func share() async throws {
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        ShareStubProtocol.own.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (200, Data(#"{"url":"http://api.test/shared/tok123"}"#.utf8))
            }
        }
        let result = await makeSyncHttpService(stub: ShareStubProtocol.self).shareList(listId: list)
        #expect(try result.get() == "http://api.test/shared/tok123")
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "POST")
        #expect(request.url?.absoluteString == "http://api.test/lists/\(list.uuidString.lowercased())/share")
    }

    @Test("SHOP-13: stopSharing sends DELETE /lists/{id}/share and a 204 is success")
    func stop() async throws {
        let seen = OSAllocatedUnfairLock<[URLRequest]>(initialState: [])
        ShareStubProtocol.own.withLock {
            $0 = { request in
                seen.withLock { $0.append(request) }
                return (204, Data())
            }
        }
        let result = await makeSyncHttpService(stub: ShareStubProtocol.self).stopSharing(listId: list)
        try result.get()
        let request = try #require(seen.withLock { $0.first })
        #expect(request.httpMethod == "DELETE")
        #expect(request.url?.absoluteString == "http://api.test/lists/\(list.uuidString.lowercased())/share")
    }

    @Test("SHOP-13: failures map by the API's code, then by status", arguments: [
        (403, "forbidden", ApiFailure.forbidden), (403, "module_disabled", ApiFailure.moduleDisabled),
        (404, "not_found", ApiFailure.notFound), (400, "validation", ApiFailure.validation),
        (500, "internal", ApiFailure.unknown), (403, "", ApiFailure.forbidden), (404, "", ApiFailure.notFound),
        (400, "", ApiFailure.validation), (500, "", ApiFailure.unknown),
    ])
    func failures(status: Int, code: String, expected: ApiFailure) {
        #expect(HttpApiService.shareFailure(status: status, code: code.isEmpty ? nil : code) == expected)
    }

    @Test("SHOP-13: a 200 without a url is unknown, and a dead network is .network")
    func bad() async {
        ShareStubProtocol.own.withLock { $0 = { _ in (200, Data("{}".utf8)) } }
        let service = makeSyncHttpService(stub: ShareStubProtocol.self)
        #expect(await service.shareList(listId: list) == .failure(.unknown))
        ShareStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        #expect(await service.shareList(listId: list) == .failure(.network))
        let stopped: Result<Void, ApiFailure> = await service.stopSharing(listId: list)
        guard case .failure(let failure) = stopped else {
            Issue.record("a dead network is a failure")
            return
        }
        #expect(failure == .network)
    }
}
