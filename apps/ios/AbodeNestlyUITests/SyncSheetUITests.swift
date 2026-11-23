import XCTest

/// The sync chip and sheet (SHOP-08, DESIGN B12) against the real API. The Debug-only `E2E_OUTBOX` hook queues one
/// `list_item` change that the server rejects (no `list_id`), so the person sees "1 to sync" without a list screen,
/// opens the sheet, finds the failed change, and discards it. E2E-SHOP-2 proper (add and check offline, a second
/// client sees it) moves to SHOP-02/04-ios, as on web, because it needs the list screens.
/// Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class SyncSheetUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?
    private let householdId = UUID().uuidString.lowercased()

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        user = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        guard let user else { return }
        // The household first: it references the user through `created_by`; members cascade.
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

    func testSHOP08_chipShowsAFailedChangeAndTheSheetDiscardsIt() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Sync Home", "created_by": user.id])
        try await insert("member", ["household_id": householdId, "user_id": user.id, "role": "owner"])

        let app = XCUIApplication()
        let seed: [[String: Any]] = [[
            "table": "list_item", "op": "upsert", "row_id": UUID().uuidString.lowercased(), "data": ["name": "Milk"],
        ]]
        app.launchEnvironment["E2E_OUTBOX"] = String(decoding: try JSONSerialization.data(withJSONObject: seed), as: UTF8.self)
        try await E2E.signIn(app, as: user)
        XCTAssertTrue(app.staticTexts["tab.shopping.content"].waitForExistence(timeout: 20), app.debugDescription)

        // AC4: the rejected change still counts, so the chip reads "1 to sync".
        let chip = app.buttons["sync.chip"]
        let reads = NSPredicate(format: "label == %@", "1 to sync")
        XCTAssertEqual(XCTWaiter().wait(for: [expectation(for: reads, evaluatedWith: chip)], timeout: 20), .completed, app.debugDescription)

        // AC3: the sheet lists it with Retry and Discard.
        chip.tap()
        XCTAssertTrue(app.staticTexts["sync.status"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["sync.failed"].label, "Failed changes: 1")
        let retry = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'sync.retry.'")).firstMatch
        let discard = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'sync.discard.'")).firstMatch
        XCTAssertTrue(retry.exists && discard.exists)

        // AC5: Sync now just runs the loop again; the change is still turned down.
        app.buttons["sync.now"].tap()
        XCTAssertTrue(discard.waitForExistence(timeout: 10))

        // Discard removes it; with nothing left to sync the chip goes away.
        discard.tap()
        XCTAssertTrue(app.staticTexts["sync.failed"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["sync.failed"].label, "Failed changes: 0")
        app.buttons["sync.done"].tap()
        XCTAssertTrue(chip.waitForNonExistence(timeout: 15), app.debugDescription)
    }
}
