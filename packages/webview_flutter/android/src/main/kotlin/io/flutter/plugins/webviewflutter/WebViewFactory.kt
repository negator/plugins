// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.webviewflutter

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class WebViewFactory internal constructor(messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    private val messenger: BinaryMessenger

    init {
        this.messenger = messenger
    }

    @SuppressWarnings("unchecked")

    override fun create(context: Context, id: Int, args: Any): PlatformView {
        return FlutterWebView(context, messenger, id, args as Map<String, Any>)
    }
}
