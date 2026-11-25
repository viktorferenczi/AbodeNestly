import XCTest

/// E2E-SHOP-1, the item-edit part (TESTING §8, SHOP-03-ios) against the real API: quick add "Milk", open it, set
/// quantity 2, unit "l" and a note, Save; the row shows "2 l" and the note. Reopen, clear the note, Save; the note is
/// gone and "2 l" stays. The server row ends with quantity 2, unit "l", note null. Local only (CI skips UI tests).
/// Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class ItemSheetUITests: XCTestCase {
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

    func testSHOP03_editQuantityUnitAndNote() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Sheet Home", "created_by": user.id])
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
        let row = app.buttons["list.row.Milk"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)

        // AC2: set all three and save.
        row.tap()
        XCTAssertTrue(app.textFields["stepper.field"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["stepper.plus"].tap()
        app.buttons["stepper.plus"].tap()
        let unit = app.textFields["item.unit"]
        unit.tap()
        unit.typeText("l")
        let note = app.textFields["item.note"]
        note.tap()
        note.typeText("lactose free")
        app.buttons["item.save"].tap()
        XCTAssertTrue(app.staticTexts["2 l"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["lactose free"].exists, app.debugDescription)

        // AC3: clear the note; the quantity stays.
        app.buttons["list.row.Milk"].tap()
        let editNote = app.textFields["item.note"]
        XCTAssertTrue(editNote.waitForExistence(timeout: 10), app.debugDescription)
        editNote.tap()
        editNote.press(forDuration: 1.0)
        app.menuItems["Select All"].tap()
        editNote.typeText(XCUIKeyboardKey.delete.rawValue)
        app.buttons["item.save"].tap()
        XCTAssertTrue(app.staticTexts["2 l"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.staticTexts["lactose free"].exists, app.debugDescription)

        // The server row ends with quantity 2, unit "l", note null.
        var found: [String: Any]?
        for _ in 0..<20 where found == nil {
            let rows = try await E2E.rows(
                key: serviceRoleKey, table: "list_item", query: "list_id=eq.\(listId)&name=eq.Milk&select=quantity,unit,note"
            )
            if let first = rows.first, first["note"] is NSNull { found = first }
            if found == nil { try await Task.sleep(for: .seconds(1)) }
        }
        let row0 = try XCTUnwrap(found)
        XCTAssertEqual((row0["quantity"] as? NSNumber)?.doubleValue, 2)
        XCTAssertEqual(row0["unit"] as? String, "l")
        XCTAssertTrue(row0["note"] is NSNull)
    }
}
