package cn.onetap.checkin.login

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A fresh official website session belonging to this app, never another app's private files. */
class WebLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_TICKET = "ticket"
        private const val SK_TOKEN_URL = "https://web-api.skland.com/account/info/hg"
    }

    private lateinit var platform: Platform
    private var page by mutableStateOf<WebView?>(null)
    private var status by mutableStateOf("正在清理上次登录会话…")
    private var host by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var checking by mutableStateOf(false)
    private var hidePage by mutableStateOf(false)
    private var closing by mutableStateOf(false)
    private var cleaned = false
    private var autoChecks = 0
    private var lastAutoCredential = ""

    private val startUrl: String get() = when (platform) {
        Platform.NETEASE -> "https://music.163.com/"
        Platform.TIEBA -> "https://tieba.baidu.com/"
        Platform.SKLAND -> "https://www.skland.com/"
        Platform.BILIBILI -> "https://passport.bilibili.com/login"
    }
    private val navigationHosts: Set<String> get() = when (platform) {
        Platform.NETEASE -> setOf("music.163.com", "st.music.163.com", "reg.163.com", "dl.reg.163.com", "passport.163.com")
        Platform.TIEBA -> setOf("tieba.baidu.com", "wappass.baidu.com", "passport.baidu.com", "wappass.bdimg.com")
        Platform.SKLAND -> setOf("www.skland.com", "web-api.skland.com", "user.hypergryph.com", "as.hypergryph.com")
        Platform.BILIBILI -> setOf("passport.bilibili.com", "www.bilibili.com", "m.bilibili.com", "big.bilibili.com", "api.bilibili.com")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = Platform.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PLATFORM) }
            ?: run { finish(); return }
        setResult(RESULT_CANCELED)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeSession(null) }
        })
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton(onClick = { closeSession(null) }, enabled = !closing) { Text("返回") }
                            Text("${platform.title} · 官方网页登录", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        Text("登录与验证码由官方网页处理。只导入本次登录，不读取手机其他 App。", Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.bodySmall)
                        if (host.isNotBlank()) Text("官方域名：$host", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        Text(status, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        if (loading || checking || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton(onClick = { detect(manual = true) }, enabled = page != null && !checking && !closing) { Text("登录完成，检测账号") }
                            TextButton(onClick = { returnToLogin() }, enabled = page != null && !checking && !closing) { Text("返回官网登录") }
                        }
                        val browser = page
                        if (browser != null && !hidePage) AndroidView(factory = { browser }, modifier = Modifier.fillMaxWidth().weight(1f))
                        else Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) { Text(if (hidePage) "正在读取本次登录授权并校验身份，请稍候…" else "正在准备独立登录会话…") }
                    }
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(false)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            if (!isFinishing && !isDestroyed && !closing) {
                createBrowser()
                lifecycleScope.launch {
                    while (!closing) { delay(2_000); detect(manual = false) }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createBrowser() {
        val browser = WebView(this)
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSavePassword(false)
            saveFormData = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) { loading = progress < 100 }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = if (request.isForMainFrame) allowedNavigation(request.url) else secureUrl(request.url)
                if (!allowed && request.isForMainFrame) status = "官网请求打开其他地址，已停止跳转。请在此页完成网页登录。"
                return !allowed
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                if (if (request.isForMainFrame) allowedNavigation(request.url) else secureUrl(request.url)) null
                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                val uri = url?.let(Uri::parse)
                if (uri == null || !allowedNavigation(uri)) { view.stopLoading(); status = "已停止非官方页面跳转"; return }
                host = uri.host.orEmpty()
                if (isSklandAuthorization(uri)) { hidePage = true; view.visibility = android.view.View.INVISIBLE }
                loading = true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                // Canceled navigation can deliver an old callback after the token URL is active.
                if (url == null || url != view.url || closing) return
                loading = false
                if (url?.let(Uri::parse)?.let(::isSklandAuthorization) == true) {
                    if (hidePage && checking) readSklandAuthorization(view)
                } else if (url?.let(Uri::parse)?.let(::allowedNavigation) == true) {
                    hidePage = false
                    view.visibility = android.view.View.VISIBLE
                    detect(manual = false)
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
                loading = false
                checking = false
                status = "官网安全连接验证失败，请检查手机时间和网络后重试"
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    loading = false
                    checking = false
                    status = "官方页面加载失败，请检查网络后点击返回官网登录"
                }
            }
        }
        page = browser
        status = "请在下方官网完成登录；检测到授权后会自动校验账号。"
        browser.loadUrl(startUrl)
    }

    private fun secureUrl(uri: Uri): Boolean = uri.scheme == "https" && uri.host != null &&
        (uri.port == -1 || uri.port == 443) && !uri.encodedAuthority.orEmpty().contains('@')

    private fun allowedNavigation(uri: Uri): Boolean = secureUrl(uri) && uri.host in navigationHosts

    private fun isSklandAuthorization(uri: Uri): Boolean = platform == Platform.SKLAND &&
        uri.host == "web-api.skland.com" && uri.path == "/account/info/hg"

    private fun cookieCredential(): String? {
        val urls = when (platform) {
            Platform.NETEASE -> listOf("https://music.163.com/")
            Platform.TIEBA -> listOf("https://tieba.baidu.com/")
            Platform.BILIBILI -> listOf("https://api.bilibili.com/", "https://www.bilibili.com/")
            Platform.SKLAND -> listOf("https://www.skland.com/")
        }
        val allowed = when (platform) {
            Platform.NETEASE -> setOf("MUSIC_U", "__csrf")
            Platform.TIEBA -> setOf("BDUSS", "STOKEN")
            Platform.BILIBILI -> setOf("SESSDATA", "bili_jct", "buvid3", "DedeUserID", "DedeUserID__ckMd5", "bili_ticket", "bili_ticket_expires")
            Platform.SKLAND -> setOf("SK_OAUTH_CRED_KEY")
        }
        val cookies = linkedMapOf<String, String>()
        urls.forEach { url -> CookieManager.getInstance().getCookie(url).orEmpty().split(';').forEach { raw ->
            val pair = raw.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0] in allowed && pair[1].isNotBlank() && pair[0] !in cookies) cookies[pair[0]] = pair[1]
        } }
        val required = when (platform) {
            Platform.NETEASE -> setOf("MUSIC_U")
            Platform.TIEBA -> setOf("BDUSS")
            Platform.BILIBILI -> setOf("SESSDATA", "bili_jct")
            Platform.SKLAND -> setOf("SK_OAUTH_CRED_KEY")
        }
        return if (required.all { it in cookies }) cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } else null
    }

    private fun detect(manual: Boolean) {
        val browser = page ?: return
        if (closing || checking || hidePage || (!manual && autoChecks >= 3)) return
        val credential = cookieCredential()
        if (!manual && (credential == null || credential == lastAutoCredential)) return
        if (credential != null && !manual) { autoChecks++; lastAutoCredential = credential }
        if (platform == Platform.SKLAND) {
            checking = true
            hidePage = true // The official JSON contains a token, so it must never be displayed.
            browser.visibility = android.view.View.INVISIBLE
            status = "正在读取本次登录授权…"
            browser.loadUrl(SK_TOKEN_URL)
        } else if (credential != null) validateCredential(credential)
        else if (manual) status = "尚未检测到完整登录授权，请先完成官方登录和验证码，再检测账号。"
    }

    private fun readSklandAuthorization(browser: WebView) {
        if (browser.url?.let(Uri::parse)?.let(::isSklandAuthorization) != true || closing) return
        browser.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})()") { value ->
            if (closing || isDestroyed) return@evaluateJavascript
            val credential = try {
                val text = JSONTokener(value).nextValue() as? String
                val token = text?.let(::JSONObject)?.optJSONObject("data")?.opt("content") as? String
                token?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Char::isWhitespace) }
            } catch (_: Exception) { null }
            if (credential == null) {
                checking = false
                status = "未取得已登录授权，请返回官网完成登录或手动验证后重试。"
                // Keep the token endpoint hidden even when the page or response is unexpected.
            } else validateCredential(credential)
        }
    }

    private fun validateCredential(credential: String) {
        checking = true
        status = "正在向官方平台校验账号身份…"
        lifecycleScope.launch {
            try {
                val adapter = (application as CheckInApp).adapters.getValue(platform)
                val identity = withContext(Dispatchers.IO) { adapter.validate(credential) }
                if (!closing && !isDestroyed) {
                    status = "已确认账号：${identity.displayName} · ID ${identity.userId}"
                    closeSession(credential)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!closing) {
                    checking = false
                    status = when ((e as? AdapterException)?.status) {
                        TaskStatus.EXPIRED -> "网页登录授权未生效或已过期，请完成官方登录后重试。"
                        TaskStatus.MANUAL -> "平台要求手动验证，请在官网完成验证码或安全检查后重试。"
                        else -> "官方身份校验未通过，请检查网络并完成网页登录后重试。"
                    }
                }
            }
        }
    }

    private fun returnToLogin() {
        if (closing || checking) return
        status = "请在官方网页完成登录；若页面要求打开其他 App，可尝试网页提供的其他登录方式。"
        // If the current page is the token response, keep it hidden until a safe page finishes.
        page?.loadUrl(startUrl)
    }

    private fun closeSession(credential: String?) {
        if (closing) return
        closing = true
        checking = false
        status = "正在清理网页登录会话…"
        disposeBrowser()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            cleaned = true
            if (!isDestroyed) {
                if (credential != null) {
                    val ticket = LoginResultBroker.put(platform, credential)
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TICKET, ticket))
                }
                finish()
            }
        }
    }

    private fun disposeBrowser() {
        val browser = page
        page = null
        browser?.let {
            it.stopLoading()
            it.clearCache(true)
            it.clearHistory()
            it.clearFormData()
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.removeAllViews()
            it.destroy()
        }
    }

    override fun onDestroy() {
        disposeBrowser()
        // Normal return/success cleared cookies before finish. On unexpected recreation the next
        // onCreate clears before loading any site; do not race a new Activity with a late clear.
        if (!cleaned) WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}
