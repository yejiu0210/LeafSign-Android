package cn.onetap.checkin.login

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import cn.onetap.checkin.CheckInApp
import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.core.TaskStatus
import cn.onetap.checkin.core.Rules
import cn.onetap.checkin.theme.ThemePalette
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.coroutines.resume

/** Native controls remain visible even if the official website or its renderer fails. */
class WebLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_TICKET = "ticket"
        const val TITLE_TAG = "official_login_title"
        const val BACK_TAG = "official_login_back"
        const val STATUS_TAG = "official_login_status"
        const val RETRY_TAG = "official_login_retry"
        const val BROWSER_TAG = "official_login_browser"
        private const val SK_TOKEN_URL = "https://web-api.skland.com/account/info/hg"
    }

    private val systemDark by lazy { resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private val themeMode by lazy { (application as CheckInApp).state.value.settings.themeMode }
    private val darkTheme by lazy { themeMode.isDark(systemDark) }
    private val colors by lazy { ThemePalette.resolve(themeMode, systemDark) }

    private lateinit var platform: Platform
    private lateinit var browserContainer: FrameLayout
    private lateinit var placeholder: TextView
    private lateinit var statusText: TextView
    private lateinit var domainText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var detectButton: Button
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private var page: WebView? = null
    private var pageTimeout: Job? = null
    private var sessionTimeout: Job? = null
    private var validationJob: Job? = null
    private var sklandProbeJob: Job? = null
    private var heyboxProbeJob: Job? = null
    private var sessionGeneration = 0
    private var navigationGeneration = 0
    private var failedNavigation = false
    private var preparing = true
    private var loading = false
    private var checking = false
    private var hidePage = false
    private var closing = false
    private var cleaned = false
    private var autoChecks = 0
    private var lastAutoFingerprint = ""
    private var lastSdkProbeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // WebView derives prefers-color-scheme from the native theme's isLightTheme attribute.
        // Select a real dark/light framework theme before creating any native or web views.
        setTheme(if (darkTheme) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        platform = Platform.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_PLATFORM) }
            ?: run { finish(); return }
        setResult(RESULT_CANCELED)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = buildNativeLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeSession(null) }
        })
        // Render the fixed native header before initializing the device's WebView provider.
        root.doOnPreDraw { root.post { if (!closing && !isDestroyed) beginFreshSession() } }
        lifecycleScope.launch { while (!closing) { delay(3_000); detect(manual = false) } }
    }

    private fun buildNativeLayout(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.background) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, max(safe.bottom, keyboard.bottom))
            insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(colors.surface) }
        backButton = Button(this).apply { text = "返回"; tag = BACK_TAG; setOnClickListener { closeSession(null) } }
        styleButton(backButton)
        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(76), dp(52)))
        toolbar.addView(label("${platform.title} · 官方网页登录", 17f).apply { tag = TITLE_TAG }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(toolbar)
        root.addView(label("登录和验证码由官网处理。只导入本次登录，不读取其他 App 数据。", 12f).apply { setTextColor(colors.onSurfaceVariant) })
        domainText = label("准备独立登录会话", 12f).apply { setTextColor(colors.onSurfaceVariant) }; root.addView(domainText)
        statusText = label("正在清理上次登录会话…", 13f).apply { tag = STATUS_TAG; minLines = 2; maxLines = 4; setTextColor(colors.primary) }
        root.addView(statusText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(colors.primary)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        val actions = LinearLayout(this)
        detectButton = Button(this).apply { text = "登录完成，检测账号"; textSize = 12f; setOnClickListener { detect(manual = true) } }
        retryButton = Button(this).apply { text = if(platform==Platform.HEYBOX) "重新打开手机版登录" else "重新加载官网"; tag = RETRY_TAG; textSize = 12f; setOnClickListener { retryLogin() } }
        styleButton(detectButton); styleButton(retryButton)
        actions.addView(detectButton, LinearLayout.LayoutParams(0, dp(52), 1.2f))
        actions.addView(retryButton, LinearLayout.LayoutParams(0, dp(52), 1f)); root.addView(actions)
        browserContainer = FrameLayout(this).apply { setBackgroundColor(colors.background) }
        placeholder = label("正在准备独立登录会话…", 15f).apply { gravity = Gravity.CENTER; setTextColor(colors.onSurfaceVariant) }
        browserContainer.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        root.addView(browserContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); applySystemBarTheme(root); ViewCompat.requestApplyInsets(root); updateControls()
        return root
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(colors.onSurface); setPadding(dp(12), dp(5), dp(12), dp(5))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private fun styleButton(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(colors.surface)
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf((colors.onSurface and 0x00FFFFFF) or (0x66 shl 24), colors.primary)
        ))
    }
    @Suppress("DEPRECATION")
    private fun applySystemBarTheme(root: View) {
        window.decorView.setBackgroundColor(colors.background)
        window.statusBarColor = colors.background; window.navigationBarColor = colors.background
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !darkTheme; isAppearanceLightNavigationBars = !darkTheme
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun showStatus(message: String) { statusText.text = message; updateControls() }
    private fun updateControls() {
        progressBar.visibility = if (preparing || loading || checking || closing) View.VISIBLE else View.INVISIBLE
        detectButton.isEnabled = page != null && !preparing && !checking && !closing && !hidePage
        retryButton.isEnabled = !preparing && !checking && !closing
        backButton.isEnabled = !closing
    }

    private fun beginFreshSession() {
        if (closing) return
        validationJob?.cancel(); sklandProbeJob?.cancel(); heyboxProbeJob?.cancel()
        val generation = ++sessionGeneration
        preparing = true; checking = false; loading = false; hidePage = false; cleaned = false
        disposeBrowser(); placeholder.text = "正在准备独立登录会话…"; placeholder.visibility = View.VISIBLE
        showStatus("正在清理上次登录会话…")
        sessionTimeout?.cancel()
        sessionTimeout = lifecycleScope.launch {
            try {
                WebView.setWebContentsDebuggingEnabled(false)
                WebStorage.getInstance().deleteAllData()
                withTimeout(10_000) { WebLoginCookieClear.awaitClear() }
                if (generation != sessionGeneration || isFinishing || isDestroyed || closing) return@launch
                preparing = false; createBrowser()
            } catch (_: TimeoutCancellationException) {
                if (generation == sessionGeneration && !closing) {
                    preparing = false
                    showStatus("系统仍在清理旧登录会话，尚未打开官网。请稍后重新加载；新账号会等待旧会话清理完成。")
                    placeholder.text = "旧会话清理尚未完成，上方返回和重试按钮仍可使用。"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                preparing = false
                showStatus("无法启动网页组件。请安装或更新 Android System WebView / Chrome 后重试。")
                placeholder.text = "系统网页组件未能启动。"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createBrowser() {
        try {
            val browser = WebView(this)
            page = browser // Ensure partially initialized providers are disposed on failure.
            browser.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; cacheMode = WebSettings.LOAD_NO_CACHE
                setSavePassword(false); saveFormData = false
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
                // The official site's own dark CSS takes precedence. On Android 13+, the
                // provider may darken other content if the site permits it. Older providers
                // retain the site's appearance; do not inject CSS into login/password pages.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try { isAlgorithmicDarkeningAllowed = darkTheme } catch (_: UnsupportedOperationException) { }
                }
                // Official NetEase home HTML sends Android/Mobile clients toward its app landing
                // page. Use this provider's desktop-web UA, never an official-app identity.
                if (platform == Platform.NETEASE) userAgentString = userAgentString
                    .replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
                    .replace(" Version/4.0", "").replace(" Mobile", "")
                // Heybox's login center has its own mobile viewport. Its desktop-home fallback
                // has no viewport and a fixed centered dialog: overview keeps the whole form visible.
                if (platform == Platform.NETEASE || platform == Platform.HEYBOX) {
                    useWideViewPort = true; loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true; displayZoomControls = false
                }
            }
            browser.tag = BROWSER_TAG; browser.setBackgroundColor(colors.background); browser.isSaveEnabled = false
            browser.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            browser.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view === page && !closing) progressBar.contentDescription = "网页加载进度 $newProgress%"
                }
            }
            browser.webViewClient = officialClient(); page = browser
            browserContainer.addView(browser, 0, FrameLayout.LayoutParams(-1, -1))
            placeholder.visibility = View.GONE; loadLoginPage()
        } catch (_: Exception) {
            disposeBrowser(); loading = false
            showStatus("系统网页组件启动失败，请更新 Android System WebView / Chrome 后重试。")
            placeholder.visibility = View.VISIBLE; placeholder.text = "网页暂时不可用，上方按钮仍可操作。"
        }
    }

    private fun officialClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val allowed = if (request.isForMainFrame) allowedNavigation(request.url) else OfficialLoginPolicy.allowedSubframe(request.url.toString())
            if (!allowed && request.isForMainFrame) failPage("官网尝试跳转到非 HTTPS 或未经核实的地址，已阻止。请使用网页内其他登录方式。", stop = false)
            if (allowed && request.isForMainFrame && view === page && platform==Platform.HEYBOX && !preparing && !checking && !closing &&
                autoChecks<3 && OfficialLoginPolicy.isHeyboxLoginReturn(request.url.toString()) &&
                OfficialLoginPolicy.canReadHeyboxSession(view.url.orEmpty())) {
                // The login center writes document.cookie just before the official redirect.
                // Read that live, coherent scope; CookieManager may still hold the earlier values.
                pageTimeout?.cancel(); loading=false; failedNavigation=false
                probeHeyboxSession(view, manual=false)
                return true
            }
            return !allowed
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
            if (if (request.isForMainFrame) allowedNavigation(request.url) else OfficialLoginPolicy.allowedSubframe(request.url.toString())) null
            else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== page || closing) return
            val uri = url?.let(Uri::parse)
            if (uri == null || !allowedNavigation(uri)) { failPage("已停止未经核实的页面跳转，请重新加载官网。"); return }
            domainText.text = "官方域名：${uri.host.orEmpty()}" + if (platform == Platform.NETEASE) " · 电脑网页" else ""
            if (isSklandAuthorization(uri)) concealPage("正在读取本次登录授权并校验身份…")
            loading = true; failedNavigation = false; armPageTimeout(); updateControls()
        }
        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (view !== page || closing || url == null || url != view.url) return
            val uri = Uri.parse(url)
            if (allowedNavigation(uri) && !isSklandAuthorization(uri) && !checking) revealPage()
        }
        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== page || url == null || url != view.url || closing || failedNavigation) return
            pageTimeout?.cancel(); loading = false
            val uri = Uri.parse(url)
            if (isSklandAuthorization(uri)) {
                if (hidePage && checking) readSklandAuthorization(view)
            } else if (allowedNavigation(uri)) {
                if (!checking) {
                    revealPage()
                    showStatus(if(platform==Platform.HEYBOX) "请在官方手机版完成登录，登录后自动导入。电脑弹窗可双指缩放；协议请本人勾选。" else "请在官网完成登录。登录后自动校验，也可点击“登录完成，检测账号”。")
                    detect(manual = false); inspectEmptyPage(view, navigationGeneration)
                }
            }
            updateControls()
        }
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
            handler.cancel()
            if (view === page) failPage("官网安全连接验证失败。请检查手机日期、网络和系统 WebView 后重试。")
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (view === page && request?.isForMainFrame == true) {
                val reason = when (error?.errorCode) {
                    ERROR_HOST_LOOKUP -> "无法解析官网域名"
                    ERROR_CONNECT -> "无法连接官网"
                    ERROR_TIMEOUT -> "官网连接超时"
                    ERROR_FAILED_SSL_HANDSHAKE -> "官网安全连接失败"
                    else -> "官网页面加载失败"
                }
                failPage("$reason。请检查网络后重新加载官网。")
            }
        }
        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (view === page && request?.isForMainFrame == true) {
                val code = errorResponse?.statusCode?.takeIf { it in 400..599 }
                failPage("官网返回 HTTP ${code ?: "错误"}，未完成登录。请稍后重新加载。")
            }
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            if (view === page) {
                pageTimeout?.cancel(); checking = false; loading = false; validationJob?.cancel(); sklandProbeJob?.cancel(); heyboxProbeJob?.cancel()
                disposeBrowser(rendererGone = true); placeholder.visibility = View.VISIBLE
                placeholder.text = "网页渲染进程已退出，可以重新加载官网恢复。"
                showStatus("系统 WebView 渲染进程退出。请重新加载；若重复出现，请更新 WebView / Chrome。")
            } else { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            return true
        }
    }

    private fun allowedNavigation(uri: Uri) = OfficialLoginPolicy.allowed(platform, uri.toString())
    private fun isSklandAuthorization(uri: Uri) = platform == Platform.SKLAND && OfficialLoginPolicy.isSklandSensitivePage(uri.toString())
    private fun armPageTimeout() {
        pageTimeout?.cancel(); val navigation = ++navigationGeneration
        pageTimeout = lifecycleScope.launch {
            delay(25_000)
            if (navigation == navigationGeneration && !closing && loading) failPage("官网加载超过 25 秒，结果未确认。请检查网络后重试；不会把白屏当作登录成功。")
        }
    }
    private fun inspectEmptyPage(browser: WebView, navigation: Int) {
        lifecycleScope.launch {
            delay(3_000)
            if (page !== browser || navigation != navigationGeneration || checking || closing || hidePage) return@launch
            // Only an empty/non-empty flag leaves this page, never its HTML or form values.
            browser.evaluateJavascript("(function(){var b=document.body;return !!b && ((b.innerText||'').trim().length>0 || !!b.querySelector('input,button,img,svg,canvas,iframe'));})()") { value ->
                if (page === browser && navigation == navigationGeneration && !checking && !closing && value == "false") showStatus("官网未显示可操作内容。可重新加载；若仍空白，请使用账号页手动导入备用方式。")
            }
        }
    }
    private fun failPage(message: String, stop: Boolean = true) {
        pageTimeout?.cancel(); navigationGeneration++; failedNavigation = true; loading = false; checking = false
        if (stop) page?.stopLoading()
        if (hidePage) { placeholder.visibility = View.VISIBLE; placeholder.text = "授权内容保持隐藏，可重新加载官网返回登录。" }
        showStatus(message)
    }
    private fun concealPage(message: String) {
        hidePage = true; page?.visibility = View.INVISIBLE; placeholder.visibility = View.VISIBLE; placeholder.text = message
    }
    private fun revealPage() {
        // Hide the token endpoint even if it unexpectedly adds query/fragment/encoded path text.
        if (platform == Platform.SKLAND && OfficialLoginPolicy.isSklandSensitivePage(page?.url.orEmpty())) return
        hidePage = false; page?.visibility = View.VISIBLE; placeholder.visibility = View.GONE
    }

    private fun cookieCredential(): String? {
        // Heybox uses a live single-page snapshot instead of merging independent host jars.
        if (platform == Platform.HEYBOX) return null
        val urls = when (platform) {
            Platform.NETEASE -> listOf("https://music.163.com/")
            Platform.TIEBA -> listOf("https://tieba.baidu.com/")
            Platform.BILIBILI -> listOf("https://api.bilibili.com/", "https://www.bilibili.com/")
            Platform.SKLAND -> listOf("https://www.skland.com/", "https://m.skland.com/")
            Platform.HEYBOX -> emptyList()
        }
        val allowed = when (platform) {
            Platform.NETEASE -> setOf("MUSIC_U", "__csrf")
            Platform.TIEBA -> setOf("BDUSS", "STOKEN")
            Platform.BILIBILI -> setOf("SESSDATA", "bili_jct", "buvid3", "DedeUserID", "DedeUserID__ckMd5", "bili_ticket", "bili_ticket_expires")
            Platform.SKLAND -> setOf("SK_OAUTH_CRED_KEY")
            Platform.HEYBOX -> setOf("pkey", "user_pkey", "heybox_id", "user_heybox_id", "x_xhh_tokenid")
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
            Platform.HEYBOX -> emptySet()
        }
        val complete=required.all {it in cookies}
        return if (complete) cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } else null
    }
    private fun detect(manual: Boolean) {
        val browser = page ?: return
        if (preparing || closing || checking || hidePage || loading || failedNavigation || (!manual && autoChecks >= 3)) return
        if (platform == Platform.HEYBOX) {
            // Do not test guest/stale cookies while the official password/SMS form is being filled.
            if (!manual && !OfficialLoginPolicy.isHeyboxLoginReturn(browser.url.orEmpty())) return
            probeHeyboxSession(browser, manual); return
        }
        if (platform == Platform.SKLAND && OfficialLoginPolicy.isHypergryphAccount(browser.url.orEmpty())) {
            // The current official portal routes a completed, agreed login to /userInfo. Do not
            // hide a user's SMS form every three seconds while /login is still being completed.
            if (!manual && Uri.parse(browser.url).path != "/userInfo") return
            if (!manual && SystemClock.elapsedRealtime() - lastSdkProbeAt < 15_000) return
            lastSdkProbeAt = SystemClock.elapsedRealtime()
            probeSklandSdk(browser, manual); return
        }
        val credential = cookieCredential()
        if (!manual && (credential == null || !newAutoCredential(credential))) return
        if (platform == Platform.SKLAND) {
            checking = true; concealPage("正在读取本次登录授权并校验身份…"); showStatus("正在读取本次登录授权…")
            browser.loadUrl(SK_TOKEN_URL)
        } else if (credential != null) validateCredential(credential)
        else if (manual) showStatus("尚未检测到完整登录授权。请完成官方登录和验证码后再检测账号。")
    }
    private fun newAutoCredential(credential: String): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(credential.toByteArray()).joinToString("") { "%02x".format(it) }
        if (fingerprint == lastAutoFingerprint) return false
        lastAutoFingerprint = fingerprint; autoChecks++; return true
    }

    private fun probeHeyboxSession(browser: WebView, manual: Boolean) {
        if (heyboxProbeJob?.isActive == true || !OfficialLoginPolicy.canReadHeyboxSession(browser.url.orEmpty())) return
        val generation=sessionGeneration
        checking=true
        concealPage("正在接收本次小黑盒官网登录会话…")
        showStatus("正在接收本次小黑盒官网登录会话…")
        heyboxProbeJob=lifecycleScope.launch {
            var validating=false
            try {
                val credential=withTimeout(8_000) {
                    // Give the official synchronous Cookie writes a short settlement window.
                    repeat(6) {
                        delay(200)
                        if (closing || generation!=sessionGeneration || page!==browser ||
                            !OfficialLoginPolicy.canReadHeyboxSession(browser.url.orEmpty())) return@withTimeout null
                        val raw=evaluate(browser, HeyboxLoginCookies.SNAPSHOT_SCRIPT)
                        if (closing || generation!=sessionGeneration || page!==browser ||
                            !OfficialLoginPolicy.canReadHeyboxSession(browser.url.orEmpty())) return@withTimeout null
                        val snapshot=try { JSONTokener(raw).nextValue() as? String } catch (_: Exception) { null }
                        val candidate=HeyboxLoginCookies.credential(snapshot)
                        if (candidate!=null) return@withTimeout candidate
                    }
                    null
                }
                if (credential!=null) {
                    if (manual || newAutoCredential(credential)) { validating=true; validateCredential(credential) }
                    else showStatus("此登录会话已检测过。完成重新登录后，请点击“登录完成，检测账号”。")
                } else showStatus("尚未取得完整的小黑盒登录会话。请在官方页面完成登录和验证后，再点“登录完成，检测账号”。")
            } catch (_: TimeoutCancellationException) {
                if (!closing) showStatus("小黑盒网页登录会话读取超时，尚未保存账号。请重新检测或加载官方登录页。")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!closing) showStatus("小黑盒官方页面暂时无法读取登录会话，请完成登录后重新检测。")
            } finally {
                if (!validating && !closing && generation==sessionGeneration && page===browser) {
                    checking=false; revealPage(); updateControls()
                }
            }
        }
    }

    /** Official user-center index.62f8883d.js uses HG_SDK.User.getToken(), result and data.token. */
    private fun probeSklandSdk(browser: WebView, manual: Boolean) {
        if (sklandProbeJob?.isActive == true || !OfficialLoginPolicy.isHypergryphAccount(browser.url.orEmpty())) return
        val key = "__oneTapLogin_" + UUID.randomUUID().toString().replace("-", "")
        val quoted = JSONObject.quote(key)
        val script = """(function(){var s=window.HG_SDK;if(!s||!s.User||typeof s.User.getToken!=='function')return 'not-ready';var b={state:'pending'};window[$quoted]=b;try{Promise.resolve(s.User.getToken()).then(function(r){if(r&&r.result===true&&r.data&&typeof r.data.token==='string'){b.state='ok';b.token=r.data.token;}else b.state='not-logged-in';}).catch(function(){b.state='error';});}catch(e){b.state='error';}return 'started';})()"""
        checking = true
        concealPage("正在通过官方账号组件读取本次登录授权…")
        showStatus("正在通过官方账号组件读取本次登录授权…")
        sklandProbeJob = lifecycleScope.launch {
            var validating = false
            try {
                val result = withTimeout(12_000) {
                    if (evaluate(browser, script) != "\"started\"") return@withTimeout JSONObject().put("state", "not-ready")
                    repeat(40) {
                        delay(250)
                        if (closing || page !== browser || !OfficialLoginPolicy.isHypergryphAccount(browser.url.orEmpty())) return@withTimeout JSONObject().put("state", "navigated")
                        val value = evaluate(browser, "JSON.stringify(window[$quoted]||{state:'missing'})")
                        val outcome = try { JSONObject(JSONTokener(value).nextValue() as? String ?: "{}") } catch (_: Exception) { JSONObject() }
                        if (outcome.optString("state") != "pending") return@withTimeout outcome
                    }
                    JSONObject().put("state", "timeout")
                }
                val token = (result.opt("token") as? String)?.takeIf { result.optString("state") == "ok" && validToken(it) }
                if (token != null) {
                    if (manual || newAutoCredential(token)) { validating = true; validateCredential(token) }
                    else showStatus("此登录授权已检测过。若已完成重新登录，请点击“登录完成，检测账号”。")
                } else showStatus(when (result.optString("state")) {
                    "not-ready" -> "官方账号组件尚未加载完成。请等待页面显示后再检测账号。"
                    "timeout" -> "官方授权检测超时，请检查网络后重试。"
                    "navigated" -> "官网页面已跳转，尚未取得授权。请在当前官方页面完成登录后重试。"
                    else -> "尚未取得官方账号登录授权。请先完成短信登录或安全验证后再检测。"
                })
            } catch (_: TimeoutCancellationException) {
                if (!closing) showStatus("官方授权检测超时，尚未保存账号。请检查网络后重新检测。")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!closing) showStatus("官方账号组件暂时无法读取授权，请重新加载官网后重试。")
            } finally {
                if (!closing && page === browser && OfficialLoginPolicy.isHypergryphAccount(browser.url.orEmpty())) {
                    try { browser.evaluateJavascript("(function(){delete window[$quoted];return true;})()", null) } catch (_: Exception) { }
                }
                if (!validating && !closing && page === browser) { checking = false; revealPage(); updateControls() }
            }
        }
    }
    private suspend fun evaluate(browser: WebView, script: String): String = withTimeout(3_000) {
        suspendCancellableCoroutine { continuation ->
            browser.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it ?: "null") }
        }
    }
    private fun validToken(value: String) = value.isNotBlank() && value.length <= 4096 && value.none(Char::isWhitespace)
    private fun readSklandAuthorization(browser: WebView) {
        if (closing) return
        if (!OfficialLoginPolicy.isSklandToken(browser.url.orEmpty())) {
            checking = false
            showStatus("官方授权页面地址发生变化，已保持隐藏。请重新加载官网，不会自动导入未知响应。")
            return
        }
        sklandProbeJob = lifecycleScope.launch {
            try {
                val value = evaluate(browser, "(function(){return document.body ? document.body.innerText : '';})()")
                if (closing || isDestroyed || page !== browser) return@launch
                val credential = try {
                    val text = JSONTokener(value).nextValue() as? String
                    (text?.let(::JSONObject)?.optJSONObject("data")?.opt("content") as? String)?.takeIf(::validToken)
                } catch (_: Exception) { null }
                if (credential == null) { checking = false; showStatus("未取得已登录授权。请重新加载官网完成登录后重试。") }
                else validateCredential(credential)
            } catch (_: TimeoutCancellationException) {
                if (!closing) { checking = false; showStatus("官方授权读取超时，尚未保存账号。请重新加载官网后重试。") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (!closing) { checking = false; showStatus("未能读取官方授权，请重新加载官网后重试。") } }
        }
    }
    private fun validateCredential(credential: String) {
        checking = true; concealPage("正在向官方平台校验账号身份…"); showStatus("正在向官方平台校验账号身份…")
        validationJob = lifecycleScope.launch {
            try {
                val adapter = (application as CheckInApp).adapters.getValue(platform)
                val identity = withTimeout(45_000) { runInterruptible(Dispatchers.IO) { adapter.validate(credential) } }
                if (!closing && !isDestroyed) { showStatus("已确认账号：${identity.displayName} · ID ${identity.userId}"); closeSession(identity.updatedCredential ?: credential) }
            } catch (_: TimeoutCancellationException) {
                if (!closing) validationFailed("官方身份校验超时，尚未保存账号。请检查网络后重试。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val adapterError=e as? AdapterException
                if (!closing) validationFailed(if (platform==Platform.HEYBOX && adapterError!=null) {
                    val diagnostic=Rules.safeDiagnostic(adapterError.diagnostic)
                    adapterError.message + if(diagnostic.isNotBlank()) "\n诊断：$diagnostic" else ""
                } else when (adapterError?.status) {
                    TaskStatus.EXPIRED -> "网页登录授权未生效或已过期，请完成官方登录后重试。"
                    TaskStatus.MANUAL -> "平台要求手动验证，请在官网完成验证码或安全检查后重试。"
                    else -> "官方身份校验未通过，请检查网络并完成网页登录后重试。"
                })
            }
        }
    }
    private fun validationFailed(message: String) {
        checking = false
        if (page?.url?.let(Uri::parse)?.let(::isSklandAuthorization) != true) revealPage()
        showStatus(message)
    }
    private fun retryLogin() {
        if (preparing || closing || checking) return
        if (page == null) beginFreshSession() else loadLoginPage()
    }
    private fun loadLoginPage() {
        val browser = page ?: return
        pageTimeout?.cancel(); sklandProbeJob?.cancel(); heyboxProbeJob?.cancel(); browser.stopLoading()
        checking = false; loading = true; failedNavigation = false; autoChecks = 0; lastAutoFingerprint = ""
        concealPage("正在加载官方登录页…"); showStatus("正在加载官网，请稍候。加载异常会在上方显示原因。")
        armPageTimeout(); browser.loadUrl(OfficialLoginPolicy.startUrl(platform))
    }
    private fun closeSession(credential: String?) {
        if (closing) return
        closing = true; val generation = ++sessionGeneration
        pageTimeout?.cancel(); sessionTimeout?.cancel(); sklandProbeJob?.cancel(); heyboxProbeJob?.cancel()
        checking = false; preparing = false; loading = false
        showStatus("正在清理网页登录会话…"); disposeBrowser()
        placeholder.visibility = View.VISIBLE; placeholder.text = "正在清理本次网页登录会话…"
        sessionTimeout = lifecycleScope.launch {
            try {
                WebStorage.getInstance().deleteAllData()
                withTimeout(10_000) { WebLoginCookieClear.awaitClear() }
                if (generation != sessionGeneration || isDestroyed) return@launch
                cleaned = true
                if (credential != null) setResult(RESULT_OK, Intent().putExtra(EXTRA_TICKET, LoginResultBroker.put(platform, credential)))
                finish()
            } catch (_: TimeoutCancellationException) {
                Toast.makeText(this@WebLoginActivity, "网页会话清理超时，账号未保存，请重新登录。", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                Toast.makeText(this@WebLoginActivity, "网页会话清理失败，账号未保存，请重新登录。", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    private fun disposeBrowser(rendererGone: Boolean = false) {
        val browser = page; page = null
        browser?.let {
            // A renderer-gone WebView cannot be reused; remove and destroy it without page calls.
            if (!rendererGone) try { it.stopLoading(); it.clearCache(true); it.clearHistory(); it.clearFormData() } catch (_: Exception) { }
            (it.parent as? ViewGroup)?.removeView(it)
            try { it.removeAllViews(); it.destroy() } catch (_: Exception) { }
        }
    }
    override fun onDestroy() {
        pageTimeout?.cancel(); sessionTimeout?.cancel(); sklandProbeJob?.cancel(); heyboxProbeJob?.cancel(); disposeBrowser()
        // Do not race a new Activity with a late asynchronous cookie clear after recreation.
        if (!cleaned) try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) { }
        super.onDestroy()
    }
}
