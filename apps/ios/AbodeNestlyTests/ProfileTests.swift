import Contract
import Foundation
import ImageIO
import Testing
@testable import Core

func makeProfile(_ id: UUID = UUID(), name: String = "Ada", avatarPath: String? = nil) -> Profile {
    Profile(
        id: id, displayName: name, avatarPath: avatarPath,
        createdAt: Date(timeIntervalSince1970: 1_700_000_000), updatedAt: Date(timeIntervalSince1970: 1_700_000_000)
    )
}

/// A signed-in `SessionModel` over a fake auth, with every store on a throwaway suite or directory.
@MainActor
struct ProfileHarness {
    let user = UUID()
    let auth: FakeAuthService
    let session: SessionModel
    let outbox: ProfileOutbox
    let cache: ProfileCache
    let pending: PendingAvatarStore
    let online: OnlineBox
    let model: ProfileModel
    let started: Task<Void, Never>

    init(online isOnline: Bool = true, name: String = "Ada", avatarPath: String? = nil, uuids: [UUID] = []) async {
        let suite = "abodenestly.tests.profile.\(UUID().uuidString)"
        let auth = FakeAuthService(session: AuthSession(userId: user, email: "ada@test.local"))
        let box = OnlineBox(isOnline)
        let outbox = ProfileOutbox(suiteName: suite), cache = ProfileCache(suiteName: suite)
        let session = SessionModel(
            auth: auth, lastHousehold: LastHouseholdStore(suiteName: suite), profileCache: cache,
            profileOutbox: outbox, isOnline: { box.value }
        )
        auth.setProfile(.success(makeProfile(user, name: name, avatarPath: avatarPath)))
        let started = Task { await session.start() }
        while session.status == .loading { await Task.yield() }
        await session.waitUntilLoaded()
        let pending = PendingAvatarStore(
            directory: FileManager.default.temporaryDirectory.appendingPathComponent("pending-\(UUID().uuidString)")
        )
        let queue = UUIDQueue(uuids)
        self.auth = auth
        self.session = session
        self.outbox = outbox
        self.cache = cache
        self.pending = pending
        self.online = box
        self.started = started
        model = ProfileModel(
            userId: user, email: session.email, session: session, auth: auth, cache: cache, outbox: outbox,
            pendingAvatar: pending, isOnline: { box.value }, uuid: { queue.next() }
        )
    }

    var updateCalls: [FakeAuthService.Call] {
        auth.calls.filter { if case .updateProfile = $0 { true } else { false } }
    }
    var uploadCalls: [FakeAuthService.Call] {
        auth.calls.filter { if case .uploadAvatar = $0 { true } else { false } }
    }
}

/// Hands out fixed UUIDs first so tests know the storage path, then random ones.
@MainActor
final class UUIDQueue {
    private var queued: [UUID]
    init(_ queued: [UUID]) { self.queued = queued }
    func next() -> UUID { queued.isEmpty ? UUID() : queued.removeFirst() }
}

