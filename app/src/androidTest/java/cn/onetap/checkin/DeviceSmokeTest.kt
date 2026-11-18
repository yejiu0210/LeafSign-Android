package cn.onetap.checkin

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.onetap.checkin.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Test-only fixtures; never compiled into the installable main APK. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun emptyAppHasWorkingTabsAndDisabledRun() {
        compose.onNodeWithText("一叶签").assertExists()
        compose.onNodeWithText("一键签到").assertExists()
        compose.onNodeWithText("今天 0 / 0 项已完成").assertExists()
        compose.onNodeWithText("还没有账号").assertExists()
        compose.onNodeWithText("账号",useUnmergedTree=true).performClick()
        compose.onNodeWithText("添加账号").performClick()
        compose.onNodeWithText("官方网页登录并自动导入").assertExists()
        compose.onNodeWithText("手动导入凭证（备用）").performClick()
        compose.onNodeWithText("本地输入凭证").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("每日自动签到").performScrollTo().assertExists()
        compose.onNodeWithText("1 分钟后测试一次").performScrollTo().assertExists()
        compose.onNodeWithText("Shizuku 增强").performScrollTo().assertExists()
    }
    @Test fun screenshotsAreNotBlockedByApp() {
        compose.runOnUiThread {assertEquals(0,compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE)}
    }
    @Test fun keystoreEncryptionBackupRecoveryAndTamperDetection() {
        // A dedicated context directory avoids touching the target application's live state.
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val ctx=object:android.content.ContextWrapper(base) {
            override fun getNoBackupFilesDir():File=File(base.noBackupFilesDir,"store-fixture").apply{mkdirs()}
        }
        val directory=ctx.noBackupFilesDir
        directory.listFiles()?.forEach{it.delete()}
        val store=Store(ctx)
        val secret="test-only-credential-not-a-real-account"
        val a=Account(platform=Platform.SKLAND,userId="test-main",displayName="fixture",note="test",credential=secret,targets=emptyList(),enabled=emptySet())
        store.update{AppState(accounts=listOf(a))}
        val file=File(directory,"state.enc")
        assertFalse(String(file.readBytes()).contains(secret))
        assertEquals(secret,store.read().accounts.single().credential)
        val backup=File(directory,"state.enc.bak")
        assertTrue(file.renameTo(backup))
        assertEquals(secret,store.read().accounts.single().credential)
        assertTrue(file.exists())
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertThrows(Exception::class.java){store.read()}
        directory.listFiles()?.forEach{it.delete()}
    }
}
