import Foundation

/// DESIGN B3 states. Port of web `switcherState`.
public enum SwitcherState: Equatable, Sendable {
    case oneHousehold
    case many
    /// The target household's name, for "Switching to X…".
    case switching(String)
}

/// Pure switch logic, shared by the model and tests (web twin: `web/src/core/Switcher.ts`).
public enum Switcher {
    public static func state(householdCount: Int, switchingTo name: String?) -> SwitcherState {
        if let name { return .switching(name) }
        return householdCount > 1 ? .many : .oneHousehold
    }

    /// Same tab in the target household; a module tab whose module is off there falls back to its start tab.
    public static func targetTab(current: AppTab, target: LandingHousehold) -> AppTab {
        let enabled: Bool = switch current {
        case .shopping: target.moduleShopping
        case .pantry: target.modulePantry
        case .maintenance: target.moduleMaintenance
        case .home: true
        }
        return enabled ? current : Landing.startTab(target)
    }
}
