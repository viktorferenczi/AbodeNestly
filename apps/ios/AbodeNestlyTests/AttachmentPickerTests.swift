import Contract
import Foundation
import Testing
@testable import Core
@testable import Sync

@MainActor
struct AttachmentHarness {
    let householdId = UUID()
    let entityId = UUID()
    let user = UUID()
    let root = switcherTempRoot()
    let database: HouseholdDatabase
    let api = FakeApiService()
    let auth = FakeAuthService()
    let online: OnlineBox
    let model: AttachmentPicker

    init(online isOnline: Bool = true, entity: AttachmentEntity = .pantryItem) async throws {
        let database = HouseholdDatabase(rootURL: root)
        let id = householdId
        try await database.open(householdId: id)
        self.database = database
        let box = OnlineBox(isOnline)
        online = box
        auth.setAttachmentURL(URL(string: "https://signed.test/photo.jpg"))
        model = AttachmentPicker(
            householdId: id, entity: entity, entityId: entityId, userId: user, database: database, api: api, auth: auth,
            isOnline: { box.value }
        )
    }

    func pendingCount() async throws -> Int {
        try await database.pendingAttachmentUploads(householdId: householdId).count
    }

    func stored(_ entity: AttachmentEntity = .pantryItem) async throws -> [Contract.Attachment] {
        try await database.attachments(householdId: householdId, entity: entity.rawValue, entityId: entityId)
    }
}

let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])

@MainActor
@Suite("CORE-11 attachment picker")
struct AttachmentPickerTests {
    @Test("CORE-11: isAllowed mirrors the API limits")
    func allowed() {
        let mb = 1024 * 1024
        let cases: [(mime: String, size: Int, expected: Bool)] = [
            ("image/jpeg", 1, true), ("image/png", 1, true), ("image/webp", 1, true), ("image/heic", 1, true),
            ("application/pdf", 25 * mb, true), ("application/pdf", 25 * mb + 1, false),
            ("image/gif", 1, false), ("text/plain", 1, false), ("image/jpeg", 0, false),
        ]
        for c in cases {
            #expect(AttachmentPicker.isAllowed(mime: c.mime, size: c.size) == c.expected, "\(c.mime) \(c.size)")
        }
    }

    @Test("CORE-11: AC4 offline writes an outbox row with the bytes and never calls sign")
    func offlineQueues() async throws {
        let h = try await AttachmentHarness(online: false)
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")

        #expect(h.model.state == .queued)
        #expect(h.api.calls.isEmpty)
        let rows = try await h.database.pendingAttachmentUploads(householdId: h.householdId)
        #expect(rows.count == 1)
        #expect(rows[0].filename == "milk.jpg" && rows[0].mime == "image/jpeg" && rows[0].size == 4)
        #expect(rows[0].entity == "pantry_item" && rows[0].entityId == h.entityId)
        #expect(try await h.database.attachmentBytes(of: rows[0]) == jpeg)
        #expect(try await h.stored().isEmpty)
    }

