package io.flutter.plugins.webviewflutter

internal enum class InjectionTime {
    DocumentStart, DocumentEnd;

    companion object {
        fun fromString(str: String?): InjectionTime {
            return if (str != null && str.trim().toLowerCase() === "end") {
                DocumentEnd
            } else {
                DocumentStart
            }
        }
    }
}

internal class UserScript constructor (val source: String, injectionTime: InjectionTime?, mainFrame: Boolean?) {
    val injectionTime: InjectionTime
    val mainFrameOnly: Boolean

    constructor(paramMap: Map<String, Any>) : this(
        paramMap["source"].toString(),
        InjectionTime.fromString(paramMap["injectionTime"] as String?),
        paramMap["mainFrameOnly"] as Boolean?
    ) {
    }

    init {
        this.injectionTime = injectionTime ?: InjectionTime.DocumentStart
        mainFrameOnly = mainFrame ?: false
    }
}
