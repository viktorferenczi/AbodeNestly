import Contract

/// What `GET /products/{barcode}` came to (SHOP-10). Web twin: `lookup` in `web/src/shopping/Barcode.ts`.
public enum ProductLookupResult: Sendable, Equatable {
    case found(Product)
    /// 404, and 400 (an invalid code reads the same way: the API does not know it).
    case notFound
    /// Only what can pass by itself: no network, or a 502 while Open Food Facts is down. The lookup is queued.
    case offline
    /// Anything else (401, 500, an unreadable body). Nothing is written and nothing is queued.
    case failed
}
