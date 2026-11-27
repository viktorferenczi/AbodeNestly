import AVFoundation

/// Camera permission, as the scanner needs to tell it apart (AVFoundation's `AVAuthorizationStatus`, narrowed).
public enum CameraAuthorization: Sendable, Equatable {
    case notDetermined, authorized, denied, restricted
}

/// Why the camera could not start. Denied or restricted access is not an error here: it shows in `authorizationStatus`.
public enum ScannerError: Error, Sendable, Equatable {
    /// No camera, or the device cannot read barcodes (the simulator).
    case unsupported
    /// The camera is busy or failed to start.
    case unavailable
}

/// The camera, its permission and its torch behind one small interface, so tests and previews use `FakeScannerDevice`.
/// Web twin: `ScannerDevice` in `web/src/shopping/ScannerDevice.ts`. Main-actor only: the model and the view live there,
/// and the AVFoundation implementation hops to its own queue where a call blocks.
@MainActor
public protocol ScannerDevice: AnyObject {
    var authorizationStatus: CameraAuthorization { get }
    /// Asks the system for camera access; `true` when granted.
    func requestAccess() async -> Bool
    /// Starts reading and calls `onCode` with the raw value of each EAN-13, EAN-8 or UPC-E code seen (UPC-A arrives as
    /// EAN-13). Throws `ScannerError`.
    func start(onCode: @escaping @MainActor (String) -> Void) async throws
    func stop()
    /// Only meaningful after `start` succeeded.
    var hasTorch: Bool { get }
    func setTorch(_ on: Bool) throws
    /// What the preview layer shows; `nil` for a fake.
    var captureSession: AVCaptureSession? { get }
}

/// A scripted device for tests (TESTING §2: hand-written fakes, no mocking libraries).
@MainActor
public final class FakeScannerDevice: ScannerDevice {
    public var authorizationStatus: CameraAuthorization
    /// What `requestAccess` answers while the status is `.notDetermined`.
    public var grantsAccess = true
    public var startError: ScannerError?
    public var hasTorch: Bool
    public private(set) var torchOn = false
    public private(set) var startCount = 0
    public private(set) var stopCount = 0
    private var onCode: (@MainActor (String) -> Void)?

    public init(authorization: CameraAuthorization = .authorized, hasTorch: Bool = false, startError: ScannerError? = nil) {
        authorizationStatus = authorization
        self.hasTorch = hasTorch
        self.startError = startError
    }

    public var captureSession: AVCaptureSession? { nil }

    public func requestAccess() async -> Bool {
        if authorizationStatus == .notDetermined { authorizationStatus = grantsAccess ? .authorized : .denied }
        return authorizationStatus == .authorized
    }

    public func start(onCode: @escaping @MainActor (String) -> Void) async throws {
        startCount += 1
        if let startError { throw startError }
        self.onCode = onCode
    }

    public func stop() {
        stopCount += 1
        onCode = nil
        torchOn = false
    }

    public func setTorch(_ on: Bool) throws { torchOn = on }

    /// Plays one camera read; ignored once the device is stopped, like a real one.
    public func scan(_ raw: String) { onCode?(raw) }
}
