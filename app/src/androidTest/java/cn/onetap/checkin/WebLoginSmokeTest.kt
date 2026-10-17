package cn.onetap.checkin

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.onetap.checkin.core.Platform
import cn.onetap.checkin.login.WebLoginActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** Requires a device/emulator; building this test APK is not evidence that it has run. */
class WebLoginSmokeTest {
    private fun launch() = ActivityScenario.launch<WebLoginActivity>(Intent(
        InstrumentationRegistry.getInstrumentation().targetContext, WebLoginActivity::class.java
    ).putExtra(WebLoginActivity.EXTRA_PLATFORM, Platform.SKLAND.name))

    @Test fun nativeReturnTitleAndStatusExistBeforeWebsiteCompletes() {
        launch().use { scenario -> scenario.onActivity { activity ->
            val decor = activity.window.decorView
            assertEquals(View.VISIBLE, decor.findViewWithTag<View>(WebLoginActivity.BACK_TAG).visibility)
            assertTrue(decor.findViewWithTag<TextView>(WebLoginActivity.TITLE_TAG).text.contains("官方网页登录"))
            assertTrue(decor.findViewWithTag<View>(WebLoginActivity.TITLE_TAG).getGlobalVisibleRect(Rect()))
            assertTrue(decor.findViewWithTag<View>(WebLoginActivity.BACK_TAG).isShown)
            assertTrue(decor.findViewWithTag<TextView>(WebLoginActivity.STATUS_TAG).text.isNotBlank())
            assertEquals(View.VISIBLE, decor.findViewWithTag<View>(WebLoginActivity.RETRY_TAG).visibility)
        } }
    }

    @Test fun mainFrameHttpFailureKeepsNativeControlsAndDoesNotExposeUrlQuery() {
        launch().use { scenario ->
            val browser = awaitBrowser(scenario)
            scenario.onActivity { activity ->
                val request = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse("https://user.hypergryph.com/login?token=TEST_SECRET_DO_NOT_SHOW")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                browser.webViewClient.onReceivedHttpError(browser, request, WebResourceResponse(
                    "text/plain", "UTF-8", 503, "Service Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0))
                ))
                val decor = activity.window.decorView
                val status = decor.findViewWithTag<TextView>(WebLoginActivity.STATUS_TAG).text.toString()
                assertTrue(status.contains("503")); assertFalse(status.contains("TEST_SECRET"))
                assertTrue(decor.findViewWithTag<View>(WebLoginActivity.BACK_TAG).isShown)
                assertTrue(decor.findViewWithTag<View>(WebLoginActivity.RETRY_TAG).isEnabled)
            }
        }
    }

    @Test fun rendererExitKeepsNativeReturnAndAllowsRebuild() {
        launch().use { scenario ->
            val browser = awaitBrowser(scenario)
            scenario.onActivity { activity ->
                assertTrue(browser.webViewClient.onRenderProcessGone(browser, null))
                val decor = activity.window.decorView
                assertTrue(decor.findViewWithTag<TextView>(WebLoginActivity.STATUS_TAG).text.contains("渲染进程退出"))
                assertTrue(decor.findViewWithTag<View>(WebLoginActivity.BACK_TAG).isShown)
                assertTrue(decor.findViewWithTag<View>(WebLoginActivity.RETRY_TAG).isEnabled)
                assertNull(decor.findViewWithTag<WebView>(WebLoginActivity.BROWSER_TAG))
            }
        }
    }

    private fun awaitBrowser(scenario: ActivityScenario<WebLoginActivity>): WebView {
        repeat(100) {
            var browser: WebView? = null
            scenario.onActivity { browser = it.window.decorView.findViewWithTag(WebLoginActivity.BROWSER_TAG) }
            browser?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("System WebView failed to initialize within 10 seconds; inspect the visible native status.")
    }
}
