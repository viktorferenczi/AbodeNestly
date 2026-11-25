import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let window: Duration = .milliseconds(150)

private func body(_ json: String) throws -> [String: Any] {
    try #require(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
}

@Suite("SHOP-04 check-off logic")
struct CheckOffLogicTests {
    @Test("SHOP-04: checkFields sets checked, checked_at and checked_by; uncheckFields resets all three")
    func fields() {
        let user = UUID()
        let on = CheckOff.checkFields(userId: user, now: "2026-11-25T10:00:00.000Z")
        #expect(on["checked"] == .bool(true) && on["checked_at"] == .string("2026-11-25T10:00:00.000Z"))
        #expect(on["checked_by"] == .string(user.uuidString.lowercased()))
        #expect(CheckOff.checkFields(userId: nil, now: "x")["checked_by"] == .null)
        #expect(CheckOff.uncheckFields() == ["checked": .bool(false), "checked_at": .null, "checked_by": .null])
    }

    @Test("SHOP-04: AC4 allChecked needs a checked row and nothing unchecked or pending", arguments: [
        (0, 0, 2, true), (1, 0, 2, false), (0, 1, 2, false), (0, 0, 0, false),
    ])
    func allChecked(unchecked: Int, pending: Int, checked: Int, expected: Bool) {
        #expect(CheckOff.allChecked(unchecked: unchecked, pending: pending, checked: checked) == expected)
    }

    @Test("SHOP-04: newestFirst orders by checkedAt, falling back to createdAt")
    func order() {
        let old = ListItemRecord(id: UUID(), name: "a", checked: true, createdAt: "2026-11-01T10:00:00.000Z", checkedAt: "2026-11-02T10:00:00.000Z")
        let new = ListItemRecord(id: UUID(), name: "b", checked: true, createdAt: "2026-11-01T09:00:00.000Z", checkedAt: "2026-11-03T10:00:00.000Z")
        let bare = ListItemRecord(id: UUID(), name: "c", checked: true, createdAt: "2026-11-01T08:00:00.000Z")
        #expect([bare, old, new].sorted(by: CheckOff.newestFirst).map(\.name) == ["b", "a", "c"])
    }

    @Test("SHOP-04: toast messages")
    func messages() {
        #expect(PendingAction.check(id: UUID(), name: "Milk").message == "Checked Milk")
        #expect(PendingAction.clear(id: UUID(), itemIds: [UUID()]).message == "Cleared 1 item")
        #expect(PendingAction.clear(id: UUID(), itemIds: [UUID(), UUID()]).message == "Cleared 2 items")
    }
}

