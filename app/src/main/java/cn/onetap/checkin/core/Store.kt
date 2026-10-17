package cn.onetap.checkin.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import cn.onetap.checkin.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(
    val id: String = UUID.randomUUID().toString(), val platform: Platform,
    val userId: String, val displayName: String, val note: String,
    val credential: String, val targets: List<Target>, val enabled: Set<String>,
    val details: String = "", val checkedAt: Long = System.currentTimeMillis()
)
data class Record(
    val id: String = UUID.randomUUID().toString(), val accountId: String,
    val platform: Platform, val accountLabel: String, val targetId: String,
    val task: String, val period: String, val time: Long = System.currentTimeMillis(),
    val status: TaskStatus, val message: String, val diagnostic: String = "", val experienceState: String = ""
)
data class Settings(val auto: Boolean = false, val hour: Int = 9, val minute: Int = 0, val notifications: Boolean = false, val offerCatchup: Boolean = true, val themeMode: ThemeMode = ThemeMode.SYSTEM)
/** Encrypted continuation state for multi-step actions; never contains platform credentials. */
data class TaskCheckpoint(val accountId: String, val targetId: String, val period: String, val values: Map<String,String>)
data class AppState(val accounts: List<Account> = emptyList(), val records: List<Record> = emptyList(), val settings: Settings = Settings(), val checkpoints: List<TaskCheckpoint> = emptyList())

internal object SettingsJson {
    fun encode(settings: Settings) = JSONObject().apply {
        put("auto", settings.auto); put("hour", settings.hour); put("minute", settings.minute)
        put("notifications", settings.notifications); put("offerCatchup", settings.offerCatchup)
        put("themeMode", settings.themeMode.name)
    }
    fun decode(value: JSONObject) = Settings(
        value.optBoolean("auto"), value.optInt("hour", 9), value.optInt("minute", 0),
        value.optBoolean("notifications"), value.optBoolean("offerCatchup", true),
        ThemeMode.fromStored(value.optString("themeMode"))
    )
}

internal object CheckpointsJson {
    fun encode(items: List<TaskCheckpoint>) = JSONArray().apply { items.forEach { c -> put(JSONObject().apply {
        put("accountId",c.accountId);put("targetId",c.targetId);put("period",c.period);put("values",JSONObject(c.values))
    }) } }
    fun decode(items: JSONArray?): List<TaskCheckpoint> = if(items==null) emptyList() else (0 until items.length()).map { i ->
        val c=items.getJSONObject(i);val values=c.optJSONObject("values")?:JSONObject()
        TaskCheckpoint(c.getString("accountId"),c.getString("targetId"),c.getString("period"),values.keys().asSequence().associateWith {values.getString(it)})
    }
}

