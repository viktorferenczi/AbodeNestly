import AVFoundation
import Contract
import Foundation
import Observation
import Sync

/// DESIGN C7 states. `looking` is between a code being read and the lookup answering; `queued` is the offline state.
public enum BarcodeScannerState: Equatable {
    case scanning
    case looking(String)
    case found(Product)
    case notFound(String)
    /// The lookup failed in a way that is not "offline" (401, 500): nothing was written; Retry or type a name.
    case failed(String)
    case queued(String)
    case permissionDenied
    case unsupported
    case cameraUnavailable

    /// The states with no camera: only the manual code field is on offer.
    var lacksCamera: Bool {
        switch self {
        case .permissionDenied, .unsupported, .cameraUnavailable: true
        default: false
        }
    }
}

/// SHOP-10 scanner logic. Web twin: the `BarcodeScanner` component and `web/src/shopping/Barcode.ts`; keep the rules in
/// step. The item write is injected (`add`), so this type never touches the database: `ListDetailModel.makeScanner`
/// supplies the outbox write.
@Observable
@MainActor
public final class BarcodeScannerModel {
    public private(set) var state: BarcodeScannerState = .scanning
    public private(set) var hasTorch = false
    public private(set) var torchOn = false
    /// What the preview layer shows once the camera is running.
    public private(set) var previewSession: AVCaptureSession?
    /// The manual code field.
    public var code = ""
    public private(set) var codeError = false
    /// The name field of not-found and failed. It is prefilled from the cache only when a code first appears, so a retry
    /// never wipes what was typed.
    public var name = ""
    /// The item could not be stored; shown as "Couldn't add that item. Try again."
    public private(set) var addFailed = false
    /// An Add is running; a second tap must not make a second item.
    public private(set) var adding = false

    @ObservationIgnored private let device: any ScannerDevice
    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let lookup: @Sendable (String) async -> ProductLookupResult
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let add: @MainActor (_ name: String, _ barcode: String?) async -> UUID?
    @ObservationIgnored private let store: BarcodeStore
    /// A code has been taken from the camera (or typed); no second one is handled until Scan again.
    @ObservationIgnored private var handled = false
    /// True from the start of a lookup to its end: one code can never produce two rows.
    @ObservationIgnored private var looking = false
    @ObservationIgnored private var starting = false
    @ObservationIgnored private var nameCode: String?

    public init(
        device: any ScannerDevice, householdId: UUID, lookup: @escaping @Sendable (String) async -> ProductLookupResult,
        isOnline: @escaping @MainActor () -> Bool, store: BarcodeStore = BarcodeStore(),
        add: @escaping @MainActor (_ name: String, _ barcode: String?) async -> UUID?
    ) {
        self.device = device
        self.householdId = householdId
        self.lookup = lookup
        self.isOnline = isOnline
        self.store = store
        self.add = add
    }

    // MARK: camera

    /// Checks permission, then starts the camera. Manual entry works in every state.
    public func startCamera() async {
        guard !starting, state == .scanning else { return }
        starting = true
        defer { starting = false }
        handled = false
        torchOn = false
        switch device.authorizationStatus {
        case .denied, .restricted:
            state = .permissionDenied
            return
        case .notDetermined:
            guard await device.requestAccess() else {
                state = .permissionDenied
                return
            }
        case .authorized:
            break
        }
        do {
            try await device.start { [weak self] raw in self?.didRead(raw) }
            hasTorch = device.hasTorch
            previewSession = device.captureSession
        } catch ScannerError.unsupported {
            state = .unsupported
        } catch {
            state = .cameraUnavailable
        }
        // A code was typed while the camera was starting (or the screen closed): the camera is not needed any more.
        if state != .scanning { stop() }
    }

    public func stop() {
        device.stop()
        hasTorch = false
        torchOn = false
        previewSession = nil
    }

    public func toggleTorch() {
        let next = !torchOn
        do {
            try device.setTorch(next)
            torchOn = next
        } catch {
            torchOn = false
        }
    }

    /// Unreadable or partial reads are ignored; the next frame tries again.
    private func didRead(_ raw: String) {
        let code = Barcode.normalize(raw)
        guard state == .scanning, Barcode.isBarcode(code) else { return }
        Task { await handle(code) }
    }

    // MARK: lookup

    /// A code from the camera or the manual field. Ignored while another one is being handled.
    public func handle(_ raw: String) async {
        guard !handled, !looking else { return }
        handled = true
        looking = true
        stop()
        addFailed = false
        state = .looking(raw)
        // Offline is decided here, without a request, as on web (`navigator.onLine`).
        var result = ProductLookupResult.offline
        if isOnline() { result = await lookup(raw) }
        switch result {
        case .found(let product):
            state = .found(product)
        case .notFound:
            prefill(raw)
            state = .notFound(raw)
        case .failed:
            prefill(raw)
            state = .failed(raw)
        case .offline:
            // The code becomes the name, with no barcode (the list_item foreign key needs a product row), and the lookup waits.
            if let id = await add(raw, nil) {
                store.appendPending(PendingLookup(householdId: householdId, itemId: id, code: raw))
                state = .queued(raw)
            } else {
                addFailed = true
                handled = false
                state = .scanning
                looking = false
                await startCamera()
            }
        }
        looking = false
    }

    /// Retry after a failed lookup. A tap while the lookup runs does nothing.
    public func retry() async {
        guard case .failed(let failedCode) = state, !looking else { return }
        handled = false
        await handle(failedCode)
    }

    public func submitCode() async {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Barcode.isBarcode(trimmed) else {
            codeError = true
            return
        }
        codeError = false
        await handle(trimmed)
    }

    public func clearCodeError() { codeError = false }

    /// The cache fills the name field only when a new code appears.
    private func prefill(_ raw: String) {
        guard nameCode != raw else { return }
        nameCode = raw
        name = store.cachedName(raw) ?? ""
    }

    // MARK: adding

    /// Found: the product's name and its barcode (the product row exists now, so the foreign key holds). `true` when stored.
    public func addFound() async -> Bool {
        guard case .found(let product) = state, !adding else { return false }
        adding = true
        defer { adding = false }
        guard await add(product.name, product.barcode) != nil else {
            addFailed = true
            return false
        }
        return true
    }

    /// Not found, or the lookup failed: the typed name with no barcode, remembered on this device for next time.
    public func addNamed() async -> Bool {
        let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let named: String
        switch state {
        case .notFound(let code), .failed(let code): named = code
        default: return false
        }
        guard !typed.isEmpty, !adding else { return false }
        adding = true
        defer { adding = false }
        guard await add(typed, nil) != nil else {
            addFailed = true
            return false
        }
        store.cacheName(code: named, name: typed)
        return true
    }

    /// Back to the camera for the next code.
    public func scanAgain() async {
        nameCode = nil
        handled = false
        addFailed = false
        code = ""
        codeError = false
        state = .scanning
        await startCamera()
    }
}
