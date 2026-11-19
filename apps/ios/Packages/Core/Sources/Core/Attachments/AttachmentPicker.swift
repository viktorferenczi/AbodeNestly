import Contract
import Foundation
import Observation
import Sync

/// Entities that can carry an attachment (SPEC §4.1 CORE-11). The raw value is what the API and the local table store.
public enum AttachmentEntity: String, Sendable, CaseIterable {
    case pantryItem = "pantry_item"
    case asset
    case maintLog = "maint_log"
}

/// The states of the control (CORE-11): the list is shown in every one of them.
public enum AttachmentPickerState: Equatable, Sendable {
    case idle
    case uploading
    case queued
    case error(String)
}

/// Logic of the shared attachment control that pantry items, assets and log entries host (port of the web
/// `AttachmentPicker`). Offline picks go to the local `attachment_outbox` queue, which this model drains when online.
@Observable
@MainActor
public final class AttachmentPicker {
    public nonisolated static let allowedMimes: Set<String> = ["image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf"]
    public nonisolated static let maxBytes = 25 * 1024 * 1024
    public static let rejectedMessage = "Choose a photo or a PDF up to 25 MB."
    public static let failedMessage = "Could not attach the file."
    public static let moduleOffMessage = "Attachments are turned off for this household."

    /// Mirrors the API's limits; the server stays the authority.
    public nonisolated static func isAllowed(mime: String, size: Int) -> Bool {
        allowedMimes.contains(mime.lowercased()) && size > 0 && size <= maxBytes
    }

    public private(set) var state: AttachmentPickerState = .idle
    public private(set) var attachments: [Attachment] = []
    public private(set) var queued: [AttachmentUpload] = []
    /// Signed read URLs of the photos, by attachment id.
    public private(set) var thumbnails: [UUID: URL] = [:]

    public let householdId: UUID
    public let entity: AttachmentEntity
    public let entityId: UUID

    @ObservationIgnored private let userId: UUID?
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let api: any ApiService
    @ObservationIgnored private let auth: any AuthService
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let uuid: @MainActor () -> UUID
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private var lastPick: Pending?
    @ObservationIgnored private var retryDrains = false
    @ObservationIgnored private var draining = false

    private struct Pending {
        var filename: String
        var mime: String
        var data: Data
    }

    private enum Outcome {
        case uploaded
        case network
        case failed(ApiFailure)
    }

    public init(
        householdId: UUID, entity: AttachmentEntity, entityId: UUID, userId: UUID?,
        database: HouseholdDatabase, api: any ApiService, auth: any AuthService,
        isOnline: @escaping @MainActor () -> Bool = { true },
        uuid: @escaping @MainActor () -> UUID = { UUID() },
        now: @escaping @MainActor () -> Date = { Date() }
    ) {
        self.householdId = householdId
        self.entity = entity
        self.entityId = entityId
        self.userId = userId
        self.database = database
        self.api = api
        self.auth = auth
        self.isOnline = isOnline
        self.uuid = uuid
        self.now = now
    }

    // MARK: Triggers

    /// On appear: show what is stored and upload what was queued earlier.
    public func start() async {
        await reload()
        await drain()
    }

    /// Follows the stored rows until the view goes away, so rows another writer adds appear without a reload.
    public func observe() async {
        for await rows in await database.observeAttachments(householdId: householdId, entity: entity.rawValue, entityId: entityId) {
            attachments = rows
            await loadThumbnails()
        }
    }

    // MARK: Pick flow

