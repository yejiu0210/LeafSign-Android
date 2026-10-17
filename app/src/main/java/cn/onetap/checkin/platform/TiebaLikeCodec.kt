package cn.onetap.checkin.platform

import cn.onetap.checkin.core.AdapterException
import cn.onetap.checkin.core.TaskStatus
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/** CUID from aiotieba a5472bef (Unlicense); opAgree wire fields checked against Android MiniTiebaApi. */
internal object TiebaLikeCodec {
    const val READ_VERSION = "12.64.1.1"
    const val WRITE_VERSION = "22.12.1.0"
    data class Post(val threadId: String, val forumId: String, val forumName: String)

    private fun number(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is String -> if (Regex("-?[0-9]+").matches(value)) value.toLongOrNull() else null
        else -> null
    }
    private fun positive(value: Any?) = number(value)?.takeIf { it > 0 }?.toString()
    private fun identityFailure(field: String, expected: String, actual: Any?, message: String): Nothing {
        val observed = positive(actual) ?: when {
            actual == null || actual == JSONObject.NULL -> "missing"
            actual == "0" || (actual is Number && number(actual) == 0L) -> "zero"
            else -> "invalid"
        }
        throw AdapterException(TaskStatus.UNKNOWN,message,"tieba field=$field expected=${positive(expected) ?: "missing"} actual=$observed")
    }
    /** Only a positive, contradictory public ID can justify rejecting an unwritten candidate. */
    fun explicitIdentityMismatch(error: AdapterException): Boolean {
        if(error.status != TaskStatus.UNKNOWN) return false
        val match=Regex("tieba field=(?:thread_id|forum_id|redundant_fid) expected=([1-9][0-9]{0,18}) actual=([1-9][0-9]{0,18})").matchEntire(error.diagnostic)
            ?: return false
        return match.groupValues[1] != match.groupValues[2]
    }
    fun requireSuccess(body: JSONObject, action: String) {
        val code = number(body.opt("error_code"))
        if (code == 0L) return
        val message = body.optString("error_msg")
        val status = when {
            listOf("验证码", "安全验证", "频繁", "验证失败").any { message.contains(it) } -> TaskStatus.MANUAL
            listOf("未登录", "请先登录", "登录失效", "登录过期", "BDUSS").any { message.contains(it, true) } -> TaskStatus.EXPIRED
            else -> TaskStatus.UNKNOWN
        }
        throw AdapterException(status, when (status) {
            TaskStatus.MANUAL -> "贴吧要求手动验证，已停止点赞，请在官方入口处理"
            TaskStatus.EXPIRED -> "贴吧登录失效，已停止点赞，请更新凭证"
            else -> "贴吧${action}结果无法确认，未继续操作"
        }, "tieba error_code=${code ?: "missing"}")
    }
    private fun checkUser(body: JSONObject, owner: String) {
        val user = body.optJSONObject("user")
        val loggedIn = number(user?.opt("is_login")); val userId = positive(user?.opt("id"))
        if (loggedIn == 0L || (userId != null && userId != owner))
            throw AdapterException(TaskStatus.EXPIRED, "帖子接口未确认当前账号身份，已停止点赞，请重新登录")
        if (loggedIn != 1L || userId == null)
            throw AdapterException(TaskStatus.UNKNOWN, "帖子接口缺少明确的已登录身份，未继续点赞")
    }
    fun posts(body: JSONObject, owner: String, forumId: String, forumName: String): List<Post> {
        requireSuccess(body, "读取候选帖子")
        checkUser(body, owner)
        if (positive(body.optJSONObject("forum")?.opt("id")) != forumId)
            throw AdapterException(TaskStatus.UNKNOWN, "帖子列表的贴吧身份不一致，未选择帖子")
        val array = body.optJSONArray("thread_list")
            ?: throw AdapterException(TaskStatus.UNKNOWN, "贴吧帖子列表结构变化，未选择帖子")
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (listOf("is_ad", "is_tiebaplus_ad", "is_deleted", "is_frs_mask").any { item.has(it) && number(item.opt(it)) != 0L }) return@mapNotNull null
            val id = positive(item.opt("id")) ?: return@mapNotNull null
            val tid = if (item.has("tid")) positive(item.opt("tid")) ?: return@mapNotNull null else id
            if (id != tid) return@mapNotNull null
            if (item.has("fid") && positive(item.opt("fid")) != forumId) return@mapNotNull null
            Post(tid, forumId, forumName)
        }.distinctBy { it.threadId }
    }
    /** Never infer personal state from aggregate agree_num, anonymous pages, or first-floor votes. */
    fun liked(body: JSONObject, owner: String, post: Post): Boolean {
        requireSuccess(body, "读取点赞状态")
        checkUser(body, owner)
        val thread = body.optJSONObject("thread")
        if (positive(thread?.opt("id")) != post.threadId)
            identityFailure("thread_id",post.threadId,thread?.opt("id"),"帖子详情返回的主题帖 ID 与所选帖子不一致或缺失，未继续操作")
        val forumId=body.optJSONObject("forum")?.opt("id")
        if (positive(forumId) != post.forumId)
            identityFailure("forum_id",post.forumId,forumId,"帖子详情返回的贴吧 ID 与所选贴吧不一致或缺失，未继续操作")
        if(thread?.has("fid")==true) {
            val redundant=thread.opt("fid")
            // ThreadInfo's optional proto3 scalar fid defaults to zero. The detail's
            // mandatory top-level forum.id and thread.id above still prove the target.
            val unset=redundant=="0" || (redundant is Number && number(redundant)==0L)
            if(!unset && positive(redundant)!=post.forumId)
                identityFailure("redundant_fid",post.forumId,redundant,"帖子详情的附加贴吧 ID 与主贴吧身份冲突或无法识别，未继续操作")
        }
        val agree = thread?.optJSONObject("agree")
        if (number(agree?.opt("agree_type")) == 5L)
            throw AdapterException(TaskStatus.UNKNOWN, "帖子返回点踩状态，未自动更改该态度")
        return when (number(agree?.opt("has_agree"))) {
            0L -> false
            1L -> true
            else -> throw AdapterException(TaskStatus.UNKNOWN, "帖子未返回明确的本人点赞状态，未继续操作")
        }
    }

    fun readFields(bduss: String) = mapOf("BDUSS" to bduss, "_client_type" to "2", "_client_version" to READ_VERSION)
    fun writeFields(bduss: String, tbs: String, post: Post, cuid: String, undo: Boolean) = mapOf(
        "BDUSS" to bduss, "_client_version" to WRITE_VERSION, "agree_type" to "2", "cuid" to cuid,
        // The wire key is obj_type. aiotieba 50d2bb0c renamed it during a local type refactor;
        // original MiniTiebaApi and maintained Android clients still send obj_type for opAgree.
        "obj_type" to "3", "op_type" to if (undo) "1" else "0", "post_id" to "0", "tbs" to tbs, "thread_id" to post.threadId
    )

    fun newCuid(): String {
        // Like aiotieba Account.android_id: a random protocol identifier, not another app's hardware data.
        val seed = ByteArray(8).apply { SecureRandom().nextBytes(this) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        return cuid(seed)
    }
    fun validCuid(value: String) = Regex("[A-F0-9]{32}\\|V[A-Z2-7]{8}").matches(value)

    /** Port of aiotieba's Unlicense cuid.c. CRC32 uses the JDK and Base32 is the RFC4648 alphabet. */
    fun cuid(randomId: String): String {
        require(Regex("[0-9a-f]{16}").matches(randomId))
        val md5 = MessageDigest.getInstance("MD5").digest(("com.baidu" + randomId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        val src = md5.toByteArray(Charsets.US_ASCII)
        val first = ByteArray(5) { -1 }
        val crc = CRC32().apply { update(src); update(first) }
        var section = (1L shl 40) - 1
        fun update(hash: Long, start: Int, xor: Boolean) {
            val bits = 0xFFFFFFFFL
            val previous = (section ushr start) and bits
            val result = if (xor) previous xor (hash and bits) else previous and (hash and bits)
            section = (section and (bits shl start).inv()) or (result shl start)
        }
        fun bytes() = ByteArray(5) { (section ushr (8 * it)).toByte() }
        update(crc.value, 8, false)
        val second = bytes()
        update(xxHash32(src + first + second).toLong() and 0xFFFFFFFFL, 0, true)
        val third = bytes()
        update(xxHash32(src + first + second + third).toLong() and 0xFFFFFFFFL, 1, true)
        val fourth = bytes()
        crc.update(second); crc.update(third); crc.update(fourth)
        update(crc.value, 7, true)
        val hashBytes = bytes()
        val bits = hashBytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val suffix = (0..7).map { alphabet[((bits ushr (35 - it * 5)) and 31).toInt()] }.joinToString("")
        return "$md5|V$suffix"
    }

    /** xxHash32 seed=0, specified 32-bit overflow; used only for the 40-bit CUID checksum. */
    internal fun xxHash32(input: ByteArray): Int {
        val p1 = 0x9E3779B1.toInt(); val p2 = 0x85EBCA77.toInt(); val p3 = 0xC2B2AE3D.toInt()
        val p4 = 0x27D4EB2F; val p5 = 0x165667B1
        fun rotate(value: Int, bits: Int) = Integer.rotateLeft(value, bits)
        fun word(at: Int) = (input[at].toInt() and 255) or ((input[at+1].toInt() and 255) shl 8) or
            ((input[at+2].toInt() and 255) shl 16) or ((input[at+3].toInt() and 255) shl 24)
        fun round(acc: Int, value: Int) = rotate(acc + value * p2, 13) * p1
        var at = 0
        var hash: Int
        if (input.size >= 16) {
            var v1 = p1 + p2; var v2 = p2; var v3 = 0; var v4 = -p1
            do { v1=round(v1,word(at));v2=round(v2,word(at+4));v3=round(v3,word(at+8));v4=round(v4,word(at+12));at+=16 } while (at <= input.size-16)
            hash = rotate(v1,1) + rotate(v2,7) + rotate(v3,12) + rotate(v4,18)
        } else hash = p5
        hash += input.size
        while (at <= input.size-4) { hash=rotate(hash + word(at)*p3,17)*p4;at+=4 }
        while (at < input.size) { hash=rotate(hash + (input[at].toInt() and 255)*p5,11)*p1;at++ }
        hash = (hash xor (hash ushr 15)) * p2
        hash = (hash xor (hash ushr 13)) * p3
        return hash xor (hash ushr 16)
    }
}
