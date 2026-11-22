package cn.onetap.checkin.platform

import java.security.MessageDigest
import java.security.SecureRandom

/** Web request signing restored from 2329c95 and checked against its official JavaScript vectors. */
internal object HeyboxWebSigning {
    private const val ALPHABET = "AB45STUVWZEFGJ6CH01D237IXYPQRKLMN89"
    private val random = SecureRandom()

    fun parameters(path: String, nowMillis: Long = System.currentTimeMillis()): Map<String, String> {
        val timestamp = nowMillis / 1_000
        val bytes = ByteArray(16).also(random::nextBytes)
        val nonce = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
        return mapOf("version" to "999.0.4", "_time" to timestamp.toString(),
            "nonce" to nonce, "hkey" to sign(path, timestamp, nonce))
    }

    /** Pure function checked against the downloaded official JavaScript, not another third-party signer. */
    fun sign(path: String, timestamp: Long, nonce: String): String {
        require(path.startsWith('/') && !path.contains('?') && !path.contains('#'))
        val normalized = "/" + path.split('/').filter(String::isNotEmpty).joinToString("/") + "/"
        fun map(value: String, dropLast: Int = 0): String {
            val alphabet = ALPHABET.dropLast(dropLast)
            return value.map { alphabet[it.code % alphabet.length] }.joinToString("")
        }
        val parts = listOf(map((timestamp + 1).toString(), 2), map(normalized), map(nonce))
        val woven = buildString {
            for (index in 0 until parts.maxOf(String::length))
                for (part in parts) if (index < part.length) append(part[index])
        }.take(20)
        val digest = MessageDigest.getInstance("MD5").digest(woven.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val tail = digest.takeLast(6).map(Char::code).toMutableList()
        val a = tail[0]; val b = tail[1]; val c = tail[2]; val d = tail[3]
        tail[0] = mix(a) xor fourth(b) xor second(c) xor first(d)
        tail[1] = first(a) xor mix(b) xor fourth(c) xor second(d)
        tail[2] = second(a) xor first(b) xor mix(c) xor fourth(d)
        tail[3] = fourth(a) xor second(b) xor first(c) xor mix(d)
        return map(digest.take(5), 4) + (tail.sum() % 100).toString().padStart(2, '0')
    }

    private fun double(value: Int): Int = if (value and 128 != 0) ((value shl 1) xor 27) and 255 else value shl 1
    private fun first(value: Int) = double(value) xor value
    private fun second(value: Int) = first(double(value))
    private fun fourth(value: Int) = second(first(double(value)))
    private fun mix(value: Int) = fourth(value) xor second(value) xor first(value)
}
