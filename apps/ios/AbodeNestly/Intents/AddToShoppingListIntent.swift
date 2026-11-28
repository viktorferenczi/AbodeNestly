import AppIntents
import Core
import Foundation
import Shopping

/// SHOP-15: "Add to my shopping list" from Siri or Shortcuts. It lives in the app target and runs in the app's process
/// in the background (`openAppWhenRun` is off), so it shares the database and sync engine with the app: no extension,
/// no app group. The work is `AssistantShortcut.add`; this type only finds the household and speaks the reply.
struct AddToShoppingListIntent: AppIntent {
    static let title: LocalizedStringResource = "Add to Shopping List"
    static let description = IntentDescription("Adds an item to your shopping list.")
    static let openAppWhenRun = false

    @Parameter(title: "Item", requestValueDialog: "What do you want to add?")
    var item: String

    // `AppEnvironment` is built on the main actor, and the app's own screens use the same instance.
    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let environment = AppEnvironment.shared
        // No session, or no household opened yet: `nil`, which the shortcut answers with "sign in first".
        let session = await environment.auth.currentSession()
        let householdId = session == nil ? nil : environment.lastHousehold.read()
        let result = await AssistantShortcut.add(
            text: item, database: environment.database, householdId: householdId, userId: session?.userId
        )
        // A failing kick (offline) is fine: the row is already in the outbox and the next trigger pushes it.
        if case .added = result { await environment.sync.kick() }
        return .result(dialog: IntentDialog(stringLiteral: result.reply))
    }
}

/// The phrases Siri and Spotlight offer. A phrase can only interpolate an `AppEntity` or `AppEnum`, not a free `String`,
/// so Siri asks "What do you want to add?" after the phrase and the person says the item.
struct AbodeNestlyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AddToShoppingListIntent(),
            phrases: [
                "Add to my shopping list in \(.applicationName)",
                "Add to \(.applicationName) shopping list",
                "Add an item in \(.applicationName)",
            ],
            shortTitle: "Add to Shopping List",
            systemImageName: "cart.badge.plus"
        )
    }
}
