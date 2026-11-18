package cn.onetap.checkin.login.qr

import cn.onetap.checkin.core.Platform

data class QrTicket(
    val platform: Platform,
    val content: String,
    val image: ByteArray? = null,
    val expiresAt: Long,
    internal val state: Map<String, String>
) {
    // Shared across providers polling this ticket; a completed one-use exchange must not be repeated.
    internal var terminalResult: QrPoll? = null
    internal var lastStatusCode: Int? = null
    // Access only while holding synchronized(ticket); never shared with another QR login session.
    internal val sessionCookies: MutableMap<String, String> = linkedMapOf()
    override fun toString() = "QrTicket(platform=$platform, expiresAt=$expiresAt, content=<redacted>, state=<redacted>)"
}

sealed interface QrPoll {
    data object Waiting : QrPoll
    data object Scanned : QrPoll
    data class Confirmed(val credential: String) : QrPoll {
        override fun toString() = "Confirmed(credential=<redacted>)"
    }
    data class Expired(val message: String) : QrPoll
    data class Failed(val message: String) : QrPoll
}

/** start() cannot return QrPoll.Failed; its exceptions contain only safe, user-facing text, never a raw cause. */
class QrLoginException internal constructor(message: String) : Exception(message)

interface QrLoginProvider {
    val platform: Platform
    fun start(): QrTicket
    fun poll(ticket: QrTicket): QrPoll
}

object QrLogin {
    fun provider(platform: Platform): QrLoginProvider = when (platform) {
        Platform.BILIBILI -> BilibiliQrProvider()
        Platform.NETEASE -> NeteaseQrProvider()
        Platform.SKLAND -> SklandQrProvider()
        Platform.TIEBA -> TiebaQrProvider()
    }
}
