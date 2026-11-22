package cn.onetap.checkin.login

import cn.onetap.checkin.core.Platform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialLoginPolicyTest {
    @Test fun officialStartPagesAndMobileSklandAreAllowed() {
        for (platform in Platform.entries.filterNot { it == Platform.HEYBOX }) {
            val url = OfficialLoginPolicy.startUrl(platform)
            assertNotNull(url)
            assertTrue(OfficialLoginPolicy.allowed(platform, url!!))
        }
        assertTrue(OfficialLoginPolicy.allowed(Platform.SKLAND, "https://m.skland.com/"))
        assertTrue(OfficialLoginPolicy.allowed(Platform.NETEASE, "https://y.music.163.com/login"))
    }
    @Test fun heyboxHasNoWebLoginEntryOrAllowedHost() {
        assertNull(OfficialLoginPolicy.startUrl(Platform.HEYBOX))
        assertFalse(OfficialLoginPolicy.allowed(Platform.HEYBOX, "https://login.xiaoheihe.cn/"))
        assertFalse(OfficialLoginPolicy.allowed(Platform.HEYBOX, "https://xiaoheihe.cn/app/bbs/home"))
    }
    @Test fun mainFrameRejectsOtherPlatformAndLookalikeDomains() {
        listOf("https://music.163.com.evil.test/", "https://evil.test/?next=https://music.163.com/", "https://music.163.com@evil.test/", "https://music.163.com./").forEach {
            assertFalse(it, OfficialLoginPolicy.allowed(Platform.NETEASE, it))
        }
        assertFalse(OfficialLoginPolicy.allowed(Platform.SKLAND, "https://music.163.com/"))
    }
    @Test fun transportRejectsCleartextCustomSchemesPortsAndCredentials() {
        listOf("http://music.163.com/", "orpheus://login", "intent://login", "file:///data/data/test", "https://music.163.com:444/", "https://user:secret@music.163.com/", "https://music.163.com\\@evil.test/").forEach {
            assertFalse(it, OfficialLoginPolicy.allowed(Platform.NETEASE, it))
        }
        assertTrue(OfficialLoginPolicy.allowed(Platform.NETEASE, "https://music.163.com:443/"))
    }
    @Test fun tokenBodyIsReadOnlyFromExactOfficialEndpoint() {
        assertTrue(OfficialLoginPolicy.isSklandToken("https://web-api.skland.com/account/info/hg"))
        listOf("http://web-api.skland.com/account/info/hg", "https://www.skland.com/account/info/hg", "https://web-api.skland.com/account/info/hg/", "https://web-api.skland.com/account/info/hg?next=x", "https://web-api.skland.com/account/info/hg#x", "https://web-api.skland.com/account/info/%68g").forEach {
            assertFalse(it, OfficialLoginPolicy.isSklandToken(it))
        }
        assertTrue(OfficialLoginPolicy.isSklandSensitivePage("https://web-api.skland.com/account/info/hg?next=x"))
        assertTrue(OfficialLoginPolicy.isSklandSensitivePage("https://web-api.skland.com/account/info/%68g"))
    }
    @Test fun sdkIsInvokedOnlyInsideOfficialHypergryphAccountPage() {
        assertTrue(OfficialLoginPolicy.isHypergryphAccount("https://user.hypergryph.com/login"))
        assertFalse(OfficialLoginPolicy.isHypergryphAccount("https://www.skland.com/"))
        assertFalse(OfficialLoginPolicy.isHypergryphAccount("https://user.hypergryph.com.evil.test/"))
        assertFalse(OfficialLoginPolicy.isHypergryphAccount("http://user.hypergryph.com/login"))
    }
    @Test fun onlySubframesMayInitializeWithExactAboutBlank() {
        assertTrue(OfficialLoginPolicy.allowedSubframe("about:blank"))
        assertFalse(OfficialLoginPolicy.allowed(Platform.NETEASE, "about:blank"))
        listOf("about:blank?x=1", "about:blank#x", "javascript:alert(1)", "file:///tmp/a", "content://data/a", "http://music.163.com/").forEach {
            assertFalse(it, OfficialLoginPolicy.allowedSubframe(it))
        }
    }
}
