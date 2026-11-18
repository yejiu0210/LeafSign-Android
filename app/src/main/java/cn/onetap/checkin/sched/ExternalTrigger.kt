package cn.onetap.checkin.sched

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom

/** Exported entry points never trust a caller-supplied source without the app-private token. */
internal fun trustedTriggerSource(source: String?, suppliedToken: String?, expectedToken: String): String {
    val validToken = expectedToken.isNotBlank() && suppliedToken != null &&
        MessageDigest.isEqual(expectedToken.toByteArray(Charsets.UTF_8), suppliedToken.toByteArray(Charsets.UTF_8))
    return if (validToken && source in setOf("alarm", "test", "catchup", "backup", "shizuku", RETRY_SOURCE)) source!! else "shizuku"
}

internal object ExternalTrigger {
    const val EXTRA_TOKEN = "cn.onetap.checkin.INTERNAL_TRIGGER_TOKEN"
    private var cachedToken: String? = null

    @Synchronized private fun token(context: Context): String {
        cachedToken?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("sched_trigger", Context.MODE_PRIVATE)
        val saved = prefs.getString("token", null)
        val value = saved?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: ByteArray(32).let { bytes ->
            SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it.toInt() and 255) }.also {
                prefs.edit().putString("token", it).apply()
            }
        }
        cachedToken = value
        return value
    }

    fun internalIntent(context: Context, intent: Intent, source: String): Intent =
        intent.putExtra(Scheduler.EXTRA_SOURCE, source).putExtra(EXTRA_TOKEN, token(context))

    fun source(context: Context, intent: Intent?): String = try {
        trustedTriggerSource(intent?.getStringExtra(Scheduler.EXTRA_SOURCE), intent?.getStringExtra(EXTRA_TOKEN), token(context))
    } catch (_: Exception) { "shizuku" } // Malformed extras cannot turn into a privileged test trigger.
}
