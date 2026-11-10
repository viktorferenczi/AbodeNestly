import Foundation

public enum AppTab: String, Sendable, Equatable, CaseIterable {
    case shopping, pantry, maintenance, home
}

/// The household columns the landing rule reads.
public struct LandingHousehold: Sendable, Equatable {
    public let id: UUID
    public let moduleShopping: Bool
    public let modulePantry: Bool
    public let moduleMaintenance: Bool

    public init(id: UUID, moduleShopping: Bool, modulePantry: Bool, moduleMaintenance: Bool) {
        self.id = id
        self.moduleShopping = moduleShopping
        self.modulePantry = modulePantry
        self.moduleMaintenance = moduleMaintenance
    }
}

/// An active (not left, household not deleted) membership with its household flags.
public struct Membership: Sendable, Equatable {
    public let householdId: UUID
    public let joinedAt: Date
    public let household: LandingHousehold

    public init(householdId: UUID, joinedAt: Date, household: LandingHousehold) {
        self.householdId = householdId
        self.joinedAt = joinedAt
        self.household = household
    }
}

public enum Destination: Sendable, Equatable {
    case welcome
    case household(id: UUID, tab: AppTab)
    /// Signed in, but memberships could not be read while offline (SPEC §6.5): enter the shell at Home.
    case home
}

/// Port of web `landing.ts` so both clients land the same way.
public enum Landing {
    /// First enabled module in the order shopping → pantry → maintenance; Home when all are off.
    public static func startTab(_ household: LandingHousehold) -> AppTab {
        if household.moduleShopping { return .shopping }
        if household.modulePantry { return .pantry }
        if household.moduleMaintenance { return .maintenance }
        return .home
    }

    /// The last used household if still active, else the earliest joined.
    public static func pickHousehold(_ memberships: [Membership], lastHouseholdId: UUID?) -> Membership? {
        if let last = memberships.first(where: { $0.householdId == lastHouseholdId }) { return last }
        return memberships.min { $0.joinedAt < $1.joinedAt }
    }

    public static func destination(memberships: [Membership], lastHouseholdId: UUID?) -> Destination {
        guard let chosen = pickHousehold(memberships, lastHouseholdId: lastHouseholdId) else { return .welcome }
        return .household(id: chosen.householdId, tab: startTab(chosen.household))
    }
}
