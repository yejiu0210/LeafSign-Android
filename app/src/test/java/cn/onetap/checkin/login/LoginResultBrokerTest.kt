package cn.onetap.checkin.login

import cn.onetap.checkin.core.Platform
import org.junit.Assert.*
import org.junit.Test

class LoginResultBrokerTest {
    @Test fun credentialIsOneUseAndTicketContainsNoCredential() {
        val vault = LoginTicketVault { 1000L }
        val ticket = vault.put(Platform.SKLAND, "fixture-sensitive-token")
        assertFalse(ticket.contains("fixture-sensitive-token"))
        assertEquals("fixture-sensitive-token", vault.take(ticket, Platform.SKLAND))
        assertNull(vault.take(ticket, Platform.SKLAND))
    }
    @Test fun wrongPlatformCannotClaimOrReuseTicket() {
        val vault = LoginTicketVault { 1000L }
        val ticket = vault.put(Platform.SKLAND, "fixture")
        assertNull(vault.take(ticket, Platform.BILIBILI))
        assertNull(vault.take(ticket, Platform.SKLAND))
    }
    @Test fun ticketExpiresAtTwoMinutes() {
        var time = 1000L
        val vault = LoginTicketVault { time }
        val ticket = vault.put(Platform.TIEBA, "fixture")
        time += 120_000L
        assertNull(vault.take(ticket, Platform.TIEBA))
    }
    @Test fun newestResultReplacesOldAccountAndUnknownTicketDoesNotConsumeIt() {
        val vault = LoginTicketVault { 1000L }
        val old = vault.put(Platform.SKLAND, "main-fixture")
        val next = vault.put(Platform.SKLAND, "clone-fixture")
        assertNull(vault.take(old, Platform.SKLAND))
        assertEquals("clone-fixture", vault.take(next, Platform.SKLAND))
    }
    @Test fun clockMovingBackwardsInvalidatesTicket() {
        var time = 1000L
        val vault = LoginTicketVault { time }
        val ticket = vault.put(Platform.NETEASE, "fixture")
        time = 500L
        assertNull(vault.take(ticket, Platform.NETEASE))
    }
}