@MainActor
@Suite("SHOP-04 check-off model", .serialized)
struct CheckOffModelTests {
    @Test("SHOP-04: AC2 Undo inside the window writes nothing, even after the window")
    func undo() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: window)
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        h.model.check(item)
        #expect(h.model.isPendingCheck(item.id) && h.model.unchecked.count == 1 && h.model.pendingActions.count == 1)
        #expect(try await h.outbox().isEmpty)
        h.model.undo(try #require(h.model.pendingActions.first).id)
        try await Task.sleep(for: .milliseconds(300))
        #expect(h.model.unchecked.count == 1 && h.model.checked.isEmpty)
        #expect(try await h.outbox().isEmpty)
    }

    @Test("SHOP-04: AC3 after the window the row is checked with checked_at and checked_by; one upsert with three columns")
    func checkWrites() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: window)
        await h.settle { h.model.unchecked.count == 1 }
        h.model.check(try #require(h.model.unchecked.first))
        await h.settle { h.model.pendingActions.isEmpty && h.model.checked.count == 1 }
        #expect(h.model.checked.count == 1 && h.model.unchecked.isEmpty)
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        let data = try body(queued[0].data)
        #expect(Set(data.keys) == ["checked", "checked_at", "checked_by"])
        #expect(data["checked"] as? Bool == true && data["checked_by"] as? String == h.user.uuidString.lowercased())
        #expect(h.model.checked.first?.checkedAt != nil)
        #expect(await h.kickCount >= 1)
    }

    @Test("SHOP-04: expiry skips a row that was deleted meanwhile")
    func skipsDeleted() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: window)
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        h.model.check(item)
        try await h.db.remove(table: "list_item", id: item.id)
        await h.settle { h.model.pendingActions.isEmpty }
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "delete")
    }

    @Test("SHOP-04: expiry skips a row already checked on another device")
    func skipsAlreadyChecked() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: window)
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        h.model.check(item)
        try await h.db.patch(table: "list_item", id: item.id, fields: CheckOff.checkFields(userId: nil, now: "2026-11-25T10:00:00.000Z"))
        await h.settle { h.model.pendingActions.isEmpty }
        #expect(try await h.outbox().count == 1)
    }

    @Test("SHOP-04: flush writes pending checks, clears and deletes at once")
    func flush() async throws {
        let h = try await DetailHarness(seed: { db, list in
            try await seedItem(db, list: list, name: "Milk")
            try await seedItem(db, list: list, name: "Bread")
            try await seedItem(db, list: list, name: "Eggs", checked: true)
        })
        await h.settle { h.model.unchecked.count == 2 && h.model.checked.count == 1 }
        let milk = try #require(h.model.unchecked.first { $0.name == "Milk" })
        let bread = try #require(h.model.unchecked.first { $0.name == "Bread" })
        h.model.check(milk)
        h.model.delete(bread)
        h.model.clearChecked()
        await h.model.flush()
        let queued = try await h.outbox()
        #expect(queued.filter { $0.op == "upsert" }.count == 1 && queued.filter { $0.op == "delete" }.count == 2)
        #expect(h.model.pendingActions.isEmpty)
    }

    @Test("SHOP-04: uncheck queues one upsert checked false with checked_at and checked_by null, and no pending action")
    func uncheck() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Eggs", checked: true) })
        await h.settle { h.model.checked.count == 1 }
        await h.model.uncheck(try #require(h.model.checked.first))
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.pendingActions.isEmpty && h.model.checked.isEmpty)
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        let data = try body(queued[0].data)
        #expect(data["checked"] as? Bool == false && data["checked_at"] is NSNull && data["checked_by"] is NSNull)
    }

    @Test("SHOP-04: AC5 clear checked soft-deletes the checked rows, one outbox delete each, unchecked rows untouched")
    func clear() async throws {
        let h = try await DetailHarness(seed: { db, list in
            for name in ["A", "B", "C"] { try await seedItem(db, list: list, name: name, checked: true) }
            try await seedItem(db, list: list, name: "Milk")
        }, undoWindow: window)
        await h.settle { h.model.checked.count == 3 }
        h.model.clearChecked()
        #expect(h.model.checked.isEmpty && h.model.unchecked.count == 1)
        #expect(try await h.outbox().isEmpty)
        await h.settle { h.model.pendingActions.isEmpty }
        let queued = try await h.outbox()
        #expect(queued.count == 3 && queued.allSatisfy { $0.op == "delete" })
        let rows = try await h.db.queryForTesting("SELECT name, deleted_at FROM list_item WHERE deleted_at IS NOT NULL")
        #expect(rows.count == 3 && h.model.unchecked.count == 1)
    }

    @Test("SHOP-04: Undo on a clear writes nothing and the rows return")
    func undoClear() async throws {
        let h = try await DetailHarness(seed: { db, list in
            for name in ["A", "B"] { try await seedItem(db, list: list, name: name, checked: true) }
        }, undoWindow: window)
        await h.settle { h.model.checked.count == 2 }
        h.model.clearChecked()
        h.model.undo(try #require(h.model.pendingActions.first).id)
        try await Task.sleep(for: .milliseconds(300))
        #expect(h.model.checked.count == 2)
        #expect(try await h.outbox().isEmpty)
    }

    @Test("SHOP-04: a row checked during a clear window is not swept in")
    func clearSnapshot() async throws {
        let h = try await DetailHarness(seed: { db, list in
            try await seedItem(db, list: list, name: "A", checked: true)
            try await seedItem(db, list: list, name: "Milk")
        }, undoWindow: window)
        await h.settle { h.model.checked.count == 1 && h.model.unchecked.count == 1 }
        h.model.clearChecked()
        try await h.db.patch(table: "list_item", id: try #require(h.model.unchecked.first).id, fields: CheckOff.checkFields(userId: nil, now: "2026-11-25T10:00:00.000Z"))
        await h.settle { h.model.pendingActions.isEmpty }
        #expect(h.model.checked.count == 1 && h.model.checked.first?.name == "Milk")
        #expect(try await h.outbox().filter { $0.op == "delete" }.count == 1)
    }

    @Test("SHOP-04: deleting a pending-check item cancels the check")
    func deleteCancelsCheck() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: window)
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        h.model.check(item)
        h.model.delete(item)
        #expect(h.model.pendingActions.count == 1)
        await h.settle { h.model.pendingActions.isEmpty }
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "delete")
    }

    @Test("SHOP-04: observeListDetail returns checkedAt and the checked group is newest check-off first")
    func checkedOrder() async throws {
        let h = try await DetailHarness(seed: { db, list in
            for (name, at) in [("Old", "2026-11-02T10:00:00.000Z"), ("New", "2026-11-03T10:00:00.000Z")] {
                try await db.upsert(table: "list_item", row: [
                    "id": .string(UUID().uuidString.lowercased()), "list_id": .string(list.uuidString.lowercased()), "name": .string(name),
                    "checked": .bool(true), "checked_at": .string(at), "created_at": .string("2026-11-01T10:00:00.000Z"),
                ])
            }
        })
        await h.settle { h.model.checked.count == 2 }
        #expect(h.model.checked.map(\.name) == ["New", "Old"])
        #expect(h.model.checked.first?.checkedAt == "2026-11-03T10:00:00.000Z")
    }
}
