import Foundation
import Sync

/// The contract's `NotificationType` (SPEC §8), in DESIGN B9 order.
public enum NotificationType: String, CaseIterable, Sendable, Identifiable {
    case eatSoon = "eat_soon"
    case lowStock = "low_stock"
    case taskDue = "task_due"
    case warranty
    case activity

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .eatSoon: "Eat soon"
        case .lowStock: "Low stock"
        case .taskDue: "Task due"
        case .warranty: "Warranty"
        case .activity: "Activity"
        }
    }
}

/// DESIGN B9 / CORE-12. Web twin: `web/src/core/NotificationPrefs.ts`.
public enum NotificationPrefs {
    /// Rows of a disabled module are hidden; Activity is core and always stays. Unknown flags (`nil`) show only Activity.
    public static func visibleTypes(_ modules: HomeModules?) -> [NotificationType] {
        NotificationType.allCases.filter { type in
            switch type {
            case .eatSoon, .lowStock: modules?.pantry ?? false
            case .taskDue, .warranty: modules?.maintenance ?? false
            case .activity: true
            }
        }
    }

    /// A missing row means enabled.
    public static func enabled(_ rows: [NotificationPrefRow], _ type: NotificationType) -> Bool {
        rows.first { $0.type == type.rawValue }?.enabled ?? true
    }

    /// UUID v5 `row_id` of a switch, the same value on every platform (golden-value test on each).
    public static func rowId(userId: UUID, householdId: UUID, type: NotificationType) -> UUID {
        NotificationPrefId.rowId(userId: userId, householdId: householdId, type: type.rawValue)
    }
}
