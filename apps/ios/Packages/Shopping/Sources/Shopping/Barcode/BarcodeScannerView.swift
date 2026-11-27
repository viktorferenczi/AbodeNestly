import Contract
import SwiftUI
import UI

/// DESIGN C7, presented full screen from the list's QuickAddBar. States: scanning (preview, guide box, torch when the
/// camera has one), found, not-found, failed lookup, queued (offline), permission-denied, unsupported and
/// camera-unavailable. "Enter code manually" is on offer wherever there is no result card. Web twin: `BarcodeScanner.tsx`.
public struct BarcodeScannerView: View {
    @Bindable private var model: BarcodeScannerModel
    private let onClose: () -> Void
    @FocusState private var manualFocused: Bool
    @FocusState private var nameFocused: Bool

    public init(model: BarcodeScannerModel, onClose: @escaping () -> Void) {
        self.model = model
        self.onClose = onClose
    }

    public var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if model.addFailed {
                        Text("Couldn't add that item. Try again.")
                            .font(.subheadline)
                            .foregroundStyle(Tokens.danger)
                            .accessibilityIdentifier("barcode.error")
                    }
                    content
                }
                .padding(Tokens.screenPadding)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(Tokens.bg)
        .task { await model.startCamera() }
        .onDisappear { model.stop() }
        // No camera: the manual field is the whole screen, so it takes focus. A result that asks for a name does too.
        .onChange(of: model.state, initial: true) { _, state in
            manualFocused = state.lacksCamera
            switch state {
            case .notFound, .failed: nameFocused = true
            default: break
            }
        }
        .accessibilityIdentifier("barcode.screen")
    }

    private var header: some View {
        HStack {
            Text("Scan barcode")
                .font(.headline)
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button {
                model.stop()
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                    .contentShape(Rectangle())
            }
            .tint(Tokens.text)
            .accessibilityLabel("Close")
            .accessibilityIdentifier("barcode.close")
        }
        .padding(.horizontal, Tokens.screenPadding)
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .scanning:
            scanning
        case .looking(let code):
            HStack(spacing: 8) {
                ProgressView()
                Text("Looking up \(code)…")
                    .foregroundStyle(Tokens.text2)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("barcode.looking")
        case .found(let product):
            found(product)
        case .notFound(let code):
            nameCard(title: "We don't know this product", code: code, retry: false)
        case .failed(let code):
            nameCard(title: "Couldn't look that up. Try again.", code: code, retry: true)
        case .queued(let code):
            queued(code)
        case .permissionDenied:
            noCamera(
                title: "Camera access is off",
                hint: "Allow the camera for AbodeNestly in Settings, then open the scanner again. You can still enter the code."
            )
        case .cameraUnavailable:
            noCamera(title: "Can't use the camera", hint: "Another app may be using it. You can still enter the code.")
        case .unsupported:
            noCamera(title: "Scanning isn't available on this device", hint: "You can still enter the code printed under the barcode.")
        }
    }

    // MARK: states

    private var scanning: some View {
        VStack(alignment: .leading, spacing: 16) {
            ZStack {
                Color.black
                CameraPreview(session: model.previewSession)
                RoundedRectangle(cornerRadius: Tokens.cardRadius)
                    .stroke(.white, lineWidth: 3)
                    .frame(width: 260, height: 150)
                    .accessibilityIdentifier("barcode.guide")
            }
            .frame(height: 320)
            .clipShape(RoundedRectangle(cornerRadius: Tokens.cardRadius))
            .accessibilityIdentifier("barcode.preview")
            Text("Point the camera at a barcode")
                .foregroundStyle(Tokens.text2)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("barcode.hint")
            if model.hasTorch {
                Button { model.toggleTorch() } label: {
                    Label("Torch", systemImage: model.torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                        .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
                }
                .buttonStyle(.bordered)
                .tint(Tokens.accent)
                .accessibilityLabel("Torch")
                .accessibilityValue(model.torchOn ? "On" : "Off")
                .accessibilityAddTraits(model.torchOn ? .isSelected : [])
                .accessibilityIdentifier("barcode.torch")
            }
            manualForm
        }
    }

    private func found(_ product: Product) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if let url = Barcode.imageURL(product.imageUrl) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFit()
                } placeholder: {
                    ProgressView()
                }
                .frame(maxWidth: .infinity, maxHeight: 160)
                .accessibilityLabel(product.name)
                .accessibilityIdentifier("barcode.image")
            }
            Text(product.name)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("barcode.found.name")
            if let brand = product.brand, !brand.isEmpty {
                Text(brand)
                    .foregroundStyle(Tokens.text2)
                    .accessibilityIdentifier("barcode.found.brand")
            }
            HStack(spacing: 12) {
                scanAgainButton("Scan again")
                Button("Add") { Task { if await model.addFound() { onClose() } } }
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .disabled(model.adding)
                    .accessibilityIdentifier("barcode.add")
            }
        }
    }

    /// Not-found and failed share the name field; only the failed one has Retry.
    private func nameCard(title: String, code: String, retry: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier(retry ? "barcode.failed.title" : "barcode.notfound.title")
            Text(code)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("barcode.code")
            TextField("Name", text: $model.name)
                .focused($nameFocused)
                .submitLabel(.done)
                .onSubmit { Task { if await model.addNamed() { onClose() } } }
                .padding(.horizontal, 12)
                .frame(minHeight: Tokens.minTouchTarget)
                .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                .accessibilityLabel("Name")
                .accessibilityIdentifier("barcode.name.field")
            HStack(spacing: 12) {
                if retry {
                    Button("Retry") { Task { await model.retry() } }
                        .buttonStyle(.bordered)
                        .tint(Tokens.accent)
                        .frame(minHeight: Tokens.minTouchTarget)
                        .accessibilityIdentifier("barcode.retry")
                } else {
                    scanAgainButton("Scan again")
                }
                Button("Add") { Task { if await model.addNamed() { onClose() } } }
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .disabled(model.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.adding)
                    .accessibilityIdentifier("barcode.add")
            }
        }
    }

    private func queued(_ code: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Added \(code). We'll look it up when you're back online")
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("barcode.queued")
            HStack(spacing: 12) {
                scanAgainButton("Scan another")
                Button("Done", action: onClose)
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityIdentifier("barcode.done")
            }
        }
    }

    private func noCamera(title: String, hint: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("barcode.nocamera.title")
            Text(hint)
                .foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("barcode.nocamera.hint")
            manualForm
        }
    }

    private func scanAgainButton(_ title: String) -> some View {
        Button(title) { Task { await model.scanAgain() } }
            .buttonStyle(.bordered)
            .tint(Tokens.accent)
            .frame(minHeight: Tokens.minTouchTarget)
            .accessibilityIdentifier("barcode.again")
    }

    private var manualForm: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Enter code manually")
                .font(.subheadline)
                .foregroundStyle(Tokens.text2)
            HStack(spacing: 8) {
                TextField("Barcode", text: $model.code)
                    .focused($manualFocused)
                    .keyboardType(.numberPad)
                    .autocorrectionDisabled()
                    .onChange(of: model.code) { _, _ in model.clearCodeError() }
                    .padding(.horizontal, 12)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                    .accessibilityLabel("Barcode")
                    .accessibilityIdentifier("barcode.manual.field")
                Button("Look up") { Task { await model.submitCode() } }
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityIdentifier("barcode.manual.submit")
            }
            if model.codeError {
                Text(Barcode.codeError)
                    .font(.subheadline)
                    .foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("barcode.manual.error")
            }
        }
    }
}
