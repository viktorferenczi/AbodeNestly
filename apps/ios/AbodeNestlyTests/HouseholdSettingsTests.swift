import Contract
import Foundation
import os
import Shared
import Testing
@testable import Core

@MainActor
final class SettingsRecorder {
    var changed: [LandingHousehold] = []
    var leftCount = 0
}

@MainActor
struct SettingsHarness {
    let householdId = UUID()
    let me = UUID()
    let api = FakeApiService()
    let online = OnlineBox()
    let cache = HouseholdCache(suiteName: "abodenestly.tests.household.\(UUID().uuidString)")
    let recorder = SettingsRecorder()
    let model: HouseholdSettingsModel

    init(role: Member.Role = .owner, onChanged: (@MainActor (LandingHousehold) -> Void)? = nil) {
        let box = online, recorder = recorder
        model = HouseholdSettingsModel(
            householdId: householdId, userId: me, api: api, cache: cache,
            isOnline: { box.value },
            onHouseholdChanged: { recorder.changed.append($0); onChanged?($0) },
            onLeft: { recorder.leftCount += 1 }
        )
        api.setHouseholdResult(.success(HouseholdWithRole(household: makeHousehold(id: householdId), role: role)))
    }

    var updateModulesCalls: [FakeApiService.Call] {
        api.calls.filter { if case .updateModules = $0 { true } else { false } }
    }
}

