import Contract
import Foundation
import ImageIO
import Observation
import UniformTypeIdentifiers

/// DESIGN B7 avatar states: `idle`, `uploading-avatar`, and `offline` (photo queued).
public enum AvatarStatus: Equatable, Sendable {
    case idle
    case uploading
    case queued
}

/// Profile screen logic (CORE-06, port of the web `Profile` screen state). Edits are local first and drain by
/// direct update when online; SHOP-08's outbox and `SyncEngine.kick()` replace `kickDrain()` later.
@Observable
@MainActor
public final class ProfileModel {
    public static let maxAvatarSide: CGFloat = 512
    public static let rejectedMessage = "Some changes couldn't be saved. Try again."
    public static let photoMessage = "That photo couldn't be used. Pick another."

    public private(set) var avatar: AvatarStatus = .idle
    /// A provider URL or a signed Storage URL for the saved avatar; `nil` shows initials.
    public private(set) var avatarImageURL: URL?
    /// The photo just picked, shown until the saved one loads.
    public private(set) var pickedPreview: Data?
    public private(set) var syncProblem: String?
    /// The text in the name field. Lives here (not in the view) so it survives a re-render and tests can set it.
    public var nameDraft: String

    public let userId: UUID
    public let email: String?
    public var profile: Profile? { session.profile }

    @ObservationIgnored private let session: SessionModel
    @ObservationIgnored private let auth: any AuthService
    @ObservationIgnored private let cache: ProfileCache
    @ObservationIgnored private let outbox: ProfileOutbox
    @ObservationIgnored private let pendingAvatar: PendingAvatarStore
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private let uuid: @MainActor () -> UUID
    @ObservationIgnored private var drainTask: Task<Void, Never>?
    /// Set when a kick arrives while a drain runs, so the running drain goes around once more.
    @ObservationIgnored private var rerun = false

    public init(
        userId: UUID, email: String?, session: SessionModel, auth: any AuthService,
        cache: ProfileCache = ProfileCache(), outbox: ProfileOutbox = ProfileOutbox(),
        pendingAvatar: PendingAvatarStore = PendingAvatarStore(),
        isOnline: @escaping @MainActor () -> Bool = { true },
        now: @escaping @MainActor () -> Date = { Date() },
        uuid: @escaping @MainActor () -> UUID = { UUID() }
    ) {
        self.userId = userId
        self.email = email
        self.session = session
        self.auth = auth
        self.cache = cache
        self.outbox = outbox
        self.pendingAvatar = pendingAvatar
        self.isOnline = isOnline
        self.now = now
        self.uuid = uuid
        self.nameDraft = session.profile?.displayName ?? ""
        // A photo left from an earlier launch is still waiting to upload.
        if let waiting = pendingAvatar.read(userId: userId) {
            pickedPreview = waiting
            avatar = .queued
        }
    }

    // MARK: Triggers

    /// Run on appear: drains anything queued earlier and resolves the avatar image.
    public func start() async {
        kickDrain()
        await loadAvatarURL()
    }

    /// Interim trigger until SHOP-08's `SyncEngine.kick()`: the view forwards `isOnline` changes here.
    public func connectivityChanged(_ online: Bool) {
        if online {
            kickDrain()
        } else if pendingAvatar.read(userId: userId) != nil {
            avatar = .queued
        }
    }

    /// Resolves once the drain in flight (and any rerun it picked up) has finished.
    public func settled() async {
        await drainTask?.value
    }

    public func signOut() async {
        // Cancel before the session ends so a drain never writes for a user who is gone.
        drainTask?.cancel()
        await session.signOut()
    }

    // MARK: Edits (local first, no network)

    public func commitName(_ draft: String) {
        guard let profile = session.profile,
            let edit = ProfileEditing.editDisplayName(profile, draft: draft, now: now(), uuid: uuid())
        else { return }
        syncProblem = nil
        apply(edit.profile, entry: edit.entry)
        kickDrain()
    }

    /// Downscales to at most 512 px, keeps the JPEG on disk and uploads when online.
    public func pickAvatar(_ data: Data) {
        guard let jpeg = Self.downscaledJPEG(data), pendingAvatar.save(jpeg, userId: userId) else {
            syncProblem = Self.photoMessage
            return
        }
        syncProblem = nil
        pickedPreview = jpeg
        avatar = isOnline() ? .uploading : .queued
        kickDrain()
    }

    private func apply(_ updated: Profile, entry: ProfileOutboxEntry) {
        session.setLocalProfile(updated)
        cache.write(updated, userId: userId)
        outbox.append(entry, userId: userId)
    }

    // MARK: Drain

    /// Coalesced: name edits, then the pending photo, then the `avatar_path` edit it produced.
    public func kickDrain() {
        if drainTask != nil {
            rerun = true
            return
        }
        drainTask = Task { [weak self] in
            guard let self else { return }
            repeat {
                rerun = false
                await drainOnce()
            } while rerun && active
            drainTask = nil
        }
    }

    private var active: Bool { !Task.isCancelled && session.userId == userId }

    private func drainOnce() async {
        guard isOnline() else {
            if pendingAvatar.read(userId: userId) != nil { avatar = .queued }
            return
        }
        guard await drainEdits(), active else { return }
        await uploadPendingPhoto()
    }

    /// `false` when the drain should stop (offline again, or signed out).
    private func drainEdits() async -> Bool {
        let result = await ProfileEditing.drain(userId: userId, outbox: outbox, auth: auth)
        guard active else { return false }
        if !result.rejected.isEmpty { syncProblem = Self.rejectedMessage }
        if result.status == .network {
            if pendingAvatar.read(userId: userId) != nil { avatar = .queued }
            return false
        }
        return true
    }

    private func uploadPendingPhoto() async {
        guard let photo = pendingAvatar.read(userId: userId), let profile = session.profile else {
            avatar = .idle
            return
        }
        avatar = .uploading
        let path = ProfileEditing.avatarStoragePath(userId: userId, uuid: uuid())
        let uploaded = await auth.uploadAvatar(path: path, data: photo)
        guard active else { return }
        switch uploaded {
        case .failure(.network):
            avatar = .queued
        case .failure:
            // A photo the server refuses would block every later drain, so it is dropped and reported.
            pendingAvatar.clear(userId: userId)
            pickedPreview = nil
            avatar = .idle
            syncProblem = Self.rejectedMessage
        case .success:
            let edit = ProfileEditing.buildEdit(
                profile, ProfileEdit(avatarPath: .some(path)), now: now(), uuid: uuid()
            )
            apply(edit.profile, entry: edit.entry)
            pendingAvatar.clear(userId: userId)
            // Queue-then-drain: the `avatar_path` update goes through the same path as a name edit.
            if await drainEdits(), active {
                avatar = .idle
                await loadAvatarURL()
                pickedPreview = nil
            }
        }
    }

    // MARK: Avatar image

    /// Provider URLs load directly; Storage paths need a signed URL, which needs a connection.
    public func loadAvatarURL() async {
        guard let path = session.profile?.avatarPath, !path.isEmpty else {
            avatarImageURL = nil
            return
        }
        if !ProfileEditing.isStoragePath(path) {
            avatarImageURL = URL(string: path)
        } else if isOnline(), let url = await auth.avatarURL(path: path) {
            avatarImageURL = url
        }
    }

    // MARK: Image helpers

    /// JPEG of the picked image, orientation applied, longest side at most `maxAvatarSide`. `nil` if it is not an image.
    public static func downscaledJPEG(_ data: Data, maxSide: CGFloat = maxAvatarSide) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }
}