    /// Step 1 to 3 of the pick flow: offline queues without calling sign (AC4); online signs, PUTs and records.
    public func pick(data: Data, filename: String, mime: String) async {
        guard Self.isAllowed(mime: mime, size: data.count) else {
            lastPick = nil
            state = .error(Self.rejectedMessage)
            return
        }
        let pending = Pending(filename: filename, mime: mime, data: data)
        lastPick = pending
        retryDrains = false
        state = .uploading
        let outcome: Outcome = isOnline() ? await send(pending) : .network
        switch outcome {
        case .uploaded:
            break
        case .network:
            // Offline, or the network dropped mid-way: keep the pick for the drain.
            do {
                let upload = AttachmentUpload(
                    id: uuid(), householdId: householdId, entity: entity.rawValue, entityId: entityId,
                    filename: filename, mime: mime, size: data.count, createdAt: now()
                )
                try await database.enqueueAttachmentUpload(upload, bytes: data)
            } catch {
                state = .error(Self.failedMessage)
                return
            }
        case .failed(let failure):
            // Nothing is recorded locally (AC5); Retry re-runs the same pick.
            state = .error(Self.message(for: failure))
            return
        }
        lastPick = nil
        await settle()
    }

    /// Re-runs the pick that failed, or the drain that stopped.
    public func retry() async {
        if let pending = lastPick {
            await pick(data: pending.data, filename: pending.filename, mime: pending.mime)
        } else if retryDrains {
            await drain()
        }
    }

    /// Step 4: queued picks in creation order. A row leaves the queue only after it was uploaded and recorded.
    public func drain() async {
        guard !draining else { return }
        draining = true
        defer { draining = false }
        guard var pending = try? await database.pendingAttachmentUploads(householdId: householdId), !pending.isEmpty else {
            await settle()
            return
        }
        state = .uploading
        retryDrains = false
        while !pending.isEmpty {
            guard isOnline() else { break }
            let next = pending.removeFirst()
            guard let data = try? await database.attachmentBytes(of: next) else {
                // The file is gone, so this pick can never upload; dropping it keeps the rest of the queue moving.
                try? await database.removeAttachmentUpload(id: next.id)
                continue
            }
            switch await send(Pending(filename: next.filename, mime: next.mime, data: data), entity: next.entity, entityId: next.entityId) {
            case .uploaded:
                try? await database.removeAttachmentUpload(id: next.id)
            case .network:
                pending.removeAll()
            case .failed(let failure):
                retryDrains = true
                await reload()
                state = .error(Self.message(for: failure))
                return
            }
        }
        await settle()
    }

    // MARK: Internals

    private func send(_ pending: Pending) async -> Outcome {
        await send(pending, entity: entity.rawValue, entityId: entityId)
    }

    private func send(_ pending: Pending, entity: String, entityId: UUID) async -> Outcome {
        let signed = await api.signAttachment(
            householdId: householdId, entity: entity, entityId: entityId, filename: pending.filename,
            mime: pending.mime, size: pending.data.count
        )
        let target: SignedUpload
        switch signed {
        case .success(let value): target = value
        case .failure(.network): return .network
        case .failure(let failure): return .failed(failure)
        }
        switch await api.upload(to: target.uploadURL, data: pending.data, mime: pending.mime) {
        case .success: break
        case .failure(.network): return .network
        case .failure(let failure): return .failed(failure)
        }
        let stamp = now()
        let row = Attachment(
            id: target.attachmentId, householdId: householdId, entity: entity, entityId: entityId,
            storagePath: target.storagePath, mime: pending.mime, sizeBytes: pending.data.count, createdBy: userId,
            createdAt: stamp, updatedAt: stamp, deletedAt: nil, syncSeq: 0
        )
        do {
            try await database.upsertAttachment(row)
        } catch {
            return .failed(.unknown)
        }
        return .uploaded
    }

    private func settle() async {
        await reload()
        state = queued.isEmpty ? .idle : .queued
    }

    private func reload() async {
        attachments = (try? await database.attachments(householdId: householdId, entity: entity.rawValue, entityId: entityId)) ?? attachments
        queued = (try? await database.pendingAttachmentUploads(householdId: householdId, entity: entity.rawValue, entityId: entityId)) ?? []
        await loadThumbnails()
    }

    private func loadThumbnails() async {
        for row in attachments where row.mime.hasPrefix("image/") && thumbnails[row.id] == nil {
            if let url = await auth.attachmentURL(path: row.storagePath) { thumbnails[row.id] = url }
        }
    }

    private static func message(for failure: ApiFailure) -> String {
        failure == .moduleDisabled ? moduleOffMessage : failedMessage
    }
}
