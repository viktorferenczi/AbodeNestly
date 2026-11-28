import Foundation
import Observation
import UIKit

/// DESIGN C8 logic. Online only: with no connection nothing is sent. Web twin: `ShareSheet.tsx`.
/// The calls are injected (`Shopping` does not depend on `Core`, where `ApiService` lives); `ListDetailModel.makeShare`
/// supplies the local write of the server's token.
@Observable
@MainActor
public final class ShareSheetModel {
    public private(set) var state: ShareState
    /// "Link copied" is showing (3 s).
    public private(set) var copied = false

    @ObservationIgnored private let webBase: URL
    @ObservationIgnored private let share: @Sendable () async -> Result<String, ShareFailure>
    @ObservationIgnored private let stop: @Sendable () async -> Result<Void, ShareFailure>
    @ObservationIgnored private let record: @MainActor (String?) async -> Void
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let copy: @MainActor (String) -> Void
    @ObservationIgnored private let copiedWindow: Duration
    @ObservationIgnored private var copiedTimer: Task<Void, Never>?

    /// `initialToken` is the list's local `share_token`. `record` writes the server's token onto the local row and kicks sync.
    public init(
        initialToken: String?, webBase: URL, share: @escaping @Sendable () async -> Result<String, ShareFailure>,
        stop: @escaping @Sendable () async -> Result<Void, ShareFailure>, record: @escaping @MainActor (String?) async -> Void,
        isOnline: @escaping @MainActor () -> Bool,
        copy: @escaping @MainActor (String) -> Void = { UIPasteboard.general.string = $0 },
        copiedWindow: Duration = .seconds(3)
    ) {
        self.state = ShareState(token: initialToken)
        self.webBase = webBase
        self.share = share
        self.stop = stop
        self.record = record
        self.isOnline = isOnline
        self.copy = copy
        self.copiedWindow = copiedWindow
    }

    /// The link to show and copy, while sharing is on.
    public var link: URL? { state.token.map { Share.link(webBase: webBase, token: $0) } }

    /// The switch and "Stop sharing" work: online and no call running.
    public var canChange: Bool { isOnline() && !state.isBusy }

    /// The switch was flipped.
    public func setOn(_ on: Bool) async {
        if on { await turnOn() } else { await turnOff() }
    }

    public func turnOn() async {
        guard canChange, !state.isOn else { return }
        state = state.begin(.turningOn)
        let result: Result<String, ShareFailure> = await share()
        // The link is built from the token, not the returned URL, which is the API's JSON route.
        guard case .success(let url) = result, let token = Share.token(fromURL: url) else {
            state = state.failed(.turnOn)
            return
        }
        state = state.turnedOn(token: token)
        await record(token)
    }

    public func turnOff() async {
        guard canChange, state.isOn else { return }
        state = state.begin(.stopping)
        let result: Result<Void, ShareFailure> = await stop()
        guard case .success = result else {
            state = state.failed(.stop)
            return
        }
        state = state.stopped()
        copiedTimer?.cancel()
        copied = false
        await record(nil)
    }

    /// Copies the link and shows "Link copied" for 3 s. Works offline: a known link stays copyable.
    public func copyLink() {
        guard let link else { return }
        copy(link.absoluteString)
        copied = true
        copiedTimer?.cancel()
        let window: Duration = copiedWindow
        copiedTimer = Task { [weak self] in
            try? await Task.sleep(for: window)
            guard !Task.isCancelled else { return }
            self?.copied = false
        }
    }
}