@MainActor
@Suite("CORE-05 household settings model")
struct HouseholdSettingsTests {
    @Test("CORE-05: AC3 a successful toggle sends only {pantry:false} and the session flags behind the tab bar follow")
    func toggleUpdatesTabs() async {
        let suite = "HouseholdSettingsTests-\(UUID())"
        let session = SessionModel(
            auth: FakeAuthService(), lastHousehold: LastHouseholdStore(suiteName: suite),
            profileCache: ProfileCache(suiteName: suite), isOnline: { true }
        )
        let h = SettingsHarness(onChanged: { session.updateHousehold($0) })
        session.enter(LandingHousehold(id: h.householdId, moduleShopping: true, modulePantry: true, moduleMaintenance: true))
        await h.model.load()
        var saved = makeHousehold(id: h.householdId)
        saved.modulePantry = false
        h.api.setUpdateModulesResult(.success(saved))

        await h.model.toggle(.pantry, on: false)

        #expect(h.updateModulesCalls == [
            .updateModules(householdId: h.householdId, shopping: nil, pantry: false, maintenance: nil)
        ])
        #expect(session.household?.modulePantry == false)
        #expect(session.household?.moduleShopping == true)
        #expect(h.model.household?.modulePantry == false)
        #expect(h.model.state == .idle)
        #expect(h.model.optimistic == nil)
    }

    @Test("CORE-05: a failed toggle leaves the flags unchanged and reports the failure")
    func toggleFailure() async {
        let h = SettingsHarness()
        await h.model.load()
        h.api.setUpdateModulesResult(.failure(.network))
        await h.model.toggle(.pantry, on: false)
        #expect(h.model.household?.modulePantry == true)
        #expect(h.model.state == .error(.network))
        #expect(h.model.saveErrorMessage == "Couldn't save. Check your connection and try again.")
        #expect(h.recorder.changed.isEmpty)
    }

    @Test("CORE-05: a forbidden toggle reloads, so a demoted owner sees the read-only screen")
    func forbiddenReloads() async {
        let h = SettingsHarness()
        await h.model.load()
        h.api.setUpdateModulesResult(.failure(.forbidden))
        h.api.setHouseholdResult(.success(HouseholdWithRole(household: makeHousehold(id: h.householdId), role: .member)))
        await h.model.toggle(.pantry, on: false)
        #expect(h.model.role == .member)
        #expect(h.model.saveErrorMessage == "Only owners can change household settings.")
    }

    @Test("CORE-05: members and offline viewers cannot change anything")
    func readOnly() async {
        let member = SettingsHarness(role: .member)
        await member.model.load()
        await member.model.toggle(.pantry, on: false)
        await member.model.setCurrency("USD")
        #expect(member.api.calls == [.fetchHousehold(householdId: member.householdId, userId: member.me)])

        let owner = SettingsHarness()
        await owner.model.load()
        owner.online.value = false
        await owner.model.toggle(.pantry, on: false)
        #expect(owner.updateModulesCalls.isEmpty)
    }

    @Test("CORE-05: the name is saved trimmed, only when non-empty and changed")
    func commitName() async {
        let h = SettingsHarness()
        await h.model.load()
        var renamed = makeHousehold(id: h.householdId, name: "Casa")
        renamed.currency = "EUR"
        h.api.setUpdateHouseholdResult(.success(renamed))

        h.model.nameDraft = "   "
        await h.model.commitName()
        h.model.nameDraft = "Test Home"
        await h.model.commitName()
        #expect(h.api.calls.filter { if case .updateHousehold = $0 { true } else { false } }.isEmpty)

        h.model.nameDraft = "  Casa  "
        await h.model.commitName()
        #expect(h.api.calls.last == .updateHousehold(householdId: h.householdId, name: "Casa", currency: nil))
        #expect(h.model.household?.name == "Casa")
        #expect(h.model.nameDraft == nil)
    }

    @Test("CORE-05: changing the currency is optimistic while saving, then replaced by the saved row")
    func currency() async {
        let h = SettingsHarness()
        await h.model.load()
        var saved = makeHousehold(id: h.householdId)
        saved.currency = "USD"
        h.api.setUpdateHouseholdResult(.success(saved))
        h.api.hold()
        let task = Task { await h.model.setCurrency("USD") }
        await waitFor { h.model.isSaving }
        #expect(h.model.shown?.currency == "USD")
        #expect(h.model.household?.currency == "EUR")
        h.api.release()
        await task.value
        #expect(h.model.household?.currency == "USD")
    }

    @Test("CORE-05: load falls back to the user's cached snapshot offline and never reads another user's")
    func cacheUserKeyed() async {
        let h = SettingsHarness()
        await h.model.load()
        #expect(h.cache.read(userId: h.me, householdId: h.householdId)?.role == .owner)
        #expect(h.cache.read(userId: UUID(), householdId: h.householdId) == nil)
        #expect(HouseholdCache.key(userId: h.me, householdId: h.householdId)
            == "abodenestly.household.\(h.me.uuidString.lowercased()).\(h.householdId.uuidString.lowercased())")

        // A fresh model (app relaunch) offline shows the snapshot.
        let box = OnlineBox(false)
        let offline = HouseholdSettingsModel(
            householdId: h.householdId, userId: h.me, api: FakeApiService(), cache: h.cache,
            isOnline: { box.value }, onHouseholdChanged: { _ in }, onLeft: {}
        )
        await offline.load()
        #expect(offline.loadState == .loaded)
        #expect(offline.role == .owner)

        let stranger = HouseholdSettingsModel(
            householdId: h.householdId, userId: UUID(), api: FakeApiService(), cache: h.cache,
            isOnline: { box.value }, onHouseholdChanged: { _ in }, onLeft: {}
        )
        await stranger.load()
        #expect(stranger.loadState == .unavailable)
    }

    @Test("CORE-05: AC5 leave removes me, clears the snapshot and reports left; Not found counts as done")
    func leave() async {
        for result in [Result<Void, ApiFailure>.success(()), .failure(.notFound)] {
            let h = SettingsHarness()
            await h.model.load()
            h.api.setRemoveResult(result)
            h.model.beginLeave()
            await h.model.leave()
            #expect(h.api.calls.last == .removeMember(householdId: h.householdId, userId: h.me))
            #expect(h.recorder.leftCount == 1)
            #expect(h.cache.read(userId: h.me, householdId: h.householdId) == nil)
            #expect(!h.model.isConfirmingLeave)
        }
    }

    @Test("CORE-05: the last owner is refused inside the sheet, other failures get a generic message")
    func leaveErrors() async {
        let h = SettingsHarness()
        await h.model.load()
        h.model.beginLeave()
        h.api.setRemoveResult(.failure(.lastOwner))
        await h.model.leave()
        #expect(h.model.leaveErrorMessage == HouseholdSettingsModel.lastOwnerMessage)
        #expect(h.model.saveErrorMessage == nil)
        #expect(h.recorder.leftCount == 0)
        h.api.setRemoveResult(.failure(.unknown))
        await h.model.leave()
        #expect(h.model.leaveErrorMessage == "Couldn't leave the household. Try again.")
        h.model.cancelLeave()
        #expect(h.model.state == .idle)
    }

    @Test("CORE-05: mode table, offline wins over role", arguments: [
        (Member.Role.owner, true, SettingsMode.owner), (.member, true, .member), (.owner, false, .offline),
        (.member, false, .offline), (nil, true, .member),
    ] as [(Member.Role?, Bool, SettingsMode)])
    func mode(role: Member.Role?, online: Bool, expected: SettingsMode) {
        #expect(HouseholdSettingsModel.settingsMode(role: role, online: online) == expected)
    }

    @Test("CORE-05: currencyOptions keeps the short list and adds an unknown current value in order")
    func currencyOptions() {
        #expect(HouseholdSettingsModel.currencyOptions(current: "EUR") == HouseholdSettingsModel.currencies)
        let options = HouseholdSettingsModel.currencyOptions(current: "BRL")
        #expect(options.count == HouseholdSettingsModel.currencies.count + 1)
        #expect(options == options.sorted())
        #expect(options.contains("BRL"))
    }

    @Test("CORE-05: withModule changes only the named flag")
    func withModule() {
        let household = makeHousehold()
        let off = HouseholdSettingsModel.withModule(household, .maintenance, on: false)
        #expect(!off.moduleMaintenance && off.moduleShopping && off.modulePantry)
    }

    @Test("CORE-05: updateHousehold on the session ignores another household")
    func sessionIgnoresOtherHousehold() {
        let suite = "HouseholdSettingsTests-\(UUID())"
        let session = SessionModel(
            auth: FakeAuthService(), lastHousehold: LastHouseholdStore(suiteName: suite),
            profileCache: ProfileCache(suiteName: suite), isOnline: { true }
        )
        let mine = LandingHousehold(id: UUID(), moduleShopping: true, modulePantry: true, moduleMaintenance: true)
        session.enter(mine)
        session.updateHousehold(LandingHousehold(id: UUID(), moduleShopping: false, modulePantry: false, moduleMaintenance: false))
        #expect(session.household == mine)
    }
}

