import BackgroundTasks
import Foundation

/// SPEC §6.4: the iOS background refresh that lets the outbox drain while the app is not running.
public enum BackgroundSync {
    /// Must match `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
    public static let taskIdentifier = "com.abodenestly.sync"
    /// The system decides the real time; this is the earliest it may run.
    public static let earliestInterval: TimeInterval = 15 * 60
}

/// The one call the app makes to schedule a refresh, behind a protocol so a test sees the request without BGTaskScheduler.
public protocol BackgroundSyncScheduler: Sendable {
    func schedule(identifier: String, earliestBegin: Date)
}

public struct SystemBackgroundSyncScheduler: BackgroundSyncScheduler {
    public init() {}

    public func schedule(identifier: String, earliestBegin: Date) {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = earliestBegin
        // Refused in the simulator and when Background App Refresh is off; the foreground triggers still run.
        try? BGTaskScheduler.shared.submit(request)
    }
}

extension BackgroundSyncScheduler {
    /// Asks for the next refresh at least 15 minutes from `now`.
    public func scheduleNext(now: Date = Date()) {
        schedule(identifier: BackgroundSync.taskIdentifier, earliestBegin: now.addingTimeInterval(BackgroundSync.earliestInterval))
    }
}
