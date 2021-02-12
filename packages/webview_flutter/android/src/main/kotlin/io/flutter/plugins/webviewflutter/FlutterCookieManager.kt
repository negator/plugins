package io.flutter.plugins.webviewflutter

import android.net.*
import android.webkit.*
import io.flutter.plugin.common.MethodChannel.Result
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie as okCookie

class Cookie(val delegate: okCookie) {
    fun matches(url: HttpUrl): Boolean {
        return delegate.matches(url)
    }

    fun expired(): Boolean {
        return delegate.expiresAt() < System.currentTimeMillis()
    }

    override fun equals(other: Any?): Boolean {
        if (other is Cookie) {
            return other.delegate.name() == delegate.name() &&
                other.delegate.domain() == delegate.domain() &&
                other.delegate.path() == delegate.path() &&
                other.delegate.secure() == delegate.secure() &&
                other.delegate.hostOnly() == delegate.hostOnly()
        } else {
            return false
        }
    }
    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + delegate.name().hashCode()
        result = 31 * result + delegate.domain().hashCode()
        result = 31 * result + delegate.path().hashCode()
        result = 31 * result + delegate.secure().hashCode()
        result = 31 * result + delegate.hostOnly().hashCode()
        return result
    }
}

internal object FlutterCookieManager : CookieJar {

    val cookies = ConcurrentHashMap.newKeySet<Cookie>()

    var cookieManager: CookieManager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, toUpdate: List<okCookie>) {
        toUpdate.forEach({ okc ->
            val cookie = Cookie(okc)
            synchronized(cookies) {
                cookies.remove(cookie)
                cookies.add(cookie)
            }
        })
    }

    override fun loadForRequest(url: HttpUrl): List<okCookie> {
        val toRemove = mutableSetOf<Cookie>()
        val toReturn = mutableSetOf<okCookie>()
        cookies.forEach({ cookie ->
            when {
                cookie.expired() -> toRemove.add(cookie)
                cookie.matches(url) -> toReturn.add(cookie.delegate)
            }
        })
        cookies.removeAll(toRemove)
        return toReturn.toList()
    }

    internal fun getCookies(url: String?, result: Result) {

        val serialized = when (val httpurl = HttpUrl.parse(url ?: "")) {
            null -> cookies.map({ cookieToMap(it) }).toList()
            else -> loadForRequest(httpurl).map({ cookieToMap(Cookie(it)) }).toList()
        }

        result.success(serialized)
    }

    internal fun setCookies(cookiesMap: List<Map<String, Any>>, result: Result) {
        val cookiesToSet = cookiesMap.map { cookieFromMap(it) }.filterNot { it.delegate.domain().isEmpty() }
        cookiesToSet.forEach { cookie ->
            synchronized(cookies) {
                cookies.remove(cookie)
                cookies.add(cookie)
            }
        }
        result.success(true)
    }

    internal fun clearCookies(result: Result) {
        cookies.clear()
        result.success(true)
    }

    private fun cookieFromMap(map: Map<String, Any>): Cookie {
        var builder = okCookie.Builder()
            .domain(map["domain"]?.toString() ?: "")
            .name(map["name"]?.toString() ?: "")
            .value(map["value"]?.toString() ?: "")
            .path(map["path"]?.toString() ?: "")
        
        if (map["secure"]?.toString()?.toBoolean() ?: false) {
            builder = builder.secure()
        }
        if (map["httpOnly"]?.toString()?.toBoolean() ?: false) {
            builder = builder.httpOnly()
        }
        map["expires"]?.toString()?.toLong()?.let{ exp ->
            builder = builder.expiresAt(exp)
        }

        return Cookie(builder.build())
    }
    private fun cookieToMap(cookie: Cookie): Map<String, Any> {
        val resultMap = hashMapOf<String, Any>()
        resultMap.put("name", cookie.delegate.name())
        resultMap.put("value", cookie.delegate.value())
        resultMap.put("path", cookie.delegate.path())
        resultMap.put("domain", cookie.delegate.domain())
        resultMap.put("secure", cookie.delegate.secure())
        if (!cookie.expired()) {
            resultMap.put("expires", cookie.delegate.expiresAt())
        }
        resultMap.put("httpOnly", cookie.delegate.httpOnly())
        resultMap.put("hostOnly", cookie.delegate.hostOnly())

        return resultMap.toMap()
    }
}
