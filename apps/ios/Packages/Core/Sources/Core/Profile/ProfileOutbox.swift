import Contract
import Foundation
import Shared

// iOS has no GRDB/outbox/sync yet (SHOP-08), so profile edits queue in UserDefaults (like `ProfileCache`)
// and the picked photo waits on disk. SHOP-08 replaces all of this with the real outbox. Port of web `Profile.ts`.

/// The columns a person can change on their own profile row. `avatarPath: .some(nil)` clears the avatar.
public struct ProfileEdit: Sendable, Codable, Equatable {
    public var displayName: String?
    public var avatarPath: String??

    public init(displayName: String? = nil, avatarPath: String?? = nil) {
        self.displayName = displayName
        self.avatarPath = avatarPath
    }

    enum CodingKeys: String, CodingKey {
        case displayName = "display_name"
        case avatarPath = "avatar_path"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        displayName = try container.decodeIfPresent(String.self, forKey: .displayName)
        if container.contains(.avatarPath) {
            avatarPath = .some(try container.decodeIfPresent(String.self, forKey: .avatarPath))
        } else {
            avatarPath = nil
        }
    }

    /// Only the keys that are set go on the wire, so an edit never overwrites a column it did not touch.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(displayName, forKey: .displayName)
        if let avatarPath { try container.encode(avatarPath, forKey: .avatarPath) }
    }
}

/// Internal queue shape modelled on SPEC §6.2. The wire type lands with SHOP-08; there is no contract change here.
public struct ProfileOutboxEntry: Sendable, Codable, Equatable {
    public var id: String
    public var table: String
    public var op: String
    public var rowId: String
    public var data: ProfileEdit
    public var clientTs: String

    public init(id: String, rowId: String, data: ProfileEdit, clientTs: String) {
        self.id = id
        self.table = "profile"
        self.op = "upsert"
        self.rowId = rowId
        self.data = data
        self.clientTs = clientTs
    }

    enum CodingKeys: String, CodingKey {
        case id, table, op, data
        case rowId = "row_id"
        case clientTs = "client_ts"
    }
}

public struct DrainResult: Sendable, Equatable {
    public enum Status: Sendable, Equatable { case done, network }
    public var status: Status
    public var rejected: [ProfileOutboxEntry]
}

/// The queued profile edits per user, persisted in `UserDefaults` under `abodenestly.outbox.profile.<uid>`.
public struct ProfileOutbox: Sendable {
    /// `UserDefaults` is not `Sendable`, so only the suite name is stored. `nil` is the standard defaults.
    private let suiteName: String?

    public init(suiteName: String? = nil) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    private static func key(_ userId: UUID) -> String { "abodenestly.outbox.profile.\(userId.uuidString.lowercased())" }

    /// Anything undecodable yields `[]`, so a stale shape never crashes the screen.
    public func read(userId: UUID) -> [ProfileOutboxEntry] {
        guard let data = defaults.data(forKey: Self.key(userId)) else { return [] }
        return (try? JSONDecoder().decode([ProfileOutboxEntry].self, from: data)) ?? []
    }

    public func write(_ entries: [ProfileOutboxEntry], userId: UUID) {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        defaults.set(data, forKey: Self.key(userId))
    }

    public func append(_ entry: ProfileOutboxEntry, userId: UUID) {
        write(read(userId: userId) + [entry], userId: userId)
    }

    public func remove(id: String, userId: UUID) {
        write(read(userId: userId).filter { $0.id != id }, userId: userId)
    }
}

public enum ProfileEditing {
    /// `nil` when the trimmed name is empty (`display_name` is not null) or unchanged.
    public static func editDisplayName(
        _ profile: Profile, draft: String, now: Date, uuid: UUID
    ) -> (profile: Profile, entry: ProfileOutboxEntry)? {
        let name = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name != profile.displayName else { return nil }
        return buildEdit(profile, ProfileEdit(displayName: name), now: now, uuid: uuid)
    }

    public static func buildEdit(
        _ profile: Profile, _ edit: ProfileEdit, now: Date, uuid: UUID
    ) -> (profile: Profile, entry: ProfileOutboxEntry) {
        // `updatedAt` stays as fetched: it is server-owned (profile_touch).
        var updated = profile
        apply(edit, to: &updated)
        let entry = ProfileOutboxEntry(
            id: uuid.uuidString.lowercased(), rowId: profile.id.uuidString.lowercased(), data: edit,
            clientTs: PostgrestJSON.format(now)
        )
        return (updated, entry)
    }

    /// Overlays queued edits onto a fetched server row so a refetch does not revert an unsynced edit.
    public static func withPending(_ profile: Profile, _ entries: [ProfileOutboxEntry]) -> Profile {
        var result = profile
        for entry in entries where entry.rowId == profile.id.uuidString.lowercased() {
            apply(entry.data, to: &result)
        }
        return result
    }

    private static func apply(_ edit: ProfileEdit, to profile: inout Profile) {
        if let name = edit.displayName { profile.displayName = name }
        if let path = edit.avatarPath { profile.avatarPath = path }
    }

    /// `profiles/<uid>/<uuid>.jpg`: exactly two folders, as the Storage policy requires.
    public static func avatarStoragePath(userId: UUID, uuid: UUID) -> String {
        "profiles/\(userId.uuidString.lowercased())/\(uuid.uuidString.lowercased()).jpg"
    }

    /// SPEC §3: `avatar_path` is either a Storage object path or a provider URL.
    public static func isStoragePath(_ avatarPath: String) -> Bool {
        let lower = avatarPath.lowercased()
        return !(lower.hasPrefix("http://") || lower.hasPrefix("https://"))
    }

    /// Interim drain (until SHOP-08): applies queued edits in order with a direct update.
    public static func drain(userId: UUID, outbox: ProfileOutbox, auth: any AuthService) async -> DrainResult {
        var rejected: [ProfileOutboxEntry] = []
        while true {
            // Re-read each round: an edit queued while a request was in flight must not be lost or skipped.
            guard let entry = outbox.read(userId: userId).first else { return DrainResult(status: .done, rejected: rejected) }
            let result = await auth.updateProfile(userId: userId, edit: entry.data)
            if case .failure(.network) = result { return DrainResult(status: .network, rejected: rejected) }
            // A rejected entry (RLS, validation) would block the queue forever, so it is dropped and reported.
            if case .failure = result { rejected.append(entry) }
            outbox.remove(id: entry.id, userId: userId)
        }
    }
}

/// The photo picked while offline (or still uploading), kept on disk so it survives a relaunch.
public struct PendingAvatarStore: Sendable {
    private let directory: URL

    public init(directory: URL? = nil) {
        self.directory = directory
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("pending-avatar", isDirectory: true)
    }

    private func file(_ userId: UUID) -> URL {
        directory.appendingPathComponent("\(userId.uuidString.lowercased()).jpg")
    }

    @discardableResult
    public func save(_ data: Data, userId: UUID) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: file(userId), options: .atomic)
            return true
        } catch {
            return false
        }
    }

    public func read(userId: UUID) -> Data? { try? Data(contentsOf: file(userId)) }

    public func clear(userId: UUID) { try? FileManager.default.removeItem(at: file(userId)) }
}
