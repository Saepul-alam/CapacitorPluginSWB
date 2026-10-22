import Foundation

@objc public class Swb: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
