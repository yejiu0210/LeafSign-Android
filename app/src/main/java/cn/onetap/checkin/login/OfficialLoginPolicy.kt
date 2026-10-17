package cn.onetap.checkin.login

import cn.onetap.checkin.core.Platform
import java.net.URI

/** Main-frame login navigation is an exact-host HTTPS allowlist, independent of UI state. */
internal object OfficialLoginPolicy {
    fun startUrl(platform: Platform): String = when (platform) {
        Platform.NETEASE -> "https://music.163.com/"
        Platform.TIEBA -> "https://tieba.baidu.com/"
        Platform.SKLAND -> "https://user.hypergryph.com/login"
        Platform.BILIBILI -> "https://passport.bilibili.com/login"
        Platform.HEYBOX -> "https://login.xiaoheihe.cn/?redirect_url=https%3A%2F%2Fwww.xiaoheihe.cn%2F&force_relogin=true"
    }
    private fun hosts(platform: Platform): Set<String> = when (platform) {
        Platform.NETEASE -> setOf("music.163.com", "y.music.163.com", "st.music.163.com", "reg.163.com", "dl.reg.163.com", "passport.163.com")
        Platform.TIEBA -> setOf("tieba.baidu.com", "wappass.baidu.com", "passport.baidu.com", "wappass.bdimg.com")
        Platform.SKLAND -> setOf("www.skland.com", "m.skland.com", "web-api.skland.com", "user.hypergryph.com", "as.hypergryph.com")
        Platform.BILIBILI -> setOf("passport.bilibili.com", "www.bilibili.com", "m.bilibili.com", "big.bilibili.com", "api.bilibili.com")
        Platform.HEYBOX -> setOf("xiaoheihe.cn", "www.xiaoheihe.cn", "login.xiaoheihe.cn", "api.xiaoheihe.cn")
    }
    private fun parse(url: String): URI? = try { URI(url) } catch (_: Exception) { null }
    private fun secure(uri: URI) = uri.scheme == "https" && uri.host != null &&
        (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null && !uri.rawAuthority.orEmpty().contains('@')
    fun secure(url: String): Boolean = parse(url)?.let(::secure) == true
    // NetEase's official shell initializes g_iframe with exactly about:blank before HTTPS content.
    fun allowedSubframe(url: String): Boolean = url == "about:blank" || secure(url)
    fun allowed(platform: Platform, url: String): Boolean = parse(url)?.let { secure(it) && it.host in hosts(platform) } == true
    fun isHypergryphAccount(url: String): Boolean = parse(url)?.let { secure(it) && it.host == "user.hypergryph.com" } == true
    fun isHeyboxLoginReturn(url: String): Boolean = parse(url)?.let {
        secure(it) && it.host in setOf("xiaoheihe.cn", "www.xiaoheihe.cn") && it.path in setOf("", "/", "/app/bbs/home", "/app/bbs/home/")
    } == true
    fun isSklandSensitivePage(url: String): Boolean = parse(url)?.let {
        secure(it) && it.host == "web-api.skland.com" && it.path.trimEnd('/') == "/account/info/hg"
    } == true
    fun isSklandToken(url: String): Boolean = parse(url)?.let {
        secure(it) && it.host == "web-api.skland.com" && it.rawPath == "/account/info/hg" && it.rawQuery == null && it.rawFragment == null
    } == true
}
