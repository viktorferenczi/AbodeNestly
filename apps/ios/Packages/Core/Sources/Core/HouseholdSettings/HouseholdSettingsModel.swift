import Contract
import Foundation
import Observation

public enum ModuleKey: String, Sendable, CaseIterable, Identifiable {
    case shopping, pantry, maintenance

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .shopping: "Shopping"
        case .pantry: "Pantry"
        case .maintenance: "Maintenance"
        }
    }

    /// One line under each switch (same copy as web).
    public var summary: String {
        switch self {
        case .shopping: "Shared shopping lists that sync across the household."
        case .pantry: "What you have at home, with expiry dates and low-stock alerts."
        case .maintenance: "Recurring home tasks, warranties and service history."
        }
    }
}

public enum SettingsMode: Sendable, Equatable { case owner, member, offline }

public enum SettingsState: Sendable, Equatable {
    case idle
    case saving
    case error(ApiFailure)
    case leaving
}

public enum SettingsLoadState: Sendable, Equatable {
    case loading
    case loaded
    /// The household could not be read and nothing is cached, or the viewer is no longer a member.
    case unavailable
}

/// Household settings logic (DESIGN B5): port of web `HouseholdSettings.ts` plus the state in `HouseholdSettings.tsx`.
@Observable
@MainActor
public final class HouseholdSettingsModel {
    public static let lastOwnerMessage = "You're the last owner. Make someone else an owner before leaving."

    public private(set) var household: Household?
    public private(set) var role: Member.Role?
    public private(set) var loadState: SettingsLoadState = .loading
    public private(set) var state: SettingsState = .idle
    /// The household as it will look once the pending save lands; shown while `saving`.
    public private(set) var optimistic: Household?
    /// What the person typed in the name field; `nil` shows the saved name.
    public var nameDraft: String?
    /// The leave confirm sheet is open.
    public private(set) var isConfirmingLeave = false

    public let householdId: UUID
    public let userId: UUID
    @ObservationIgnored private let api: any ApiService
    @ObservationIgnored private let cache: HouseholdCache
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let onHouseholdChanged: @MainActor (LandingHousehold) -> Void
    @ObservationIgnored private let onLeft: @MainActor () -> Void

    public init(
        householdId: UUID, userId: UUID, api: any ApiService, cache: HouseholdCache = HouseholdCache(),
        isOnline: @escaping @MainActor () -> Bool = { true },
        onHouseholdChanged: @escaping @MainActor (LandingHousehold) -> Void,
        onLeft: @escaping @MainActor () -> Void
    ) {
        self.householdId = householdId
        self.userId = userId
        self.api = api
        self.cache = cache
        self.isOnline = isOnline
        self.onHouseholdChanged = onHouseholdChanged
        self.onLeft = onLeft
    }

    // MARK: Derived

    public var shown: Household? { optimistic ?? household }

    public var name: String { nameDraft ?? shown?.name ?? "" }

    public func mode(isOnline online: Bool) -> SettingsMode { Self.settingsMode(role: role, online: online) }

    public var isSaving: Bool { state == .saving }

    // MARK: Loading

    /// Cache first so the screen is readable offline, then the live row when online.
    public func load() async {
        if household == nil, let cached = cache.read(userId: userId, householdId: householdId) {
            show(cached)
        }
        guard isOnline() else {
            if household == nil { loadState = .unavailable }
            return
        }
        switch await api.fetchHousehold(householdId: householdId, userId: userId) {
        case .success(let value?):
            show(value)
            cache.write(value, userId: userId)
        case .success(nil):
            // No longer an active member (left elsewhere, or the household was deleted).
            cache.clear(userId: userId, householdId: householdId)
            household = nil
            role = nil
            loadState = .unavailable
        case .failure:
            if household == nil { loadState = .unavailable }
        }
    }

    private func show(_ value: HouseholdWithRole) {
        household = value.household
        role = value.role
        loadState = .loaded
    }

    // MARK: Saving

    public func toggle(_ module: ModuleKey, on: Bool) async {
        guard canEdit, let current = household, !isBusy else { return }
        let display = Self.withModule(current, module, on: on)
        await save(display: display) { [api, householdId] in
            await api.updateModules(
                householdId: householdId,
                shopping: module == .shopping ? on : nil,
                pantry: module == .pantry ? on : nil,
                maintenance: module == .maintenance ? on : nil
            )
        }
    }