object Rules {
    fun removeAccount(state: AppState, accountId: String) = state.copy(
        accounts=state.accounts.filterNot {it.id==accountId},records=state.records.filterNot {it.accountId==accountId},
        checkpoints=state.checkpoints.filterNot {it.accountId==accountId})
    /** Retire the old ordinary sign-in without copying its completion to the member task. */
    fun retireOrdinaryMusicTask(state: AppState): AppState = state.copy(accounts=state.accounts.map { a ->
        if(a.platform!=Platform.NETEASE || a.targets.none {it.id=="netease:daily:android"}) a
        else a.copy(targets=a.targets.filterNot {it.id=="netease:daily:android"},enabled=a.enabled-"netease:daily:android",
            details="已停用旧普通签到。请点「校验 / 刷新」载入黑胶乐签，再勾选启用。旧记录保留，不计为黑胶乐签完成。")
    })
    fun period(target: Target, at: Instant = Instant.now()): String {
        val zone = runCatching { ZoneId.of(target.metadata["resetZone"] ?: "Asia/Shanghai") }.getOrDefault(ZoneId.of("Asia/Shanghai"))
        val hour = target.metadata["resetHour"]?.toLongOrNull()?.coerceIn(0, 23) ?: 0L
        return at.atZone(zone).minusHours(hour).toLocalDate().toString()
    }
    fun completed(status: TaskStatus) = status == TaskStatus.SUCCESS || status == TaskStatus.ALREADY
    fun latest(state: AppState, account: Account, target: Target): Record? = state.records.lastOrNull {
        it.accountId == account.id && it.targetId == target.id && it.period == period(target)
    }
    fun addOrUpdate(state: AppState, identity: Identity, platform: Platform, credential: String, note: String, previousId: String?): AppState {
        require(identity.userId.isNotBlank() && identity.displayName.isNotBlank()) { "平台未返回可辨认的账号身份" }
        val previous = state.accounts.find { it.id == previousId }
        if (previous != null) require(previous.platform == platform && previous.userId == identity.userId) { "新凭证属于另一个账号，请单独添加，未覆盖原账号" }
        require(state.accounts.none { it.id != previousId && it.platform == platform && it.userId == identity.userId }) { "此账号已存在，请更新已有账号，避免把同一账号当成两个账号" }
        val targets = identity.targets.distinctBy { it.id }
        val selection = if (previous == null) targets.filter { it.bound && it.metadata["defaultEnabled"]!="false" }.map { it.id }.toSet()
            else previous.enabled.intersect(targets.filter { it.bound }.map { it.id }.toSet())
        val account = Account(previous?.id ?: UUID.randomUUID().toString(), platform, identity.userId, identity.displayName,
            note.trim().take(40), identity.updatedCredential ?: credential, targets, selection, identity.details)
        return state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account)
    }
    fun safeDiagnostic(value: String): String {
        val tiebaDetail = Regex("tieba endpoint=pb_page reason=(?:thread_unavailable|verification|login|other|missing) thread_id=[1-9][0-9]{0,18} error_code=(?:-?[0-9]{1,10}|missing)")
            .matchEntire(value)?.value
        if (tiebaDetail != null) return tiebaDetail
        val numeric = Regex("(?i)\\b(?:HTTP [1-5][0-9]{2}|(?:code|status|errno|retcode|error_code|experience\\.state)[=: ]+(?:-?[0-9]{1,10}|missing)|exception[=: ]+[A-Za-z]{1,60})\\b")
            .findAll(value).map { it.value }.toList()
        // Only public thread/forum identifiers; account IDs and server text are excluded.
        val tiebaIdentity = Regex("tieba field=(?:thread_id|forum_id|redundant_fid) expected=[1-9][0-9]{0,18} actual=(?:[1-9][0-9]{0,18}|missing|zero|invalid)")
            .matchEntire(value)?.value
        return (numeric+listOfNotNull(tiebaIdentity)).distinct().joinToString("; ").take(240)
    }
    fun resultTitle(status: TaskStatus, targetId: String): String = if(targetId.startsWith("tieba:like:")) when(status) {
        TaskStatus.SUCCESS -> "点赞完成"
        TaskStatus.ALREADY -> "今日点赞任务已完成"
        TaskStatus.PENDING -> "待点赞"
        else -> status.title
    } else status.title
    fun safeMessage(value: String, credential: String): String {
        var text = value
        if (credential.isNotBlank()) text = text.replace(credential, "[已隐藏]")
        credential.split(';', '"', ':', '=', ' ', '\n', ',').filter { it.length >= 8 }.forEach { text = text.replace(it, "[已隐藏]") }
        return text.replace(Regex("(?i)(cookie|token|cred|sign|bduss|sessdata|music_u|authorization)\\s*[:=]\\s*[^ ,;\\n]+"), "$1=[已隐藏]").take(350)
    }
}

/** All state (including credentials) is authenticated-encrypted in noBackupFilesDir. */
interface StateStore {
    fun read(): AppState
    fun update(change: (AppState) -> AppState): AppState
}
class Store(context: Context) : StateStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "state.enc"))
    private val alias = "onetap-state-v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun read(): AppState {
        val bytes = try { file.readFully() } catch (e: FileNotFoundException) {
            val base = file.baseFile
            if (!base.exists() && !File(base.path+".bak").exists() && !File(base.path+".new").exists()) return AppState()
            throw e
        }
        require(bytes.size > 29 && bytes[0] == 1.toByte()) { "无法读取本地加密数据" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1,13)))
        cipher.updateAAD("OneTapCheckIn.v1".toByteArray())
        val decoded = StateJson.decode(JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)), Charsets.UTF_8)))
        // Persist retirement immediately, using the original retained JSON to preserve all other data.
        decoded.migratedRoot?.let(::writeRoot)
        return decoded.state
    }
    @Synchronized override fun update(change: (AppState) -> AppState): AppState {
        val state = change(read())
        writeRoot(StateJson.encode(state))
        return state
    }
    private fun writeRoot(root: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("OneTapCheckIn.v1".toByteArray()) }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

/** JSON migration runs before enum decoding so removed platforms remain safe to upgrade from. */
internal object StateJson {
    data class Decoded(val state: AppState, val migratedRoot: JSONObject?)

