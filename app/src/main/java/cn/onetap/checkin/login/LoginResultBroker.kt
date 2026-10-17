package cn.onetap.checkin.login

import cn.onetap.checkin.core.Platform
import java.util.UUID

/** Credentials stay in process memory; Activity results contain only a short-lived one-use ticket. */
object LoginResultBroker {
    private val vault = LoginTicketVault { System.currentTimeMillis() }
    fun put(platform: Platform, credential: String): String = vault.put(platform, credential)
    fun take(ticket: String, expectedPlatform: Platform): String? = vault.take(ticket, expectedPlatform)
}

internal class LoginTicketVault(private val nowMs: () -> Long) {
    private data class Entry(val ticket: String, val platform: Platform, val credential: String, val createdAt: Long)
    private var entry: Entry? = null

    @Synchronized fun put(platform: Platform, credential: String): String {
        require(credential.isNotBlank())
        val ticket = UUID.randomUUID().toString()
        entry = Entry(ticket, platform, credential, nowMs())
        return ticket
    }

    @Synchronized fun take(ticket: String, expectedPlatform: Platform): String? {
        val saved = entry ?: return null
        val age = nowMs() - saved.createdAt
        if (age < 0 || age >= 120_000L) { entry = null; return null }
        if (ticket != saved.ticket) return null
        entry = null
        return saved.credential.takeIf { saved.platform == expectedPlatform }
    }
}
