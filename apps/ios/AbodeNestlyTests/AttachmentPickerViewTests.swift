import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core

@MainActor
private struct ViewHarness {
    let base: AttachmentHarness

    init(online: Bool = true, entity: AttachmentEntity = .pantryItem) async throws {
        base = try await AttachmentHarness(online: online, entity: entity)
    }

    var model: AttachmentPicker { base.model }

    func has(_ id: String, enabled: Bool = true) -> Bool {
        (try? AttachmentPickerView(model: model, enabled: enabled).inspect().find(viewWithAccessibilityIdentifier: id)) != nil
    }

    func text(_ id: String) throws -> String {
        try AttachmentPickerView(model: model).inspect().find(viewWithAccessibilityIdentifier: id).text().string()
    }
}

@MainActor
@Suite("CORE-11 attachment control view")
struct AttachmentPickerViewTests {
    @Test("CORE-11: idle shows both add buttons and no items")
    func idle() async throws {
        let h = try await ViewHarness()
        #expect(h.has("attachments.photo") && h.has("attachments.pdf") && h.has("attachments.idle"))
        #expect(!h.has("attachments.item") && !h.has("attachments.error"))
    }

    @Test("CORE-11: AC1 a held upload shows the uploading state")
    func uploading() async throws {
        let h = try await ViewHarness()
        h.base.api.hold()
        let pick = Task { await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg") }
        while h.model.state != .uploading { await Task.yield() }
        #expect(try h.text("attachments.uploading") == "Uploading…")
        #expect(!h.has("attachments.idle"))
        h.base.api.release()
        await pick.value
        #expect(h.has("attachments.idle"))
    }

    @Test("CORE-11: AC2 after the upload the control is idle and lists the photo")
    func photoListed() async throws {
        let h = try await ViewHarness()
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(h.has("attachments.idle"))
        #expect(h.has("attachments.item") && h.model.attachments.count == 1)
    }

    @Test("CORE-11: AC3 a PDF on a log entry is listed as a filename row")
    func pdfListed() async throws {
        let h = try await ViewHarness(entity: .maintLog)
        h.base.api.setSignResult(.success(SignedUpload(
            attachmentId: UUID(), uploadURL: URL(string: "https://upload.test/o")!, storagePath: "h/maint_log/e/manual.pdf"
        )))
        await h.model.pick(data: Data("%PDF".utf8), filename: "manual.pdf", mime: "application/pdf")
        #expect(h.has("attachments.item") && h.model.attachments.count == 1)
        let row = try AttachmentPickerView(model: h.model).inspect().find(text: "manual.pdf")
        #expect(try row.string() == "manual.pdf")
    }

    @Test("CORE-11: AC4 offline shows the queued state and the queued file")
    func queued() async throws {
        let h = try await ViewHarness(online: false)
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(try h.text("attachments.queued") == "Will upload when you're online")
        #expect(h.has("attachments.queuedItem"))
        #expect(h.base.api.calls.isEmpty)
    }

    @Test("CORE-11: AC5 a failure shows the error and Retry, and Retry recovers")
    func errorRetry() async throws {
        let h = try await ViewHarness()
        h.base.api.setSignResult(.failure(.unknown))
        await h.model.pick(data: jpeg, filename: "milk.jpg", mime: "image/jpeg")
        #expect(try h.text("attachments.error") == AttachmentPicker.failedMessage)
        #expect(h.has("attachments.retry"))

        h.base.api.setSignResult(.success(SignedUpload(attachmentId: UUID(), uploadURL: URL(string: "https://upload.test/o")!, storagePath: "p/a.jpg")))
        await h.model.retry()
        #expect(h.has("attachments.idle") && !h.has("attachments.retry"))
    }

    @Test("CORE-11: module off renders nothing")
    func moduleOff() async throws {
        let h = try await ViewHarness()
        #expect(!h.has("attachments.photo", enabled: false))
        #expect(!h.has("attachments.pdf", enabled: false))
        #expect(!h.has("attachments.idle", enabled: false))
    }
}
