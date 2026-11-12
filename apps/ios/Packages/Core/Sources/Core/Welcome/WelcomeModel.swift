import Contract
import Foundation
import Observation

/// DESIGN B2 states. Typed text lives in `name` / `code`, outside the state, so it survives every error (AC3).
public enum WelcomeState: Equatable, Sendable {
    case idle
    case creating
    case joining
    case createError(ApiFailure)
    case errorInvalidCode
    case joinError(ApiFailure)
}

/// What the person did on the Welcome screen; the app turns it into a `SessionModel` call.
public enum WelcomeOutcome: Sendable, Equatable {
    case created(Household)
    case joined(householdId: UUID)
}

@Observable
@MainActor
public final class WelcomeModel {
    public var name = ""
    public var code = ""
    public private(set) var state: WelcomeState = .idle

    @ObservationIgnored private let api: any ApiService
    @ObservationIgnored private let onLanded: @MainActor (WelcomeOutcome) -> Void

    public init(api: any ApiService, onLanded: @escaping @MainActor (WelcomeOutcome) -> Void) {
        self.api = api
        self.onLanded = onLanded
    }

    public var isBusy: Bool { state == .creating || state == .joining }

    /// DESIGN B4: every household opens on its Home tab.
    public static func landing(for household: Household) -> Destination {
        .household(id: household.id, tab: .home)
    }

    public func create() async {
        guard !isBusy else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return state = .createError(.validation) }
        state = .creating
        switch await api.createHousehold(name: trimmed) {
        case .success(let household):
            state = .idle
            onLanded(.created(household))
        case .failure(let failure):
            state = .createError(failure)
        }
    }

    public func join() async {
        guard !isBusy else { return }
        // Codes are lowercase hex; people type or paste them in any case (web does the same).
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return state = .joinError(.validation) }
        state = .joining
        switch await api.acceptInvite(code: trimmed) {
        case .success(let householdId):
            state = .idle
            onLanded(.joined(householdId: householdId))
        case .failure(.invalidCode):
            state = .errorInvalidCode
        case .failure(let failure):
            state = .joinError(failure)
        }
    }
}
