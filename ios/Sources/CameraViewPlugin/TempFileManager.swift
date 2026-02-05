import Foundation
import UIKit

/// Manages temporary files created during camera capture operations.
/// Provides automatic cleanup to prevent disk space leaks.
///
/// This singleton tracks all temporary files created by the camera plugin
/// and provides cleanup at various lifecycle points:
/// - On session stop (cleans up session-specific files)
/// - On app activation (cleans up stale files older than 1 hour)
/// - On app termination (cleans up all tracked files)
public final class TempFileManager: @unchecked Sendable {
    /// Shared singleton instance
    public static let shared = TempFileManager()

    /// Serial queue for thread-safe file tracking
    private let queue = DispatchQueue(label: "com.michaelwolz.capacitorcameraview.tempFileManager")

    /// Set of tracked temporary file URLs
    private var trackedFiles = Set<URL>()

    /// Directory prefix used to identify camera capture temp files
    private let tempFilePrefix = "camera_capture_"

    /// Stale file threshold in seconds (1 hour)
    private let staleThresholdSeconds: TimeInterval = 3600

    private init() {
        setupAppLifecycleObservers()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Public API

    /// Creates a temporary file URL for storing captured images and tracks it for cleanup.
    ///
    /// - Returns: A URL pointing to the temporary file location
    /// - Throws: Never throws, always returns a valid temp directory URL
    public func createTempImageFile() -> URL {
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        let fileName = "\(tempFilePrefix)\(timestamp).jpg"
        let tempDir = FileManager.default.temporaryDirectory
        let fileURL = tempDir.appendingPathComponent(fileName)

        queue.sync {
            trackedFiles.insert(fileURL)
        }

        return fileURL
    }

    /// Registers an externally created file for tracking.
    ///
    /// - Parameter url: The URL of the file to track
    public func trackFile(_ url: URL) {
        queue.sync {
            trackedFiles.insert(url)
        }
    }

    /// Removes a file from tracking without deleting it.
    ///
    /// - Parameter url: The URL of the file to untrack
    public func untrackFile(_ url: URL) {
        queue.sync {
            trackedFiles.remove(url)
        }
    }

    /// Cleans up all tracked temporary files.
    /// Call this when the camera session stops.
    public func cleanupSessionFiles() {
        queue.async { [weak self] in
            guard let self = self else { return }

            let filesToDelete = self.trackedFiles
            self.trackedFiles.removeAll()

            for fileURL in filesToDelete {
                self.deleteFile(at: fileURL)
            }
        }
    }

    /// Cleans up stale temporary files that are older than the threshold.
    /// This helps recover from cases where cleanup was missed.
    public func cleanupStaleFiles() {
        queue.async { [weak self] in
            guard let self = self else { return }

            let fileManager = FileManager.default
            let tempDir = fileManager.temporaryDirectory

            guard let contents = try? fileManager.contentsOfDirectory(
                at: tempDir,
                includingPropertiesForKeys: [.creationDateKey],
                options: .skipsHiddenFiles
            ) else { return }

            let staleDate = Date().addingTimeInterval(-self.staleThresholdSeconds)

            for fileURL in contents {
                // Only process our camera capture files
                guard fileURL.lastPathComponent.hasPrefix(self.tempFilePrefix) else { continue }

                // Check file age
                if let attributes = try? fileManager.attributesOfItem(atPath: fileURL.path),
                   let creationDate = attributes[.creationDate] as? Date,
                   creationDate < staleDate {
                    self.deleteFile(at: fileURL)
                    self.trackedFiles.remove(fileURL)
                }
            }
        }
    }

    /// Cleans up all camera capture temporary files in the temp directory.
    /// Use this for aggressive cleanup on app termination.
    public func cleanupAllCaptureFiles() {
        queue.async { [weak self] in
            guard let self = self else { return }

            let fileManager = FileManager.default
            let tempDir = fileManager.temporaryDirectory

            guard let contents = try? fileManager.contentsOfDirectory(
                at: tempDir,
                includingPropertiesForKeys: nil,
                options: .skipsHiddenFiles
            ) else { return }

            for fileURL in contents {
                if fileURL.lastPathComponent.hasPrefix(self.tempFilePrefix) {
                    self.deleteFile(at: fileURL)
                }
            }

            self.trackedFiles.removeAll()
        }
    }

    // MARK: - Private Methods

    private func deleteFile(at url: URL) {
        do {
            try FileManager.default.removeItem(at: url)
        } catch {
            // Silently ignore - file may already be deleted or inaccessible
        }
    }

    private func setupAppLifecycleObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppWillTerminate),
            name: UIApplication.willTerminateNotification,
            object: nil
        )
    }

    @objc private func handleAppDidBecomeActive() {
        // Clean up any stale files when app becomes active
        cleanupStaleFiles()
    }

    @objc private func handleAppWillTerminate() {
        // Clean up all capture files on termination
        // Note: This is synchronous to ensure cleanup happens before termination
        queue.sync { [weak self] in
            guard let self = self else { return }

            let fileManager = FileManager.default
            let tempDir = fileManager.temporaryDirectory

            if let contents = try? fileManager.contentsOfDirectory(
                at: tempDir,
                includingPropertiesForKeys: nil,
                options: .skipsHiddenFiles
            ) {
                for fileURL in contents {
                    if fileURL.lastPathComponent.hasPrefix(self.tempFilePrefix) {
                        try? fileManager.removeItem(at: fileURL)
                    }
                }
            }

            self.trackedFiles.removeAll()
        }
    }
}