/// Own stub, so these tests never race the shared handler of `StubURLProtocol` in other suites.
final class SettingsStubURLProtocol: URLProtocol, @unchecked Sendable {
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

@Suite("CORE-05 http api service", .serialized)
struct HouseholdSettingsHttpTests {
    private let config = AppConfig(
        apiBaseURL: URL(string: "http://api.test")!, supabaseURL: URL(string: "http://supabase.test")!,
        supabaseAnonKey: "anon", authURLScheme: "abodenestly-dev",
        webBaseURL: URL(string: "http://localhost:5173")!
    )

    private func makeService() -> HttpApiService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SettingsStubURLProtocol.self]
        return HttpApiService(config: config, auth: FakeAuthService(), urlSession: URLSession(configuration: configuration))
    }

    private static func householdJSON(id: UUID, pantry: Bool = true) -> String {
        """
        {"id":"\(id)","name":"Test Home","created_by":"\(UUID())","module_shopping":true,"module_pantry":\(pantry),
         "module_maintenance":true,"currency":"EUR","created_at":"2026-11-09T20:31:12.123456+00:00",
         "updated_at":"2026-11-09T20:31:12+00:00","deleted_at":null,"sync_seq":2}
        """
    }

    @Test("CORE-05: updateModules PATCHes /households/{id}/modules with only the given flags and decodes the 200")
    func updateModulesSuccess() async throws {
        let id = UUID()
        let seen = OSAllocatedUnfairLock<URLRequest?>(initialState: nil)
        SettingsStubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0 = request }
                return (200, Data(Self.householdJSON(id: id, pantry: false).utf8))
            }
        }
        let result = await makeService().updateModules(householdId: id, shopping: nil, pantry: false, maintenance: nil)
        #expect(try result.get().modulePantry == false)
        let request = try #require(seen.withLock { $0 })
        #expect(request.httpMethod == "PATCH")
        #expect(request.url?.path == "/households/\(id.uuidString.lowercased())/modules")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fake-token")
        let body = try #require(request.httpBody ?? request.httpBodyStream.map { Data(reading: $0) })
        #expect(try JSONSerialization.jsonObject(with: body) as? [String: Bool] == ["pantry": false])
    }

    @Test("CORE-05: updateModules maps 403 to forbidden, 400 to validation, other statuses and transport errors")
    func updateModulesErrors() async {
        for (status, expected) in [(403, ApiFailure.forbidden), (400, .validation), (500, .unknown)] {
            SettingsStubURLProtocol.handler.withLock { $0 = { _ in (status, Data(#"{"error":{"code":"x"}}"#.utf8)) } }
            let result = await makeService().updateModules(householdId: UUID(), shopping: true, pantry: nil, maintenance: nil)
            #expect(result == .failure(expected), "\(status)")
        }
        SettingsStubURLProtocol.handler.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        #expect(await makeService().updateModules(householdId: UUID(), shopping: true, pantry: nil, maintenance: nil) == .failure(.network))
    }

    @Test("CORE-05: fetchHousehold queries the viewer's active membership and decodes role + household")
    func fetchHousehold() async throws {
        let id = UUID(), user = UUID()
        let seen = OSAllocatedUnfairLock<URL?>(initialState: nil)
        SettingsStubURLProtocol.handler.withLock {
            $0 = { request in
                seen.withLock { $0 = request.url }
                return (200, Data(#"[{"role":"owner","household":\#(Self.householdJSON(id: id))}]"#.utf8))
            }
        }
        let found = try await makeService().fetchHousehold(householdId: id, userId: user).get()
        #expect(found?.role == .owner)
        #expect(found?.household.id == id)
        let query = try #require(seen.withLock { $0 }?.query)
        #expect(query.contains("left_at=is.null"))
        #expect(query.contains("household.deleted_at=is.null"))
        #expect(query.contains("user_id=eq.\(user.uuidString.lowercased())"))

        SettingsStubURLProtocol.handler.withLock { $0 = { _ in (200, Data("[]".utf8)) } }
        #expect(try await makeService().fetchHousehold(householdId: id, userId: user).get() == nil)
    }

    @Test("CORE-05: updateHousehold returns the row; 42501 is forbidden; an empty array is unknown")
    func updateHousehold() async throws {
        let id = UUID()
        SettingsStubURLProtocol.handler.withLock { $0 = { _ in (200, Data("[\(Self.householdJSON(id: id))]".utf8)) } }
        #expect(try await makeService().updateHousehold(householdId: id, name: "X", currency: nil).get().id == id)

        SettingsStubURLProtocol.handler.withLock { $0 = { _ in (403, Data(#"{"code":"42501","message":"denied"}"#.utf8)) } }
        #expect(await makeService().updateHousehold(householdId: id, name: "X", currency: nil) == .failure(.forbidden))

        SettingsStubURLProtocol.handler.withLock { $0 = { _ in (200, Data("[]".utf8)) } }
        #expect(await makeService().updateHousehold(householdId: id, name: "X", currency: nil) == .failure(.unknown))
    }
}

private extension Data {
    /// `URLProtocol` receives bodies as streams.
    init(reading stream: InputStream) {
        self.init()
        stream.open()
        defer { stream.close() }
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            append(buffer, count: count)
        }
    }
}
