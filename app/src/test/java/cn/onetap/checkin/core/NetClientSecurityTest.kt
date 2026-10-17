package cn.onetap.checkin.core

import org.junit.Assert.assertThrows
import org.junit.Test

class NetClientSecurityTest {
    private val net=NetClient(setOf("music.163.com"))
    @Test fun plaintextIsRejectedBeforeAnyNetwork(){assertThrows(IllegalArgumentException::class.java){net.get("http://music.163.com/")}}
    @Test fun unknownHostIsRejectedBeforeAnyNetwork(){assertThrows(IllegalArgumentException::class.java){net.get("https://example.invalid/")}}
    @Test fun suffixLookalikeIsRejectedBeforeAnyNetwork(){assertThrows(IllegalArgumentException::class.java){net.get("https://music.163.com.example.invalid/")}}
    @Test fun alternatePortIsRejectedBeforeAnyNetwork(){assertThrows(IllegalArgumentException::class.java){net.get("https://music.163.com:8443/")}}
    @Test fun userInfoIsRejectedBeforeAnyNetwork(){assertThrows(IllegalArgumentException::class.java){net.get("https://test:fixture@music.163.com/")}}
}
