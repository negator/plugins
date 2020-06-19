package io.flutter.plugins.webviewflutter;

import java.util.Map;

enum InjectionTime {
    DocumentStart, DocumentEnd;

    public static InjectionTime fromString(String str) {
        if (str != null && str.trim().toLowerCase() == "end") {
            return DocumentEnd;
        } else {
            return DocumentStart;
        }
    }
}

class UserScript {
    final String source;
    final InjectionTime injectionTime;
    final Boolean mainFrameOnly;

    UserScript(String src, InjectionTime injectionTime, Boolean mainFrame) {
        this.source = src;
        this.injectionTime = injectionTime != null ? injectionTime : InjectionTime.DocumentStart;
        this.mainFrameOnly = mainFrame != null ? mainFrame : false;            
    }

    UserScript(Map<String, Object> paramMap) {
        this(paramMap.get("source").toString(),
                InjectionTime.fromString((String) paramMap.get("injectionTime")),
                (Boolean) paramMap.get("mainFrameOnly"));
    }
}