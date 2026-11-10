import Network
import Observation

/// Whether the device has a usable network path. Tests construct it with a fixed `isOnline`.
@Observable
@MainActor
public final class NetworkMonitor {
    public private(set) var isOnline: Bool
    @ObservationIgnored private var monitor: NWPathMonitor?

    /// Fixed value, no path monitoring (tests and previews).
    public init(isOnline: Bool) {
        self.isOnline = isOnline
    }

    /// Follows `NWPathMonitor` for the app's lifetime.
    public static func live() -> NetworkMonitor {
        let model = NetworkMonitor(isOnline: true)
        let monitor = NWPathMonitor()
        model.monitor = monitor
        monitor.pathUpdateHandler = { [weak model] path in
            let online = path.status == .satisfied
            Task { @MainActor in model?.isOnline = online }
        }
        monitor.start(queue: DispatchQueue(label: "com.abodenestly.network"))
        return model
    }

    /// Lets tests flip connectivity on a fixed monitor.
    public func setOnline(_ online: Bool) {
        isOnline = online
    }
}
