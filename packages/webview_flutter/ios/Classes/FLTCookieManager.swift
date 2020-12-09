import Flutter
import UIKit
import WebKit

@available(iOS 11.0, *)
public class FLTCookieManager: NSObject {
    let httpCookieStore: WKHTTPCookieStore

    @objc public init(cookieStore: WKHTTPCookieStore) {
        httpCookieStore = cookieStore
    }

    @objc public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "getCookies":
            let arguments = call.arguments as! NSDictionary
            let url = arguments["url"] as? String
            getCookies(urlString: url, result: result)
        case "setCookies":
            let cookies = call.arguments as! [NSDictionary]
            setCookies(cookies: cookies, result: result)
        case "hasCookies":
            hasCookies(result: result)
        case "clearCookies":
            clearCookies(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    @objc public func setCookies(cookies: [NSDictionary], result: @escaping FlutterResult) {
        // sets the cookie at the index in the cookies list
        func setCookieAt(index: Int = 0) {
            if index >= cookies.count {
                result(true)
                return
            }
            // set the cookie, and on completion set the next cookie
            _setCookie(cookie: cookies[index], done: { () in
                setCookieAt(index: index + 1)
            })
        }

        setCookieAt()
    }

    @objc public func clearCookies(result: @escaping FlutterResult) {
        // delete HTTPCookieStorage all cookies
        if let cookies = HTTPCookieStorage.shared.cookies {
            for cookie in cookies {
                HTTPCookieStorage.shared.deleteCookie(cookie)
            }
        }

        httpCookieStore.getAllCookies { cookies in
            // sets the cookie at the index in the cookies list
            func deleteCookieAt(index: Int = 0) {
                if index >= cookies.count {
                    result(nil)
                    return
                }
                self.httpCookieStore.delete(cookies[index], completionHandler: { () in
                    deleteCookieAt(index: index + 1)
                })
            }

            deleteCookieAt()
        }
    }

    @objc public func hasCookies(result: @escaping FlutterResult) {
        httpCookieStore.getAllCookies { cookies in
            var isEmpty = cookies.isEmpty
            if isEmpty {
                // If it is empty, check whether the HTTPCookieStorage cookie is also empty.
                isEmpty = HTTPCookieStorage.shared.cookies?.isEmpty ?? true
            }
            result(!isEmpty)
        }
    }

    private func _setCookie(cookie: NSDictionary, done: @escaping () -> Void) {
        let expiresDate = cookie["expires"] as? Double
        let isSecure = cookie["secure"] as? Bool
        let isHttpOnly = cookie["httpOnly"] as? Bool

        var properties: [HTTPCookiePropertyKey: Any] = [:]
        properties[.name] = cookie["name"] as! String
        properties[.value] = cookie["value"] as! String
        properties[.domain] = cookie["domain"] as! String
        properties[.path] = cookie["path"] as? String ?? "/"

        if let expires = expiresDate {
            properties[.expires] = Date(timeIntervalSince1970: expires)
        }
        if isSecure ?? false {
            properties[.secure] = "TRUE"
        }
        if isHttpOnly ?? false {
            properties[.init("HttpOnly")] = "YES"
        }

        let cookie = HTTPCookie(properties: properties)!

        httpCookieStore.setCookie(cookie, completionHandler: { () in
            done()
        })
    }

    @objc public func getCookies(urlString: String?, result: @escaping FlutterResult) {
        // map empty string and nil to "", indicating that no filter should be applied
        let url = urlString.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) } ?? ""

        // ensure passed in url is parseable, and extract the host
        let host = URL(string: url)?.host

        // fetch and filter cookies from WKHTTPCookieStore
        httpCookieStore.getAllCookies { wkCookies in

            func matches(cookie: HTTPCookie) -> Bool {
                // nil host means unparseable url or empty string
                let containsHost = host.map { cookie.domain.contains($0) } ?? false
                return url == "" || containsHost
            }

            var cookies = wkCookies.filter { matches(cookie: $0) }

            // If the cookie value is empty in WKHTTPCookieStore,
            // get the cookie value from HTTPCookieStorage
            if cookies.count == 0 {
                if let httpCookies = HTTPCookieStorage.shared.cookies {
                    cookies = httpCookies.filter { matches(cookie: $0) }
                }
            }
            let cookieList = NSMutableArray()
            cookies.forEach { cookie in
                cookieList.add(FLTCookieManager._cookieToDictionary(cookie: cookie))
            }
            result(cookieList)
        }
    }

    static func _cookieToDictionary(cookie: HTTPCookie) -> NSDictionary {
        let result = NSMutableDictionary()

        result.setValue(cookie.name, forKey: "name")
        result.setValue(cookie.value, forKey: "value")
        result.setValue(cookie.domain, forKey: "domain")
        result.setValue(cookie.path, forKey: "path")
        result.setValue(cookie.isSecure, forKey: "secure")
        result.setValue(cookie.isHTTPOnly, forKey: "httpOnly")

        if cookie.expiresDate != nil {
            let expiredDate = cookie.expiresDate?.timeIntervalSince1970
            result.setValue(Int(expiredDate!), forKey: "expires")
        }

        return result
    }
}
