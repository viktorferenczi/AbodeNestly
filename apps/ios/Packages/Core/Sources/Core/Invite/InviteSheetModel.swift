import Contract
import Foundation
import Observation
import UIKit

/// DESIGN B6 states. `.error` keeps the old invite when a "New code" attempt fails (port of web `InviteSheetState`).
public enum InviteSheetState: Equatable, Sendable {
    case loading
    case ready(Invite)
    case rotating(Invite)
    case error(ApiFailure, Invite?)
}

@Observable
@MainActor
public final class InviteSheetModel: Identifiable {
    public private(set) var state: InviteSheetState = .loading
    /// The new code is ready but retiring the old one failed.
    public private(set) var oldCodeMayWork = false
    /// Copy feedback ("Code copied").
    public private(set) var notice: String?

    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let api: any ApiService
    @ObservationIgnored private let copy: @MainActor (String) -> Void

    public init(
        householdId: UUID, api: any ApiService,
        copy: @escaping @MainActor (String) -> Void = { UIPasteboard.general.string = $0 }
    ) {
        self.householdId = householdId
        self.api = api
        self.copy = copy
    }

    public var shownInvite: Invite? {
        switch state {
        case .loading: nil
        case .ready(let invite), .rotating(let invite): invite
        case .error(_, let invite): invite
        }
    }

    public var isRotating: Bool {
        if case .rotating = state { true } else { false }
    }

    public var hasFailed: Bool {
        if case .error = state { true } else { false }
    }

    /// Fetches the live invite, minting one if there is none. No-op while an invite is on screen.
    public func loadIfNeeded() async {
        guard shownInvite == nil else { return }
        state = .loading
        switch await api.fetchLiveInvite(householdId: householdId) {
        case .failure(let failure):
            state = .error(failure, nil)
        case .success(let live?):
            state = .ready(live)
        case .success(nil):
            switch await api.createInvite(householdId: householdId) {
            case .success(let created): state = .ready(created)
            case .failure(let failure): state = .error(failure, nil)
            }
        }
    }

    /// Creates a code, then retires the old one; the API never retires older codes, the client does (SPEC §14).
    public func newCode() async {
        guard !isRotating, let old = shownInvite else { return }
        notice = nil
        oldCodeMayWork = false
        state = .rotating(old)
        switch await api.createInvite(householdId: householdId) {
        case .failure(let failure):
            state = .error(failure, old)
        case .success(let created):
            if case .failure = await api.retireInvite(id: old.id) { oldCodeMayWork = true }
            state = .ready(created)
        }
    }

    public func copyCode() {
        guard let invite = shownInvite, !isRotating else { return }
        copy(invite.code)
        notice = "Code copied"
    }

    // MARK: Pure helpers

    /// `<base>/join/<code>`; web routes it to the Welcome join card.
    public static func link(base: URL, code: String) -> URL {
        let root = base.absoluteString.hasSuffix("/") ? String(base.absoluteString.dropLast()) : base.absoluteString
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        let encoded = code.addingPercentEncoding(withAllowedCharacters: allowed) ?? code
        return URL(string: "\(root)/join/\(encoded)") ?? base
    }

    public static func usesLeft(_ invite: Invite) -> Int { invite.maxUses - invite.uses }

    /// "19 Nov" in the viewer's locale; `locale` and `timeZone` are injectable for tests.
    public static func expiryText(_ date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        date.formatted(Date.FormatStyle(locale: locale, timeZone: timeZone).day().month(.abbreviated))
    }

    public static func metaText(_ invite: Invite, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let left = usesLeft(invite)
        return "\(left == 1 ? "1 use left" : "\(left) uses left") · Expires "
            + expiryText(invite.expiresAt, locale: locale, timeZone: timeZone)
    }
}
