import Foundation

/// Why a share call failed. The sheet says the same thing for every cause, so the cause only matters to tests.
public enum ShareFailure: Error, Sendable, Equatable {
    case network
    case failed
}

/// What the C8 sheet shows when something goes wrong; the texts are the web twin's.
public enum ShareError: Equatable, Sendable {
    case turnOn
    case stop

    public var message: String {
        switch self {
        case .turnOn: "Could not turn on sharing. Try again."
        case .stop: "Could not stop sharing. Try again."
        }
    }
}

/// The pure parts of SHOP-13 and the sheet's state transitions. Web twin: `web/src/shopping/ShareSheet.ts`.
public enum Share {
    /// The page a link opens is the web app's `/shared/<token>`. The API's own link (`API_BASE_URL/shared/<token>`) is
    /// the JSON route, a different origin, so only its last path segment is taken, percent-decoded.
    public static func token(fromURL url: String) -> String? {
        guard let components = URLComponents(string: url) else { return nil }
        // Only an absolute path counts: newer Foundation encodes "not a url" into a relative path rather than failing.
        guard components.percentEncodedPath.hasPrefix("/") else { return nil }
        let segments: [String] = components.percentEncodedPath.split(separator: "/").map(String.init)
        guard let last = segments.last, let decoded = last.removingPercentEncoding, !decoded.isEmpty else { return nil }
        return decoded
    }

    /// `<web base>/shared/<token>`; the token is percent-encoded as one path segment.
    public static func link(webBase: URL, token: String) -> URL {
        let text: String = webBase.absoluteString
        let root: String = text.hasSuffix("/") ? String(text.dropLast()) : text
        let allowed: CharacterSet = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        let encoded: String = token.addingPercentEncoding(withAllowedCharacters: allowed) ?? token
        return URL(string: "\(root)/shared/\(encoded)") ?? webBase
    }
}

/// The sheet's state: off, on(link), busy or an error. `token` is what the switch shows; it only changes when a call
/// has succeeded, so a failure leaves the switch where it was.
public struct ShareState: Equatable, Sendable {
    public enum Busy: Equatable, Sendable {
        case turningOn
        case stopping
    }

    public var token: String?
    public var busy: Busy?
    public var error: ShareError?

    public init(token: String?, busy: Busy? = nil, error: ShareError? = nil) {
        self.token = token
        self.busy = busy
        self.error = error
    }

    /// The switch: on when there is a link.
    public var isOn: Bool { token != nil }
    public var isBusy: Bool { busy != nil }

    public func begin(_ busy: Busy) -> ShareState { ShareState(token: token, busy: busy, error: nil) }
    public func turnedOn(token: String) -> ShareState { ShareState(token: token) }
    public func stopped() -> ShareState { ShareState(token: nil) }
    public func failed(_ error: ShareError) -> ShareState { ShareState(token: token, busy: nil, error: error) }
}
