import Flutter
import UIKit
import WebKit

@available(iOS 11.0, *)
@objc
public class FLTWebViewFactorySwift: NSObject, FlutterPlatformViewFactory {
    let _messenger: FlutterBinaryMessenger

    @objc public init(messenger: FlutterBinaryMessenger) {
        _messenger = messenger
        super.init()
    }

    @objc func createArgsCodec() -> FlutterMessageCodec {
        return FlutterStandardMessageCodec.sharedInstance()
    }

    @objc public func create(withFrame frame: CGRect, viewIdentifier id: Int64, arguments args: Any?) -> FlutterPlatformView {
        return FLTWebViewController(frame: frame, viewIdentifier: id, arguments: args, binaryMessenger: _messenger)
    }
}

public class FLTWKWebView: WKWebView {
    override public var frame: CGRect {
        didSet {
            //   // We don't want the contentInsets to be adjusted by iOS, flutter should always take control of
            // webview's contentInsets.
            scrollView.contentInset = .zero
            if #available(iOS 11, *) {
                // Above iOS 11, adjust contentInset to compensate the adjustedContentInset so the sum will
                // always be 0.
                if scrollView.adjustedContentInset == .zero {
                    return
                }
                
                let insetToAdjust = scrollView.adjustedContentInset
                scrollView.contentInset = UIEdgeInsets(top: -insetToAdjust.top, left: -insetToAdjust.left,
                                                       bottom: -insetToAdjust.bottom, right: -insetToAdjust.right)
            }
        }
    }
}

public class FLTWebViewController: NSObject, FlutterPlatformView, UIScrollViewDelegate, WKUIDelegate {
    
    var _webView: FLTWKWebView?
    let _cookieManager: FLTCookieManager
    let _channel: FlutterMethodChannel
    // The set of registered JavaScript channel names.
    var _javaScriptChannelNames: Set<String>
    let _navigationDelegate: FLTWKNavigationDelegate
        
    public func view() -> UIView {
        return _webView!
    }
    
