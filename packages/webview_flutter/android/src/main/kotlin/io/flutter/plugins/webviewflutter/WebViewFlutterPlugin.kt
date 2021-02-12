
package io.flutter.plugins.webviewflutter

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.PluginRegistry.Registrar

/**
 * Kotlin platform implementation of the webview_flutter plugin.
 *
 *
 * Register this in an add to app scenario to gracefully handle activity and context changes.
 *
 *
 * Call [.registerWith] to use the stable `io.flutter.plugin.common`
 * package instead.
 */
class WebViewFlutterPlugin
/**
 * Add an instance of this to [io.flutter.embedding.engine.plugins.PluginRegistry] to
 * register it.
 *
 *
 * THIS PLUGIN CODE PATH DEPENDS ON A NEWER VERSION OF FLUTTER THAN THE ONE DEFINED IN THE
 * PUBSPEC.YAML. Text input will fail on some Android devices unless this is used with at least
 * flutter/flutter@1d4d63ace1f801a022ea9ec737bf8c15395588b9. Use the V1 embedding with [ ][.registerWith] to use this plugin with older Flutter versions.
 *
 *
 * Registration should eventually be handled automatically by v2 of the
 * GeneratedPluginRegistrant. https://github.com/flutter/flutter/issues/42694
 */
    : FlutterPlugin {

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val messenger = binding.getBinaryMessenger()
        binding
            .getFlutterEngine()
            .getPlatformViewsController()
            .getRegistry()
            .registerViewFactory(
                "plugins.flutter.io/webview", WebViewFactory(messenger)
            )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    }

    companion object {
        /**
         * Registers a plugin implementation that uses the stable `io.flutter.plugin.common`
         * package.
         *
         *
         * Calling this automatically initializes the plugin. However plugins initialized this way
         * won't react to changes in activity or context, unlike [CameraPlugin].
         */
        fun registerWith(registrar: Registrar) {
            registrar
                .platformViewRegistry()
                .registerViewFactory(
                    "plugins.flutter.io/webview",
                    WebViewFactory(registrar.messenger())
                )
        }
    }
}
