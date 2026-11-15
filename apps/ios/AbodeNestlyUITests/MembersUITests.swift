import XCTest

/// E2E-CORE-1, members half: A creates a household and invites B; both see each other with the "You" marker;
/// A cannot leave as the only owner, then removes B and `member.left_at` is set after the 5 s undo window.
/// Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class MembersUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var owner: E2E.TestUser?
    private var joiner: E2E.TestUser?

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        owner = try await E2E.createUser(key: serviceRoleKey)
        joiner = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        if let owner {
            // Households first: they reference the owner through `created_by`.
            let households = try? await E2E.rows(
                key: serviceRoleKey, table: "household", query: "created_by=eq.\(owner.id)&select=id"
            )
            for id in households?.compactMap({ $0["id"] as? String }) ?? [] {
                _ = try? await URLSession.shared.data(
                    for: E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/household?id=eq.\(id)", method: "DELETE")
                )
            }
            await E2E.deleteUser(key: serviceRoleKey, id: owner.id)
        }
        if let joiner { await E2E.deleteUser(key: serviceRoleKey, id: joiner.id) }
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id]
    }

    private func openMembers(_ app: XCUIApplication) {
        // A returning user lands on their first module tab; a new household lands on Home.
        let homeTab = app.tabBars.buttons["Home"]
        XCTAssertTrue(homeTab.waitForExistence(timeout: 15))
        homeTab.tap()
        XCTAssertTrue(app.staticTexts["tab.home.content"].waitForExistence(timeout: 15))
        app.buttons["home.members"].tap()
        XCTAssertTrue(app.buttons["members.invite"].waitForExistence(timeout: 10))
    }

    func testCORE04_membersSeeEachOtherOwnerCannotLeaveThenRemovesTheOther() async throws {
        let owner = try XCTUnwrap(owner), joiner = try XCTUnwrap(joiner)

        // A creates the household and reads its invite code.
        let appA = XCUIApplication()
        try await E2E.signIn(appA, as: owner)
        XCTAssertTrue(appA.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        let name = appA.textFields["welcome.name"]
        name.tap()
        name.typeText("Test Home")
        appA.buttons["welcome.create"].tap()
        openMembers(appA)
        // Alone in the household: A sees only themselves, marked "You", and cannot leave.
        XCTAssertTrue(element(appA, "members.row.\(owner.id).you").waitForExistence(timeout: 15), appA.debugDescription)
        appA.buttons["members.invite"].tap()
        let codeText = appA.descendants(matching: .any)["invite.code"]
        XCTAssertTrue(codeText.waitForExistence(timeout: 15))
        let code = codeText.label.replacingOccurrences(of: "Invite code ", with: "")
        XCTAssertFalse(code.isEmpty)
        appA.terminate()

        // B joins with the code and sees both members, "You" on their own row and no overflow menu.
        let appB = XCUIApplication()
        try await E2E.signIn(appB, as: joiner)
        XCTAssertTrue(appB.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        let field = appB.textFields["welcome.code"]
        field.tap()
        field.typeText(code)
        appB.buttons["welcome.join"].tap()
        openMembers(appB)
        XCTAssertTrue(element(appB, "members.row.\(joiner.id).you").waitForExistence(timeout: 15), appB.debugDescription)
        XCTAssertTrue(element(appB, "members.row.\(owner.id)").exists)
        XCTAssertFalse(element(appB, "members.row.\(owner.id).you").exists)
        XCTAssertFalse(element(appB, "members.row.\(owner.id).menu").exists)
        XCTAssertTrue(appB.buttons["members.leave"].isEnabled)
        appB.terminate()

        // A signs in again: sees B, is blocked from leaving as the last owner, then removes B.
        let appA2 = XCUIApplication()
        try await E2E.signIn(appA2, as: owner)
        openMembers(appA2)
        XCTAssertTrue(element(appA2, "members.row.\(joiner.id)").waitForExistence(timeout: 15), appA2.debugDescription)
        XCTAssertTrue(element(appA2, "members.row.\(owner.id).you").exists)

        appA2.buttons["members.leave"].tap()
        let alert = appA2.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts[
            "You're the last owner. Make someone else an owner before leaving."
        ].exists, alert.debugDescription)
        alert.buttons["OK"].tap()

        appA2.buttons["members.row.\(joiner.id).menu"].tap()
        appA2.buttons["Remove"].tap()
        XCTAssertTrue(element(appA2, "undo-toast").waitForExistence(timeout: 5))
        XCTAssertFalse(element(appA2, "members.row.\(joiner.id)").exists)

        // `left_at` is set only once the 5 s window has passed.
        let households = try await E2E.rows(
            key: serviceRoleKey, table: "household", query: "created_by=eq.\(owner.id)&select=id"
        )
        let householdId = try XCTUnwrap(households.first?["id"] as? String)
        let query = "household_id=eq.\(householdId)&user_id=eq.\(joiner.id)&select=left_at"
        var leftAt: Any?
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            leftAt = try await E2E.rows(key: serviceRoleKey, table: "member", query: query).first?["left_at"]
            if let leftAt, !(leftAt is NSNull) { break }
            try await Task.sleep(for: .milliseconds(500))
        }
        XCTAssertTrue(leftAt != nil && !(leftAt is NSNull), "left_at was not set within the window")
        XCTAssertFalse(element(appA2, "members.row.\(joiner.id)").exists)
    }
}