    @Test("CORE-11: AC1/AC2 online signs, PUTs the bytes and records the attachment")
    func onlineUploads() async throws {
        let h = try await AttachmentHarness()
        let attachmentId = UUID()
        let target = URL(string: "https://upload.test/o")!
        h.api.setSignResult(.success(SignedUpload(attachmentId: attachmentId, uploadURL: target, storagePath: "h/pantry_item/e/a.jpg")))
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")

        #expect(h.model.state == .idle)
        #expect(h.api.calls == [
            .signAttachment(
                householdId: h.householdId, entity: "pantry_item", entityId: h.entityId, filename: "milk.jpg",
                mime: "image/jpeg", size: 4
            ),
            .upload(url: target, byteCount: 4, mime: "image/jpeg"),
        ])
        let rows = try await h.stored()
        #expect(rows.count == 1)
        #expect(rows[0].id == attachmentId && rows[0].createdBy == h.user && rows[0].mime == "image/jpeg" && rows[0].sizeBytes == 4)
        #expect(h.model.attachments == rows)
        #expect(h.model.thumbnails[attachmentId] != nil)
        #expect(try await h.pendingCount() == 0)
    }

    @Test("CORE-11: AC5 a sign failure is an error, records nothing, and Retry succeeds")
    func signFailureRetry() async throws {
        let h = try await AttachmentHarness()
        h.api.setSignResult(.failure(.unknown))
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(h.model.state == .error(AttachmentPicker.failedMessage))
        #expect(try await h.stored().isEmpty)
        #expect(try await h.pendingCount() == 0)

        h.api.setSignResult(.success(SignedUpload(attachmentId: UUID(), uploadURL: URL(string: "https://upload.test/o")!, storagePath: "p/a.jpg")))
        await h.model.retry()
        #expect(h.model.state == .idle)
        #expect(try await h.stored().count == 1)
    }

    @Test("CORE-11: a module-off sign failure says so")
    func moduleOff() async throws {
        let h = try await AttachmentHarness()
        h.api.setSignResult(.failure(.moduleDisabled))
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(h.model.state == .error(AttachmentPicker.moduleOffMessage))
    }

    @Test("CORE-11: a network failure while signing falls back to the queue")
    func networkFallsBack() async throws {
        let h = try await AttachmentHarness()
        h.api.setSignResult(.failure(.network))
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(h.model.state == .queued)
        #expect(try await h.pendingCount() == 1)
    }

    @Test("CORE-11: a failed PUT is an error, records nothing, and Retry signs again")
    func putFailure() async throws {
        let h = try await AttachmentHarness()
        h.api.setUploadResult(.failure(.unknown))
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(h.model.state == .error(AttachmentPicker.failedMessage))
        #expect(try await h.stored().isEmpty)

        h.api.setUploadResult(.success(()))
        await h.model.retry()
        #expect(h.model.state == .idle)
        let signs = h.api.calls.filter { if case .signAttachment = $0 { true } else { false } }
        #expect(signs.count == 2)
    }

    @Test("CORE-11: a file the API would reject is refused without a request")
    func rejected() async throws {
        let h = try await AttachmentHarness()
        await h.model.pick(data: jpeg, filename: "a.gif", mime: "image/gif")
        #expect(h.model.state == .error(AttachmentPicker.rejectedMessage))
        #expect(h.api.calls.isEmpty)
    }

    @Test("CORE-11: going online drains the queue in order and deletes each row and its file")
    func drains() async throws {
        let h = try await AttachmentHarness(online: false)
        await h.model.pick(data: jpeg, filename: "first.jpg", mime: "image/jpeg")
        await h.model.pick(data: Data("%PDF".utf8), filename: "second.pdf", mime: "application/pdf")
        let queuedFiles = try await h.database.pendingAttachmentUploads(householdId: h.householdId)
        #expect(queuedFiles.map(\.filename) == ["first.jpg", "second.pdf"])

        h.online.value = true
        await h.model.drain()

        #expect(h.model.state == .idle)
        #expect(try await h.pendingCount() == 0)
        let signed = h.api.calls.compactMap { call -> String? in
            if case .signAttachment(_, _, _, let name, _, _) = call { name } else { nil }
        }
        #expect(signed == ["first.jpg", "second.pdf"])
        for row in queuedFiles {
            #expect(!FileManager.default.fileExists(atPath: h.root.appendingPathComponent(row.filePath).path))
        }
    }

    @Test("CORE-11: a drain that fails for a non-network reason keeps the row and shows an error")
    func drainFailureKeepsRow() async throws {
        let h = try await AttachmentHarness(online: false)
        await h.model.pick(data: jpeg, filename: "first.jpg", mime: "image/jpeg")
        h.online.value = true
        h.api.setSignResult(.failure(.validation))
        await h.model.drain()
        #expect(h.model.state == .error(AttachmentPicker.failedMessage))
        #expect(try await h.pendingCount() == 1)
    }

    @Test("CORE-11: a drain stays offline-quiet when the network drops")
    func drainOffline() async throws {
        let h = try await AttachmentHarness(online: false)
        await h.model.pick(data: jpeg, filename: "first.jpg", mime: "image/jpeg")
        await h.model.drain()
        #expect(h.api.calls.isEmpty)
        #expect(h.model.state == .queued)
    }
}
