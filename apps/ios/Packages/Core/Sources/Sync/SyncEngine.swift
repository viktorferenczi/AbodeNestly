/// What screens call to ask for fresh data. SHOP-08's push/pull loop replaces `ActivityPull` (Core/Home) behind
/// this seam; screens and their triggers stay as they are. Web twin: `web/src/sync/SyncEngine.ts`.
public protocol SyncEngine: Sendable {
    func kick() async
}