/// A real, tiny PNG so `downscaledJPEG` has something to decode.
func makeImageData(side: Int = 1200, red: CGFloat = 0.2) -> Data {
    let context = CGContext(
        data: nil, width: side, height: side / 2, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    )!
    context.setFillColor(red: red, green: 0.5, blue: 0.4, alpha: 1)
    context.fill(CGRect(x: 0, y: 0, width: side, height: side / 2))
    let output = NSMutableData()
    let destination = CGImageDestinationCreateWithData(output, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    CGImageDestinationFinalize(destination)
    return output as Data
}

@MainActor
@Suite("CORE-06 profile logic")
struct ProfileTests {
    private let now = Date(timeIntervalSince1970: 1_700_000_100)

    // MARK: Pure helpers

    @Test("CORE-06: editDisplayName trims, and returns nil for blank or unchanged names")
    func editName() {
        let profile = makeProfile(name: "Ada")
        #expect(ProfileEditing.editDisplayName(profile, draft: "   ", now: now, uuid: UUID()) == nil)
        #expect(ProfileEditing.editDisplayName(profile, draft: "Ada", now: now, uuid: UUID()) == nil)
        #expect(ProfileEditing.editDisplayName(profile, draft: "  Ada  ", now: now, uuid: UUID()) == nil)
        let edit = ProfileEditing.editDisplayName(profile, draft: "  Ada L  ", now: now, uuid: UUID())
        #expect(edit?.profile.displayName == "Ada L")
        #expect(edit?.profile.updatedAt == profile.updatedAt)
        #expect(edit?.entry.data == ProfileEdit(displayName: "Ada L"))
    }

    @Test("CORE-06: withPending overlays queued edits for the same row only")
    func overlay() {
        let profile = makeProfile(name: "Ada")
        let mine = ProfileEditing.buildEdit(profile, ProfileEdit(displayName: "Ada L"), now: now, uuid: UUID()).entry
        let clearAvatar = ProfileEditing.buildEdit(
            makeProfile(avatarPath: "x"), ProfileEdit(avatarPath: .some(nil)), now: now, uuid: UUID()
        ).entry
        let other = ProfileEditing.buildEdit(makeProfile(), ProfileEdit(displayName: "Other"), now: now, uuid: UUID()).entry
        let result = ProfileEditing.withPending(makeProfile(profile.id, name: "Ada", avatarPath: "old"), [mine, other])
        #expect(result.displayName == "Ada L")
        #expect(result.avatarPath == "old")
        var cleared = clearAvatar
        cleared.rowId = profile.id.uuidString.lowercased()
        #expect(ProfileEditing.withPending(makeProfile(profile.id, avatarPath: "old"), [cleared]).avatarPath == nil)
    }

    @Test("CORE-06: avatar paths have exactly two folders and provider URLs are not storage paths")
    func paths() {
        let user = UUID(), file = UUID()
        #expect(
            ProfileEditing.avatarStoragePath(userId: user, uuid: file)
                == "profiles/\(user.uuidString.lowercased())/\(file.uuidString.lowercased()).jpg"
        )
        #expect(ProfileEditing.isStoragePath("profiles/u/f.jpg"))
        #expect(!ProfileEditing.isStoragePath("https://lh3.googleusercontent.com/a"))
        #expect(!ProfileEditing.isStoragePath("HTTP://x"))
    }

    @Test("CORE-06: an outbox entry encodes in the SPEC §6.2 shape and only the keys it sets")
    func entryShape() throws {
        let entry = ProfileOutboxEntry(
            id: "e1", rowId: "r1", data: ProfileEdit(displayName: "Ada", avatarPath: .some(nil)), clientTs: "t"
        )
        let json = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(entry)) as? [String: Any]
        )
        #expect(json["table"] as? String == "profile")
        #expect(json["op"] as? String == "upsert")
        #expect(json["row_id"] as? String == "r1")
        let data = try #require(json["data"] as? [String: Any])
        #expect(data["display_name"] as? String == "Ada")
        #expect(data.keys.contains("avatar_path") && data["avatar_path"] is NSNull)
        let nameOnly = try #require(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(ProfileEdit(displayName: "A"))) as? [String: Any]
        )
        #expect(!nameOnly.keys.contains("avatar_path"))
        let back = try JSONDecoder().decode(ProfileOutboxEntry.self, from: JSONEncoder().encode(entry))
        #expect(back == entry)
    }

    @Test("CORE-06: undecodable stored outbox data reads as empty")
    func undecodable() {
        let suite = "abodenestly.tests.outbox.\(UUID().uuidString)"
        let user = UUID()
        UserDefaults(suiteName: suite)!.set(Data("nope".utf8), forKey: "abodenestly.outbox.profile.\(user.uuidString.lowercased())")
        #expect(ProfileOutbox(suiteName: suite).read(userId: user).isEmpty)
    }

    @Test("CORE-06: a zero-row update response is a failure, a returned row is success")
    func zeroRows() {
        func failure(_ data: Data) -> AuthError? {
            if case .failure(let error) = SupabaseAuthService.updateOutcome(data) { error } else { nil }
        }
        #expect(failure(Data("[]".utf8)) == .unknown)
        #expect(failure(Data()) == .unknown)
        #expect(failure(Data(#"[{"id":"x"}]"#.utf8)) == nil)
    }

    @Test("CORE-06: downscaledJPEG caps the longest side at 512 and rejects non-images")
    func downscale() throws {
        let jpeg = try #require(ProfileModel.downscaledJPEG(makeImageData(side: 1200)))
        let source = try #require(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let size = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        #expect((size[kCGImagePropertyPixelWidth] as? Int ?? 0) <= 512)
        #expect(ProfileModel.downscaledJPEG(Data("not an image".utf8)) == nil)
    }

    // MARK: Drain

    @Test("CORE-06: drain applies entries in order and empties the queue")
    func drainOrder() async {
        let h = await ProfileHarness()
        h.model.commitName("First")
        h.model.commitName("Second")
        await h.model.settled()
        let names = h.updateCalls.compactMap { call -> String? in
            if case .updateProfile(_, let edit) = call { edit.displayName } else { nil }
        }
        #expect(names == ["First", "Second"])
        #expect(h.outbox.read(userId: h.user).isEmpty)
    }

    @Test("CORE-06: a network failure stops the drain and keeps every entry")
    func drainNetwork() async {
        let h = await ProfileHarness()
        h.auth.setUpdateProfileResult(.failure(.network))
        h.model.commitName("First")
        await h.model.settled()
        #expect(h.updateCalls.count == 1)
        #expect(h.outbox.read(userId: h.user).count == 1)
    }

    @Test("CORE-06: a rejected entry is dropped and reported, and the next entry still goes through")
    func drainRejected() async {
        let h = await ProfileHarness(online: false)
        h.model.commitName("Bad")
        h.model.commitName("Good")
        h.auth.queueUpdateProfileResults([.failure(.unknown), .success(())])
        let result = await ProfileEditing.drain(userId: h.user, outbox: h.outbox, auth: h.auth)
        #expect(result.status == .done)
        #expect(result.rejected.map(\.data.displayName) == ["Bad"])
        #expect(h.updateCalls.count == 2)
        #expect(h.outbox.read(userId: h.user).isEmpty)
    }

    @Test("CORE-06: a server error keeps the entry queued instead of dropping it")
    func drainServerError() async {
        let h = await ProfileHarness()
        h.auth.setUpdateProfileResult(.failure(.server))
        h.model.commitName("Ada L")
        await h.model.settled()
        #expect(h.outbox.read(userId: h.user).count == 1)
        #expect(h.model.syncProblem == nil)
    }

    @Test("CORE-06: a cancelled drain (sign-out) sends nothing and keeps every entry")
    func drainCancelled() async {
        let h = await ProfileHarness(online: false)
        h.model.commitName("Ada L")
        let task = Task { await ProfileEditing.drain(userId: h.user, outbox: h.outbox, auth: h.auth) }
        task.cancel()
        let result = await task.value
        #expect(result.status == .cancelled)
        #expect(h.updateCalls.isEmpty)
        #expect(h.outbox.read(userId: h.user).count == 1)
    }

    @Test("CORE-06: a rejected name rolls the profile, cache and field back to the server row")
    func rejectionRestores() async {
        let h = await ProfileHarness()
        h.auth.setUpdateProfileResult(.failure(.unknown))
        h.model.commitName("Refused")
        await h.model.settled()
        #expect(h.session.profile?.displayName == "Ada")
        #expect(h.cache.read(userId: h.user)?.displayName == "Ada")
        #expect(h.model.nameDraft == "Ada")
        #expect(h.outbox.read(userId: h.user).isEmpty)
        #expect(h.model.syncProblem == ProfileModel.rejectedMessage)
    }

    @Test("CORE-06: a blank or unchanged draft falls back to the saved name")
    func draftFallsBack() async {
        let h = await ProfileHarness(online: false)
        h.model.nameDraft = "   "
        h.model.commitName(h.model.nameDraft)
        #expect(h.model.nameDraft == "Ada")
        h.model.nameDraft = "Ada"
        h.model.commitName(h.model.nameDraft)
        #expect(h.model.nameDraft == "Ada")
        h.model.nameDraft = "  Bea  "
        h.model.commitName(h.model.nameDraft)
        #expect(h.model.nameDraft == "Bea")
    }

    // MARK: Story acceptance

    @Test("CORE-06: AC2 an offline name edit updates the local profile and queues exactly one upsert, with no network call")
    func offlineNameEdit() async {
        let h = await ProfileHarness(online: false)
        h.model.commitName("Ada Lovelace")
        await h.model.settled()
        #expect(h.session.profile?.displayName == "Ada Lovelace")
        #expect(h.cache.read(userId: h.user)?.displayName == "Ada Lovelace")
        let entries = h.outbox.read(userId: h.user)
        #expect(entries.count == 1)
        #expect(entries.first?.table == "profile" && entries.first?.op == "upsert")
        #expect(entries.first?.rowId == h.user.uuidString.lowercased())
        #expect(entries.first?.data.displayName == "Ada Lovelace")
        #expect(h.updateCalls.isEmpty)
    }

    @Test("CORE-06: blank and unchanged names add nothing")
    func noopNames() async {
        let h = await ProfileHarness(online: false)
        h.model.commitName("  ")
        h.model.commitName("Ada")
        #expect(h.outbox.read(userId: h.user).isEmpty)
    }

    @Test("CORE-06: queued edits drain once the connection returns")
    func reconnectDrains() async {
        let h = await ProfileHarness(online: false)
        h.model.commitName("Ada L")
        h.online.value = true
        h.model.connectivityChanged(true)
        await h.model.settled()
        #expect(h.updateCalls.count == 1)
        #expect(h.outbox.read(userId: h.user).isEmpty)
    }

    @Test("CORE-06: a refetch keeps an unsynced name edit")
    func refetchKeepsPending() async {
        let h = await ProfileHarness(online: false)
        h.model.commitName("Ada L")
        h.session.retry()
        await h.session.waitUntilLoaded()
        #expect(h.session.profile?.displayName == "Ada L")
    }

    @Test("CORE-06: an offline photo is queued on disk, then uploaded and its avatar_path update drained")
    func photoFlow() async {
        let file = UUID()
        let h = await ProfileHarness(online: false, uuids: [file])
        h.model.pickAvatar(makeImageData())
        await h.model.settled()
        #expect(h.model.avatar == .queued)
        #expect(h.pending.read(userId: h.user) != nil)
        #expect(h.uploadCalls.isEmpty)

        h.online.value = true
        h.model.connectivityChanged(true)
        await h.model.settled()
        let path = ProfileEditing.avatarStoragePath(userId: h.user, uuid: file)
        #expect(h.uploadCalls.count == 1)
        guard case .uploadAvatar(let uploaded, _) = h.uploadCalls[0] else {
            Issue.record("no upload")
            return
        }
        #expect(uploaded == path)
        #expect(h.session.profile?.avatarPath == path)
        #expect(h.pending.read(userId: h.user) == nil)
        #expect(h.outbox.read(userId: h.user).isEmpty)
        #expect(h.model.avatar == .idle)
        #expect(h.updateCalls.count == 1)
    }

    @Test("CORE-06: a name committed during an upload survives the avatar_path update")
    func nameDuringUpload() async {
        let file = UUID()
        let h = await ProfileHarness(uuids: [file])
        h.auth.holdUpload()
        h.model.pickAvatar(makeImageData())
        while h.uploadCalls.isEmpty { await Task.yield() }
        h.model.commitName("Ada L")
        h.auth.releaseUpload()
        await h.model.settled()
        #expect(h.session.profile?.displayName == "Ada L")
        #expect(h.cache.read(userId: h.user)?.displayName == "Ada L")
        #expect(h.session.profile?.avatarPath == ProfileEditing.avatarStoragePath(userId: h.user, uuid: file))
        #expect(h.outbox.read(userId: h.user).isEmpty)
    }

    @Test("CORE-06: a photo picked during an upload is kept and uploaded next")
    func secondPickDuringUpload() async {
        let h = await ProfileHarness()
        h.auth.holdUpload()
        h.model.pickAvatar(makeImageData(side: 1200))
        while h.uploadCalls.isEmpty { await Task.yield() }
        h.model.pickAvatar(makeImageData(side: 1200, red: 0.9))
        h.auth.releaseUpload()
        await h.model.settled()
        #expect(h.uploadCalls.count == 2)
        #expect(h.pending.read(userId: h.user) == nil)
        #expect(h.model.avatar == .idle)
    }

    @Test("CORE-06: going offline during the avatar_path update leaves the photo queued, not stuck uploading")
    func pathDrainOffline() async {
        let h = await ProfileHarness()
        h.auth.setUpdateProfileResult(.failure(.network))
        h.model.pickAvatar(makeImageData())
        await h.model.settled()
        #expect(h.model.avatar == .queued)
        #expect(h.model.pickedPreview != nil)
        #expect(h.outbox.read(userId: h.user).count == 1)
        #expect(h.pending.read(userId: h.user) == nil)
    }

    @Test("CORE-06: a network failure during upload keeps the photo queued")
    func uploadNetworkFailure() async {
        let h = await ProfileHarness()
        h.auth.setUploadResult(.failure(.network))
        h.model.pickAvatar(makeImageData())
        await h.model.settled()
        #expect(h.model.avatar == .queued)
        #expect(h.pending.read(userId: h.user) != nil)
        #expect(h.session.profile?.avatarPath == nil)
    }

    @Test("CORE-06: a refused photo is dropped and reported")
    func uploadRejected() async {
        let h = await ProfileHarness()
        h.auth.setUploadResult(.failure(.unknown))
        h.model.pickAvatar(makeImageData())
        await h.model.settled()
        #expect(h.model.avatar == .idle)
        #expect(h.pending.read(userId: h.user) == nil)
        #expect(h.model.syncProblem == ProfileModel.rejectedMessage)
    }

    @Test("CORE-06: a non-image pick is reported and nothing is queued")
    func badPhoto() async {
        let h = await ProfileHarness()
        h.model.pickAvatar(Data("nope".utf8))
        #expect(h.model.syncProblem == ProfileModel.photoMessage)
        #expect(h.pending.read(userId: h.user) == nil)
        #expect(h.uploadCalls.isEmpty)
    }

    @Test("CORE-06: a drain that finishes after sign-out writes nothing")
    func cancelAfterSignOut() async {
        let h = await ProfileHarness()
        h.auth.holdUpload()
        h.model.pickAvatar(makeImageData())
        while h.uploadCalls.isEmpty { await Task.yield() }
        await h.model.signOut()
        h.auth.releaseUpload()
        await h.model.settled()
        #expect(h.session.profile?.avatarPath == nil)
        #expect(h.updateCalls.isEmpty)
        #expect(h.outbox.read(userId: h.user).isEmpty)
    }

    @Test("CORE-06: provider avatar URLs load directly; storage paths are signed")
    func avatarURLs() async {
        let provider = await ProfileHarness(avatarPath: "https://img.test/a.png")
        await provider.model.loadAvatarURL()
        #expect(provider.model.avatarImageURL == URL(string: "https://img.test/a.png"))

        let stored = await ProfileHarness(avatarPath: "profiles/u/f.jpg")
        stored.auth.setAvatarURL(URL(string: "https://signed.test/x?token=1"))
        await stored.model.loadAvatarURL()
        #expect(stored.model.avatarImageURL == URL(string: "https://signed.test/x?token=1"))

        let offline = await ProfileHarness(online: false, avatarPath: "profiles/u/f.jpg")
        offline.auth.setAvatarURL(URL(string: "https://signed.test/x"))
        await offline.model.loadAvatarURL()
        #expect(offline.model.avatarImageURL == nil)
    }
}
