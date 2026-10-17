package cn.onetap.checkin.login

/** A single official page's Cookie snapshot; never combines different origin/session jars. */
internal object HeyboxLoginCookies {
    // Read no page content, form fields, storage, or unrelated Cookie values. The caller must
    // first enforce the official HTTPS page allowlist and decode evaluateJavascript's JSON string.
    const val SNAPSHOT_SCRIPT = """(function(){var names=['pkey','user_pkey','heybox_id','user_heybox_id','x_xhh_tokenid'];return document.cookie.split(';').filter(function(part){var eq=part.indexOf('=');return eq>=0&&names.indexOf(part.substring(0,eq).trim())>=0;}).join(';');})()"""

    private val names = setOf("pkey", "user_pkey", "heybox_id", "user_heybox_id", "x_xhh_tokenid")
    private val sentinels = setOf("0", "null", "undefined", "false", "guest")
    private val positiveId = Regex("[1-9][0-9]*")

    /** Null means incomplete or ambiguous, and must not be reported as a server login rejection. */
    fun credential(snapshot: String?): String? {
        if (snapshot.isNullOrEmpty() || snapshot.length > 32_768 || snapshot.any { it.code !in 32..126 }) return null
        val cookies = linkedMapOf<String, String>()
        for (part in snapshot.split(';')) {
            val item = part.trimStart(' ')
            val separator = item.indexOf('=')
            val name = (if (separator < 0) item else item.substring(0, separator)).trim(' ')
            if (name !in names) continue
            if (separator < 0) return null
            val value = item.substring(separator + 1)
            if (value.isEmpty() || value.any { it.code !in 33..126 } || value.lowercase() in sentinels) return null
            if (cookies.containsKey(name) && cookies[name] != value) return null
            cookies[name] = value
        }
        val pkey = coherentAlias(cookies, "pkey", "user_pkey") ?: return null
        val id = coherentAlias(cookies, "heybox_id", "user_heybox_id") ?: return null
        if (!positiveId.matches(id)) return null
        // Imported values already have the official wire escaping. In particular, keep '+',
        // '%3D', and '%25' byte-for-byte rather than URL-decoding and encoding a second time.
        return linkedMapOf("pkey" to pkey, "user_pkey" to pkey, "heybox_id" to id, "user_heybox_id" to id)
            .apply { cookies["x_xhh_tokenid"]?.let { put("x_xhh_tokenid", it) } }
            .entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun coherentAlias(cookies: Map<String, String>, first: String, second: String): String? {
        val primary = cookies[first]
        val alias = cookies[second]
        if (primary != null && alias != null && primary != alias) return null
        return primary ?: alias
    }
}