    fun encode(state: AppState) = JSONObject().apply {
        put("accounts", JSONArray().apply { state.accounts.forEach { a -> put(JSONObject().apply {
            put("id",a.id);put("platform",a.platform.name);put("userId",a.userId);put("name",a.displayName);put("note",a.note);put("credential",a.credential);put("details",a.details);put("checkedAt",a.checkedAt)
            put("enabled",JSONArray(a.enabled.toList()));put("targets",JSONArray().apply { a.targets.forEach { t -> put(JSONObject().apply {
                put("id",t.id);put("type",t.type);put("label",t.label);put("game",t.game);put("server",t.server);put("bound",t.bound);put("metadata",JSONObject(t.metadata))
            }) } })
        }) } })
        put("records",JSONArray().apply { state.records.takeLast(3000).forEach { r -> put(JSONObject().apply {
            put("id",r.id);put("accountId",r.accountId);put("platform",r.platform.name);put("accountLabel",r.accountLabel);put("targetId",r.targetId);put("task",r.task);put("period",r.period);put("time",r.time);put("status",r.status.name);put("message",r.message);put("diagnostic",r.diagnostic);put("experienceState",r.experienceState)
        }) } })
        put("settings",SettingsJson.encode(state.settings))
        put("checkpoints",CheckpointsJson.encode(state.checkpoints))
    }
    fun decode(saved: JSONObject): Decoded {
        val migrated = retireHeybox(saved)
        val root = migrated ?: saved
        fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
        val accounts = objects(root.optJSONArray("accounts") ?: JSONArray()).map { a ->
            val targets = objects(a.getJSONArray("targets")).map { t ->
                val meta = t.optJSONObject("metadata") ?: JSONObject()
                Target(t.getString("id"),t.getString("type"),t.getString("label"),t.optString("game"),t.optString("server"),t.optBoolean("bound",true),meta.keys().asSequence().associateWith { meta.getString(it) })
            }
            val enabled = a.getJSONArray("enabled")
            Account(a.getString("id"),Platform.valueOf(a.getString("platform")),a.getString("userId"),a.getString("name"),a.optString("note"),a.getString("credential"),targets,(0 until enabled.length()).map { enabled.getString(it) }.toSet(),a.optString("details"),a.optLong("checkedAt"))
        }
        val records = objects(root.optJSONArray("records") ?: JSONArray()).map { r -> Record(r.getString("id"),r.getString("accountId"),Platform.valueOf(r.getString("platform")),r.getString("accountLabel"),r.getString("targetId"),r.getString("task"),r.getString("period"),r.getLong("time"),TaskStatus.valueOf(r.getString("status")),r.getString("message"),r.optString("diagnostic"),r.optString("experienceState")) }
        val s = root.optJSONObject("settings") ?: JSONObject()
        val checkpoints=CheckpointsJson.decode(root.optJSONArray("checkpoints"))
        return Decoded(AppState(accounts, records, SettingsJson.decode(s),checkpoints), migrated)
    }

    private fun retireHeybox(saved: JSONObject): JSONObject? {
        fun objects(key: String) = saved.optJSONArray(key)?.let { items ->
            (0 until items.length()).map { items.getJSONObject(it) }
        }.orEmpty()
        val accounts = objects("accounts")
        val retiredAccounts = accounts.filter { it.optString("platform") == "HEYBOX" }
        val retiredIds = retiredAccounts.mapNotNull { (it.opt("id") as? String)?.takeIf(String::isNotEmpty) }.toSet()
        fun retiredRow(row: JSONObject) = row.optString("platform") == "HEYBOX" ||
            row.optString("accountId") in retiredIds || row.optString("targetId").startsWith("heybox:")
        val records = objects("records")
        val checkpoints = objects("checkpoints")
        val keptRecords = records.filterNot(::retiredRow)
        val keptCheckpoints = checkpoints.filterNot(::retiredRow)
        if (retiredAccounts.isEmpty() && keptRecords.size == records.size && keptCheckpoints.size == checkpoints.size) return null
        // Copy rather than mutating the caller. Unknown fields and every retained row survive unchanged.
        return JSONObject(saved.toString()).apply {
            if (has("accounts")) put("accounts", JSONArray(accounts.filterNot { it.optString("platform") == "HEYBOX" }))
            if (has("records")) put("records", JSONArray(keptRecords))
            if (has("checkpoints")) put("checkpoints", JSONArray(keptCheckpoints))
        }
    }
}
