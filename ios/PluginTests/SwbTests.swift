import XCTest
import CoreLocation
@testable import Plugin

class SwbTests: XCTestCase {

    func testEcho() {
        let implementation = Swb()
        let value = "Hello, World!"
        let result = implementation.echo(value)
        XCTAssertEqual(value, result)
    }

    func testCalculateStatus() {
        let implementation = Swb()

        let usable = implementation.calculateStatus(enabled: true, granted: true, isMock: false)
        XCTAssertEqual(usable["usable"] as? Bool, true)
        XCTAssertEqual(usable["enabled"] as? Bool, true)
        XCTAssertEqual(usable["granted"] as? Bool, true)
        XCTAssertEqual(usable["isMock"] as? Bool, false)

        let mockStatus = implementation.calculateStatus(enabled: true, granted: true, isMock: true)
        XCTAssertEqual(mockStatus["usable"] as? Bool, false)

        let disabledStatus = implementation.calculateStatus(enabled: false, granted: true, isMock: false)
        XCTAssertEqual(disabledStatus["usable"] as? Bool, false)

        let deniedStatus = implementation.calculateStatus(enabled: true, granted: false, isMock: false)
        XCTAssertEqual(deniedStatus["usable"] as? Bool, false)
    }
}
