import XCTest

/// E2E-SHOP-1, the add part (TESTING §8, SHOP-02-ios) against the real API: a household with a list and a checked
/// "Milk" in its history; type "mi", tap the suggestion, then type a new name and press Return; both rows reach the
/// server. Local only (CI skips the UI tests).
/// Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class QuickAddUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?
    private let householdId = UUID().uuidString.lowercased()
    private let listId = UUID().uuidString.lowercased()

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        user = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        guard let user else { return }
        _ = try? await URLSession.shared.data(
            for: E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/household?id=eq.\(householdId)", method: "DELETE")
        )
        await E2E.deleteUser(key: serviceRoleKey, id: user.id)
    }

    private func insert(_ table: String, _ row: [String: Any]) async throws {
        var request = E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/\(table)", method: "POST")
        request.httpBody = try JSONSerialization.data(withJSONObject: row)
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 201, "insert into \(table)")
    }

    func testSHOP02_addFromASuggestionAndAsTyped() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Quick Home", "created_by": user.id])
        try await insert("member", ["household_id": householdId, "user_id": user.id, "role": "owner"])
        try await insert("shopping_list", ["id": listId, "household_id": householdId, "name": "Groceries"])
        try await insert("list_item", [
            "id": UUID().uuidString.lowercased(), "household_id": householdId, "list_id": listId, "name": "Milk",
            "checked": true, "checked_at": ISO8601DateFormatter().string(from: Date()),
        ])

        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)
        XCTAssertTrue(app.staticTexts["tab.shopping.content"].waitForExistence(timeout: 20), app.debugDescription)

        // The lists overview is pulled first; open the list.
        let row = app.buttons["lists.row"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20), app.debugDescription)
        row.tap()
        let field = app.textFields["quickadd.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)

        // AC1, AC2: "mi" suggests Milk; tapping it adds a row named Milk.
        field.tap()
        field.typeText("mi")
        let suggestion = app.buttons["quickadd.suggestion.Milk"]
        XCTAssertTrue(suggestion.waitForExistence(timeout: 10), app.debugDescription)
        suggestion.tap()
        XCTAssertTrue(app.buttons["list.row.Milk"].waitForExistence(timeout: 10), app.debugDescription)

        // AC3: a name with no history is added as typed.
        field.typeText("Lightbulbs\n")
        XCTAssertTrue(app.buttons["list.row.Lightbulbs"].waitForExistence(timeout: 10), app.debugDescription)

        // Both rows reach the server, unchecked, in this list.
        var names: Set<String> = []
        for _ in 0..<20 where names != ["Milk", "Lightbulbs"] {
            let rows = try await E2E.rows(
                key: serviceRoleKey, table: "list_item", query: "list_id=eq.\(listId)&checked=eq.false&deleted_at=is.null&select=name"
            )
            names = Set(rows.compactMap { $0["name"] as? String })
            if names != ["Milk", "Lightbulbs"] { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertEqual(names, ["Milk", "Lightbulbs"])
    }
}
