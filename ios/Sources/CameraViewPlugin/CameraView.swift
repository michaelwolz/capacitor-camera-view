import Foundation

@objc public class CameraView: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