    /// Saves the draft when it is trimmed, non-empty and changed.
    public func commitName() async {
        guard canEdit, let current = household, !isBusy else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != current.name else { return }
        await save(display: nil) { [api, householdId] in
            await api.updateHousehold(householdId: householdId, name: trimmed, currency: nil)
        }
    }

    public func setCurrency(_ currency: String) async {
        guard canEdit, var display = household, display.currency != currency, !isBusy else { return }
        display.currency = currency
        await save(display: display) { [api, householdId] in
            await api.updateHousehold(householdId: householdId, name: nil, currency: currency)
        }
    }

    private var canEdit: Bool { isOnline() && role == .owner }

    private var isBusy: Bool { state == .saving || state == .leaving }

    private func save(display: Household?, run: () async -> Result<Household, ApiFailure>) async {
        state = .saving
        optimistic = display
        let result = await run()
        optimistic = nil
        switch result {
        case .success(let saved):
            nameDraft = nil
            household = saved
            cache.write(HouseholdWithRole(household: saved, role: role ?? .owner), userId: userId)
            state = .idle
            onHouseholdChanged(Self.landing(saved))
        case .failure(let failure):
            state = .error(failure)
            // The role may have changed under us; reload so the screen shows the truth.
            if failure == .forbidden { await load() }
        }
    }

    // MARK: Leaving

    public func beginLeave() {
        guard !isBusy else { return }
        state = .idle
        isConfirmingLeave = true
    }

    public func cancelLeave() {
        guard state != .leaving else { return }
        state = .idle
        isConfirmingLeave = false
    }

    public func leave() async {
        guard isOnline(), state != .leaving else { return }
        state = .leaving
        switch await api.removeMember(householdId: householdId, userId: userId) {
        case .success, .failure(.notFound):
            // Already gone counts as done.
            cache.clear(userId: userId, householdId: householdId)
            state = .idle
            isConfirmingLeave = false
            onLeft()
        case .failure(let failure):
            state = .error(failure)
        }
    }

    // MARK: Messages

    /// The inline error under the form; hidden while the leave sheet is open (it shows its own).
    public var saveErrorMessage: String? {
        guard case .error(let failure) = state, !isConfirmingLeave else { return nil }
        return Self.saveError(failure)
    }

    public var leaveErrorMessage: String? {
        guard case .error(let failure) = state, isConfirmingLeave else { return nil }
        return failure == .lastOwner ? Self.lastOwnerMessage : "Couldn't leave the household. Try again."
    }

    public static func saveError(_ failure: ApiFailure) -> String {
        switch failure {
        case .forbidden: "Only owners can change household settings."
        case .network: "Couldn't save. Check your connection and try again."
        default: "Couldn't save. Try again."
        }
    }

    // MARK: Pure helpers (port of web `HouseholdSettings.ts`)

    /// Offline wins over role: the whole screen is read-only without a connection.
    public static func settingsMode(role: Member.Role?, online: Bool) -> SettingsMode {
        if !online { return .offline }
        return role == .owner ? .owner : .member
    }

    /// The household as it will look once the module is saved.
    public static func withModule(_ household: Household, _ module: ModuleKey, on: Bool) -> Household {
        var copy = household
        switch module {
        case .shopping: copy.moduleShopping = on
        case .pantry: copy.modulePantry = on
        case .maintenance: copy.moduleMaintenance = on
        }
        return copy
    }

    public static func isOn(_ household: Household, _ module: ModuleKey) -> Bool {
        switch module {
        case .shopping: household.moduleShopping
        case .pantry: household.modulePantry
        case .maintenance: household.moduleMaintenance
        }
    }

    static func landing(_ household: Household) -> LandingHousehold {
        LandingHousehold(
            id: household.id, moduleShopping: household.moduleShopping,
            modulePantry: household.modulePantry, moduleMaintenance: household.moduleMaintenance
        )
    }

    public static let currencies = ["AUD", "CAD", "CHF", "CZK", "DKK", "EUR", "GBP", "HUF", "JPY", "NOK", "PLN", "SEK", "USD"]

    /// The short list, plus the household's own value when it is not in it so the picker never lies.
    public static func currencyOptions(current: String) -> [String] {
        currencies.contains(current) ? currencies : (currencies + [current]).sorted()
    }
}
