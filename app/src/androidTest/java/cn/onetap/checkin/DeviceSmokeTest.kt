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
        compose.onAllNodesWithText("一键签到").assertCountEquals(2)
        compose.onNodeWithText("今天 0 / 0 项已完成").assertExists()
        compose.onNodeWithText("账号",useUnmergedTree=true).performClick()
        compose.onNodeWithText("添加账号").performClick()
        compose.onNodeWithText("本地输入凭证").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("每日后台签到（可选）").performScrollTo().assertExists()
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
