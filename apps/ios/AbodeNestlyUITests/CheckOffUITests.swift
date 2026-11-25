import XCTest

/// E2E-SHOP-1, the check-off part (TESTING §8, SHOP-04-ios) against the real API: quick add Milk and Bread; check Milk
/// and tap Undo (it stays unchecked, nothing reaches the server); check Milk and let it land (under Checked (1), server
/// `checked` true); check Bread (All done); Clear checked and let it land (the list is empty, both server rows are soft
/// deleted). Local only (CI skips UI tests). Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and
/// `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class CheckOffUITests: XCTestCase {
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

    private func serverRow(_ name: String, _ select: String) async throws -> [String: Any]? {
        try await E2E.rows(key: serviceRoleKey, table: "list_item", query: "list_id=eq.\(listId)&name=eq.\(name)&select=\(select)").first
    }

    func testSHOP04_checkOffUndoAndClear() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Check Home", "created_by": user.id])
        try await insert("member", ["household_id": householdId, "user_id": user.id, "role": "owner"])
        try await insert("shopping_list", ["id": listId, "household_id": householdId, "name": "Groceries"])

        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)
        XCTAssertTrue(app.staticTexts["tab.shopping.content"].waitForExistence(timeout: 20), app.debugDescription)
        let listRow = app.buttons["lists.row"].firstMatch
        XCTAssertTrue(listRow.waitForExistence(timeout: 20), app.debugDescription)
        listRow.tap()

        let field = app.textFields["quickadd.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap()
        field.typeText("Milk\n")
        field.typeText("Bread\n")
        XCTAssertTrue(app.buttons["list.row.Bread"].waitForExistence(timeout: 10), app.debugDescription)

        // AC2: check, then Undo inside the window; the row stays unchecked and the server row is never checked.
        app.buttons["list.row.Milk.check"].tap()
        let undo = app.buttons["Undo"]
        XCTAssertTrue(undo.waitForExistence(timeout: 5), app.debugDescription)
        undo.tap()
        XCTAssertTrue(app.buttons["list.row.Milk.check"].waitForExistence(timeout: 5), app.debugDescription)
        try await Task.sleep(for: .seconds(6))
        XCTAssertFalse(app.buttons["list.checked"].exists || app.staticTexts["list.checked"].exists)
        let untouched = try await serverRow("Milk", "checked")
        XCTAssertEqual(untouched?["checked"] as? Bool, false)

        // AC3: check and let the window end; the row moves under Checked (1) and the server row is checked.
        app.buttons["list.row.Milk.check"].tap()
        XCTAssertTrue(app.staticTexts["list.checked"].waitForExistence(timeout: 15), app.debugDescription)
        var checked = false
        for _ in 0..<20 where !checked {
            checked = (try await serverRow("Milk", "checked"))?["checked"] as? Bool == true
            if !checked { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(checked, "the check-off reached the server")

        // AC4: check the last one; the all-checked state offers Clear checked.
        app.buttons["list.row.Bread.check"].tap()
        XCTAssertTrue(app.staticTexts["list.allDone"].waitForExistence(timeout: 15), app.debugDescription)

        // AC5: Clear checked, let the window end; both rows are soft-deleted on the server.
        app.buttons["list.clearChecked"].tap()
        var deleted = false
        for _ in 0..<30 where !deleted {
            let milk = try await serverRow("Milk", "deleted_at")
            let bread = try await serverRow("Bread", "deleted_at")
            deleted = milk != nil && bread != nil && !(milk?["deleted_at"] is NSNull) && !(bread?["deleted_at"] is NSNull)
            if !deleted { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(deleted, "both rows are soft-deleted on the server")
        XCTAssertTrue(app.staticTexts["list.empty"].waitForExistence(timeout: 10), app.debugDescription)
    }
}
