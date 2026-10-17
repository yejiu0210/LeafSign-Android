package cn.onetap.checkin.core

import cn.onetap.checkin.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RetiredPlatformMigrationTest {
    private val period = "2026-10-17"
    private fun structuralValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { structuralValue(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { structuralValue(value.get(it)) }
        is Number -> "number" to value.toString()
        JSONObject.NULL -> null
        else -> value
    }
    private fun assertJsonEquals(expected: JSONObject, actual: JSONObject?) {
        assertNotNull(actual)
        assertEquals(structuralValue(expected), structuralValue(actual))
    }
    private fun account(id: String, platform: Platform) = Account(
        id = id, platform = platform, userId = "fixture-user-$id", displayName = "fixture-$id", note = "fixture-note-$id",
        credential = "fixture-credential-$id", targets = listOf(Target("target-$id", "fixture", "fixture-label-$id",
            game = "fixture-game", server = "fixture-server", metadata = mapOf("ownerUserId" to "fixture-user-$id"))),
        enabled = setOf("target-$id"), details = "fixture-details-$id", checkedAt = 1234L
    )
    private fun record(id: String, a: Account) = Record(id = id, accountId = a.id, platform = a.platform,
        accountLabel = a.note, targetId = a.targets.single().id, task = "fixture-task", period = period, time = 5678L,
        status = TaskStatus.UNKNOWN, message = "fixture-message", diagnostic = "error_code=4", experienceState = "fixture-state")
    private fun retainedState(): AppState {
        val accounts = listOf(account("netease", Platform.NETEASE), account("tieba", Platform.TIEBA),
            account("skland-main", Platform.SKLAND), account("skland-clone", Platform.SKLAND), account("bilibili", Platform.BILIBILI))
        val checkpoint = TaskCheckpoint("tieba", "tieba:like:123", period,
            mapOf("stage" to "like_sent", "tid" to "456", "likeAttempts" to "1", "candidateRepairUsed" to "true"))
        return AppState(accounts, accounts.map { record("record-${it.id}", it) },
            Settings(true, 22, 37, true, false, ThemeMode.DARK), listOf(checkpoint))
    }
    private fun legacyAccount() = JSONObject().put("id", "retired-account").put("platform", "HEYBOX")
        .put("credential", "fixture-retired-session")
    private fun addLegacy(root: JSONObject) = root.apply {
        getJSONArray("accounts").put(legacyAccount())
        getJSONArray("records").put(JSONObject().put("platform", "HEYBOX").put("accountId", "retired-account"))
        getJSONArray("checkpoints").put(JSONObject().put("accountId", "retired-account").put("targetId", "heybox:daily"))
    }

    @Test fun mixedLegacyStateKeepsEveryOtherAccountSettingHistoryAndContinuation() {
        val expected = retainedState()
        val decoded = StateJson.decode(addLegacy(StateJson.encode(expected)))
        assertEquals(expected, decoded.state)
        val migrated = decoded.migratedRoot!!
        assertJsonEquals(StateJson.encode(expected), migrated)
        assertFalse(migrated.toString().contains("fixture-retired-session"))
    }

    @Test fun retiredOnlyStateBecomesEmptyWhileKeepingSettings() {
        val expected = AppState(settings = Settings(themeMode = ThemeMode.DARK, offerCatchup = false))
        val decoded = StateJson.decode(addLegacy(StateJson.encode(expected)))
        assertEquals(expected, decoded.state)
        assertJsonEquals(StateJson.encode(expected), decoded.migratedRoot)
    }

    @Test fun orphanRetiredRecordsAndCheckpointsAreRemovedByExactPlatformOrTargetPrefix() {
        val expected = retainedState()
        val root = StateJson.encode(expected)
        root.getJSONArray("records").put(JSONObject().put("platform", "HEYBOX").put("accountId", "orphan-one"))
            .put(JSONObject().put("platform", "TIEBA").put("accountId", "orphan-two").put("targetId", "heybox:daily"))
        root.getJSONArray("checkpoints").put(JSONObject().put("platform", "HEYBOX").put("accountId", "orphan-three"))
            .put(JSONObject().put("accountId", "orphan-four").put("targetId", "heybox:daily"))
        assertEquals(expected, StateJson.decode(root).state)
    }

    @Test fun retiredAccountReferencesAreRemovedEvenWithoutLegacyPlatformAndTargetFields() {
        val expected = retainedState()
        val root = StateJson.encode(expected)
        root.getJSONArray("accounts").put(legacyAccount())
        root.getJSONArray("records").put(JSONObject().put("accountId", "retired-account").put("platform", "TIEBA"))
        root.getJSONArray("checkpoints").put(JSONObject().put("accountId", "retired-account"))
        assertEquals(expected, StateJson.decode(root).state)
    }

    @Test fun migrationIsIdempotentAndDoesNotMutateInput() {
        val root = addLegacy(StateJson.encode(retainedState()))
        val before = JSONObject(root.toString())
        val first = StateJson.decode(root)
        assertJsonEquals(before, root)
        val second = StateJson.decode(first.migratedRoot!!)
        assertNull(second.migratedRoot)
        assertEquals(first.state, second.state)
        assertNull(StateJson.decode(StateJson.encode(retainedState())).migratedRoot)
    }

    @Test fun rewriteRetainsUnknownFieldsAndDoesNotTruncateExistingHistory() {
        val root = addLegacy(StateJson.encode(retainedState()))
        root.put("futureRootField", JSONObject().put("fixture", 9))
        root.getJSONObject("settings").put("futureSetting", "fixture-value")
        root.getJSONArray("accounts").getJSONObject(0).put("futureAccountField", true)
        val rows = root.getJSONArray("records")
        val sample = rows.getJSONObject(0)
        repeat(3001) { rows.put(JSONObject(sample.toString()).put("id", "extra-$it")) }
        val decoded = StateJson.decode(root)
        val rewritten = decoded.migratedRoot!!
        assertEquals(3006, decoded.state.records.size)
        assertEquals(3006, rewritten.getJSONArray("records").length())
        assertEquals(9, rewritten.getJSONObject("futureRootField").getInt("fixture"))
        assertEquals("fixture-value", rewritten.getJSONObject("settings").getString("futureSetting"))
        assertTrue(rewritten.getJSONArray("accounts").getJSONObject(0).getBoolean("futureAccountField"))
    }

    @Test fun nonRetiredUnknownAccountPlatformStillFailsWithoutSilentlyClearingState() {
        val root = addLegacy(StateJson.encode(retainedState()))
        root.getJSONArray("accounts").getJSONObject(0).put("platform", "FUTURE_PLATFORM")
        assertThrows(IllegalArgumentException::class.java) { StateJson.decode(root) }
        assertEquals(6, root.getJSONArray("accounts").length())
    }

    @Test fun nonRetiredUnknownRecordPlatformStillFailsWithoutSilentlyDroppingHistory() {
        val root = addLegacy(StateJson.encode(retainedState()))
        root.getJSONArray("records").getJSONObject(0).put("platform", "FUTURE_PLATFORM")
        assertThrows(IllegalArgumentException::class.java) { StateJson.decode(root) }
        assertEquals(6, root.getJSONArray("records").length())
    }
}
