import AVFoundation
import SwiftUI
import UIKit

/// AVFoundation behind `ScannerDevice` (SHOP-10): `AVCaptureSession` + `AVCaptureMetadataOutput`. VisionKit's
/// `DataScannerViewController` is not used: it needs an A12 chip and does not run in the simulator.
@MainActor
public final class AVScannerDevice: NSObject, ScannerDevice, AVCaptureMetadataOutputObjectsDelegate {
    /// `AVCaptureSession` is not `Sendable`. It is configured on the main actor before it runs, and then only
    /// `startRunning` and `stopRunning` touch it, one at a time, on `queue` (as Apple's AVCam sample does).
    private final class SessionBox: @unchecked Sendable {
        let session = AVCaptureSession()
    }

    private static let types: [AVMetadataObject.ObjectType] = [.ean13, .ean8, .upce]

    private let queue = DispatchQueue(label: "com.abodenestly.scanner")
    private var box: SessionBox?
    private var camera: AVCaptureDevice?
    private var onCode: (@MainActor (String) -> Void)?

    public override init() { super.init() }

    public var authorizationStatus: CameraAuthorization {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined: .notDetermined
        case .authorized: .authorized
        case .restricted: .restricted
        default: .denied
        }
    }

    public func requestAccess() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .video)
    }

    public var captureSession: AVCaptureSession? { box?.session }
    public var hasTorch: Bool { camera?.hasTorch ?? false }

    public func start(onCode: @escaping @MainActor (String) -> Void) async throws {
        stop()
        // No camera at all (the simulator, an iPad without one): nothing to read with.
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) ?? AVCaptureDevice.default(for: .video) else {
            throw ScannerError.unsupported
        }
        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: camera)
        } catch {
            throw ScannerError.unavailable
        }
        let box = SessionBox()
        let output = AVCaptureMetadataOutput()
        box.session.beginConfiguration()
        guard box.session.canAddInput(input), box.session.canAddOutput(output) else {
            box.session.commitConfiguration()
            throw ScannerError.unavailable
        }
        box.session.addInput(input)
        box.session.addOutput(output)
        // The delegate runs on the main queue, so the callback below can assume the main actor.
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Types can only be set once the output is attached to a session.
        output.metadataObjectTypes = Self.types.filter { output.availableMetadataObjectTypes.contains($0) }
        box.session.commitConfiguration()
        self.camera = camera
        self.box = box
        self.onCode = onCode
        let queue = queue
        // `startRunning` blocks until the camera is up, so it never runs on the main thread.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                box.session.startRunning()
                continuation.resume()
            }
        }
    }

    public func stop() {
        if let camera, camera.torchMode == .on { try? setTorch(false) }
        if let box {
            queue.async { box.session.stopRunning() }
        }
        box = nil
        camera = nil
        onCode = nil
    }

    public func setTorch(_ on: Bool) throws {
        guard let camera, camera.hasTorch else { throw ScannerError.unavailable }
        try camera.lockForConfiguration()
        defer { camera.unlockForConfiguration() }
        camera.torchMode = on ? .on : .off
    }

    nonisolated public func metadataOutput(
        _ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection
    ) {
        guard let raw = metadataObjects.lazy.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        MainActor.assumeIsolated { onCode?(raw) }
    }
}

/// The camera preview: an `AVCaptureVideoPreviewLayer` as the layer of a plain `UIView`.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession?

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer? { layer as? AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer?.videoGravity = .resizeAspectFill
        view.previewLayer?.session = session
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        view.previewLayer?.session = session
    }
}