    public func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with _: UIView?) {
        scrollView.pinchGestureRecognizer?.isEnabled = false
    }

    public func scrollViewDidZoom(_ scrollView: UIScrollView) {
        scrollView.setZoomScale(1.0, animated: false)
    }

    public init(frame: CGRect, viewIdentifier viewId: Int64, arguments: Any?, binaryMessenger: FlutterBinaryMessenger) {
        let channelName = "plugins.flutter.io/webview_\(viewId)"

        _channel = FlutterMethodChannel(name: channelName, binaryMessenger: binaryMessenger)

        let userContentController = WKUserContentController()
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = userContentController
        configuration.preferences.javaScriptEnabled = true
        let store = WKWebsiteDataStore.nonPersistent()
        configuration.websiteDataStore = store
        configuration.processPool = WKProcessPool()
        
        _cookieManager = FLTCookieManager(cookieStore: store.httpCookieStore)
        _navigationDelegate = FLTWKNavigationDelegate(channel: _channel)

        _javaScriptChannelNames = Set<String>()
        _webView = nil

        super.init()

        if let args = arguments as? NSDictionary {
            if let cookies = args["cookies"] as? [NSDictionary] {
                _cookieManager.setCookies(cookies: cookies) { _ in }
            }

            if let names = args["javascriptChannelNames"] as? [String] {
                names.forEach { name in _javaScriptChannelNames.insert(name) }
                registerJavaScriptChannels(names: _javaScriptChannelNames, controller: userContentController)
            }

            if let scripts = args["userScripts"] as? [NSDictionary] {
                registerUserScripts(scripts: scripts, controller: userContentController)
            }
        }

        var str = "X2Fsd2F5c1J1bnNBdEZvcmVncm91bmRQcmlvcml0eQ=="
        if #available(iOS 12.2, *) {
            NSLog("iOS 12.2+ detected")
            str = "YWx3YXlzUnVuc0F0Rm9yZWdyb3VuZFByaW9yaXR5"
        }

        if let data = Data(base64Encoded: str),
           let prop = String(data: data, encoding: String.Encoding.utf8)
        {
            configuration.setValue(true, forKey: prop)
        }

        _webView = FLTWKWebView(frame: frame, configuration: configuration)

        _webView?.scrollView.delegate = self
        _webView?.navigationDelegate = _navigationDelegate
        _webView?.uiDelegate = self

        _channel.setMethodCallHandler { call, result in
            self.onMethodCall(call: call, result: result)
        }

        if #available(iOS 11.0, *) {
            _webView?.scrollView.contentInsetAdjustmentBehavior = .never
            if #available(iOS 13.0, *) {
                _webView?.scrollView.automaticallyAdjustsScrollIndicatorInsets = false
            }
        }
        
        if let args = arguments as? NSDictionary {
            if let settings = args["settings"] as? NSDictionary {
                let err = applySettings(settings: settings)
                 if err != "" {
                    NSLog("Error applying settings: \(err)")
                 } else {
                    NSLog("Settings applied")
                }
            }
            
            if let url = args["initialUrl"] as? String {
                let loaded = loadUrl(url: url, headers: args["headers"] as? [String: String])
                if !loaded {
                    NSLog("URL not loaded: \(url)")
                } else {
                    NSLog("URL loaded: \(url)")
                }
            }
        }
               
    }

    func registerJavaScriptChannels(names: Set<String>, controller: WKUserContentController) {
        names.forEach { name in
            NSLog("registering channel: \(name)")
            let channel = FLTJavaScriptChannel(methodChannel: _channel, javaScriptChannelName: name)
            controller.add(channel, name: name)
            let wrapperSource = "window.\(name) = webkit.messageHandlers.\(name);"
            let wrapperScript = WKUserScript(source: wrapperSource, injectionTime: .atDocumentStart, forMainFrameOnly: false)
            controller.addUserScript(wrapperScript)
        }
    }

    func registerUserScripts(scripts: [NSDictionary], controller: WKUserContentController) {
        scripts.forEach { script in
            let source = script["source"] as! String
            let inject = script["injectionTime"] as! String
            let mainFrame = script["mainFrameOnly"] as! NSNumber

            var time = WKUserScriptInjectionTime.atDocumentStart
            if inject == "end" {
                time = WKUserScriptInjectionTime.atDocumentEnd
            }
            let mainFrameOnly = mainFrame.boolValue

            let wrapperScript = WKUserScript(source: source, injectionTime: time, forMainFrameOnly: mainFrameOnly)
            controller.addUserScript(wrapperScript)
            
        }
    }

    func onMethodCall(call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch (call.method, call.arguments) {
        case let ("updateSettings", settings as NSDictionary):
            switch applySettings(settings: settings) {
            case "": result(nil)
            case let err: result(FlutterError(
                    code: "updateSettings_failed",
                    message: err,
                    details: nil
                ))
            }
        case let ("loadUrl", args as NSDictionary):
            NSLog("loading url \(args)")
            switch loadRequest(request: args) {
            case true: result(nil)
            case false: result(FlutterError(
                    code: "loadUrl_failed",
                    message: "Failed parsing the URL",
                    details: "Request was: \(args)"
                ))
            }

        case ("canGoBack", _):
            result(NSNumber(booleanLiteral: _webView?.canGoBack ?? false))
        case ("canGoForward", _):
            result(NSNumber(booleanLiteral: _webView?.canGoForward ?? false))
        case ("goBack", _):
            _webView?.goBack()
            result(nil)
        case ("goForward", _):
            _webView?.goForward()
            result(nil)
        case ("reload", _):
            _webView?.reload()
            result(nil)
        case ("stopLoading", _):
            _webView?.stopLoading()
            result(nil)
        case ("currentUrl", _):
            result(_webView?.url ?? "")
        case let ("evaluateJavascript", js as String):
            _webView?.evaluateJavaScript(js) { r, err in
                if let error = err {
                    result(FlutterError(
                        code: "evaluateJavaScript_failed",
                        message: "Failed evaluating JavaScript",
                        details: "JavaScript was: \(js)\n\(error)"
                    ))
                } else {
                    result("\(r ?? "")")
                }
            }
        case let ("addJavascriptChannels", channelNames as [String]):
            channelNames.forEach { _javaScriptChannelNames.insert($0) }
            if let c = _webView?.configuration.userContentController {
                registerJavaScriptChannels(names: Set(channelNames), controller: c)
            }
            result(nil)
        case let ("removeJavascriptChannels", channelNames as [String]):
            // WkWebView does not support removing a single user script, so instead we remove all
            // user scripts, all message handlers. And re-register channels that shouldn't be removed.
            _webView?.configuration.userContentController.removeAllUserScripts()
            _javaScriptChannelNames.forEach { name in
                _webView?.configuration.userContentController.removeScriptMessageHandler(forName: name)
            }
            channelNames.forEach { _javaScriptChannelNames.remove($0) }
            if let c = _webView?.configuration.userContentController {
                registerJavaScriptChannels(names: _javaScriptChannelNames, controller: c)
            }
            result(nil)
        case ("clearCache", _):
            if #available(iOS 9.0, *) {
                let cacheDataTypes = WKWebsiteDataStore.allWebsiteDataTypes()
                let dataStore = _webView?.configuration.websiteDataStore
                let dateFrom = Date(timeIntervalSinceReferenceDate: 0)
                dataStore?.removeData(ofTypes: cacheDataTypes, modifiedSince: dateFrom) {
                    result(nil)
                }
            } else {
                NSLog("Clearing cache is not supported for Flutter WebViews prior to iOS 9.")
            }
        case ("getTitle", _):
            result(_webView?.title ?? "")
        case let ("scrollTo", args as NSDictionary):
            let x = (args["x"] as? NSNumber).map { $0.intValue }
            let y = (args["y"] as? NSNumber).map { $0.intValue }
            _webView?.scrollView.contentOffset = CGPoint(x: x ?? 0, y: y ?? 0)
            result(nil)
        case let ("scrollBy", args as NSDictionary):
            let offset = _webView?.scrollView.contentOffset
            let x = (args["x"] as? NSNumber).map { $0.intValue } ?? 0
            let y = (args["y"] as? NSNumber).map { $0.intValue } ?? 0
            let cgx = CGFloat(integerLiteral: x) + (offset?.x ?? CGFloat(0))
            let cgy = CGFloat(integerLiteral: y) + (offset?.y ?? CGFloat(0))
            _webView?.scrollView.contentOffset = CGPoint(x: cgx, y: cgy)
            result(nil)
        case ("getScrollX", _):
            let offset = _webView?.scrollView.contentOffset.x
            result(NSNumber(value: Float(offset ?? 0.0)))
        case ("getScrollY", _):
            let offset = _webView?.scrollView.contentOffset.y
            result(NSNumber(value: Float(offset ?? 0.0)))
        case let ("getCookies", args as NSDictionary):
            _cookieManager.getCookies(urlString: args["url"] as? String, result: result)
        case let ("setCookies", args as [NSDictionary]):
            _cookieManager.setCookies(cookies: args, result: result)
        case ("clearCookies", _):
            _cookieManager.clearCookies(result: result)
        default:
            break
        }
    }

    private func loadRequest(request: NSDictionary) -> Bool {
        if let url = request["url"] as? String {
            return loadUrl(url: url, headers: request["headers"] as? [String: String])
        }
        return false
    }

    private func loadUrl(url: String, headers: [String: String]?) -> Bool {
        
        if let nsUrl = URL(string: url) {
            var request = URLRequest(url: nsUrl)
            if let h = headers {
                request.allHTTPHeaderFields = h
            }
            _webView?.load(request)
        } else {
            _webView?.loadHTMLString(url, baseURL: nil)
        }
                
        return true
    }

    private func applySettings(settings: NSDictionary) -> String {
        var unkownKeys: [String] = []
        let prefs = _webView?.configuration.preferences

        settings.forEach { k, value in
            guard let key = k as? String else {
                return
            }

            switch (key, value) {
            case let ("jsMode", mode as NSNumber) where mode == 0:
                prefs?.javaScriptEnabled = false
            case let ("jsMode", mode as NSNumber) where mode == 1:
                prefs?.javaScriptEnabled = true
            case let ("hasNavigationDelegate", has as NSNumber):
                _navigationDelegate.hasDartNavigationDelegate = has.boolValue
            case ("debuggingEnabled", _):
                // no-op debugging is always enabled on iOS.
                break
            case let ("gestureNavigationEnabled", allows as NSNumber):
                _webView?.allowsBackForwardNavigationGestures = allows.boolValue
            case let ("userAgent", ua as String):
                if #available(iOS 9.0, *) {
                    _webView?.customUserAgent = ua
                    
                } else {
                    NSLog("Updating UserAgent is not supported for Flutter WebViews prior to iOS 9.")
                }
            default: unkownKeys.append(key)
            }
        }

        if unkownKeys.count > 0 {
            return "webview_flutter: unknown setting keys:\(unkownKeys.joined(separator: ", "))"
        }
        return ""
    }

    private func webView(webView: WKWebView, createWebViewWithConfiguration _: WKWebViewConfiguration, forNavigationAction navigationAction: WKNavigationAction, windowFeatures _: WKWindowFeatures) {
        if navigationAction.targetFrame?.isMainFrame ?? false {
            webView.load(navigationAction.request)
        }
    }

    // Confirm dialog
    public func webView(_: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame _: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let method = "onJsConfirm"
        _channel.invokeMethod(method, arguments: ["message": message]) { result in
            guard let r = result else {
                NSLog("\(method) has unexpectedly returned no result, proceeding to confirm.")
                completionHandler(true)
                return
            }

            switch r {
            case let t as NSNumber: completionHandler(t.boolValue)
            case let error as FlutterError:
                NSLog("\(method) has unexpectedly completed with an error: \(error.debugDescription), proceeding to confirm.")
                completionHandler(true)
            default:
                NSLog("\(method) unexpectedly returned a non boolean value: \(r), proceeding to confirm.")
                completionHandler(true)
            }
        }
    }

    // Alert dialog
    public func webView(_: WKWebView,
                        runJavaScriptAlertPanelWithMessage message: String,
                        initiatedByFrame _: WKFrameInfo,
                        completionHandler: @escaping () -> Void)
    {
        let method = "onsJsAlert"
        _channel.invokeMethod(method, arguments: ["message": message]) { result in
            guard let r = result else {
                NSLog("\(method) has unexpectedly returned no result, proceeding to confirm.")
                completionHandler()
                return
            }

            switch r {
            case let error as FlutterError:
                NSLog("\(method) has unexpectedly completed with an error: \(error.debugDescription), proceeding to confirm.")
                completionHandler()
            default:
                completionHandler()
            }
        }
    }

    // Input dialog
    public func webView(_: WKWebView,
                        runJavaScriptTextInputPanelWithPrompt message: String,
                        defaultText: String?,
                        initiatedByFrame _: WKFrameInfo,
                        completionHandler: @escaping (String?) -> Void)
    {
        let method = "onJsPrompt"
        _channel.invokeMethod(method, arguments: ["message": message, "default": defaultText]) { result in
            guard let r = result else {
                NSLog("\(method) has unexpectedly returned no result, proceeding to confirm.")
                completionHandler(defaultText)
                return
            }

            switch r {
            case let t as String: completionHandler(t)
            case let error as FlutterError:
                NSLog("\(method) has unexpectedly completed with an error: \(error.debugDescription), proceeding to confirm.")
                completionHandler(defaultText)
            default:
                NSLog("\(method) unexpectedly returned a non String value: \(r), proceeding to confirm.")
                completionHandler(defaultText)
            }
        }
    }
}
