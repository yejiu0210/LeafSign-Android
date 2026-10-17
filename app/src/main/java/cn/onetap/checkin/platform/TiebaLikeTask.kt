package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import kotlin.random.Random

/** One fixed thread per account/forum/local day. Sent writes are checkpointed before network I/O. */
internal class TiebaLikeTask(
    private val store: StateStore?,
    private val request: (String, Map<String,String>) -> JSONObject,
    private val choose: (Int) -> Int = { Random.nextInt(it) },
    private val pace: () -> Unit = { }
) {
    companion object {
        const val TARGET_PREFIX = "tieba:like:"
        private const val EXPERIENCE = "经验待官方确认"
        fun target(owner: String, forumId: String, forumName: String, available: Boolean = true) =
            Target("$TARGET_PREFIX$forumId", "daily-like", if(available) "${forumName}吧 · 每日随机点赞一帖" else "每日随机点赞一帖（尚未关注贴吧）", bound=available,
                metadata=mapOf("ownerUserId" to owner, "forumId" to forumId, "forumName" to forumName,
                    "defaultEnabled" to "false", "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                    "resetVerified" to "false", "resetEvidence" to "工具本地每账号每吧每日限一帖；平台经验和精确重置时间未确认"))
    }
    private data class Context(val accountId: String, val target: Target, val period: String)
    private data class Step(val post: TiebaLikeCodec.Post, val stage: String, val likes: Int, val unlikes: Int, val cuid: String,
        val protocol: String = "obj_type", val repairUsed: Boolean = false, val candidateRepairUsed: Boolean = false) {
        fun values() = mapOf("threadId" to post.threadId, "forumId" to post.forumId, "forumName" to post.forumName,
            "stage" to stage, "likeAttempts" to likes.toString(), "unlikeAttempts" to unlikes.toString(), "cuid" to cuid,
            "agreeProtocol" to protocol, "repairUsed" to repairUsed.toString(), "candidateRepairUsed" to candidateRepairUsed.toString())
    }
    private fun context(owner: String, target: Target): Context {
        val forumId=target.metadata["forumId"].orEmpty();val name=target.metadata["forumName"].orEmpty()
        if (target.id != "$TARGET_PREFIX$forumId" || forumId.toLongOrNull()?.let {it>0}!=true || name.isBlank() || name.any {it.code<32} ||
            target.type != "daily-like" || target.metadata["ownerUserId"] != owner)
            throw AdapterException(TaskStatus.UNKNOWN, "每日点赞任务身份不一致，请刷新账号")
        val state = store?.read() ?: throw AdapterException(TaskStatus.UNKNOWN, "每日点赞缺少安全续传存储，已停止")
        val account = state.accounts.singleOrNull { it.platform == Platform.TIEBA && it.userId == owner }
            ?: throw AdapterException(TaskStatus.UNKNOWN, "每日点赞找不到唯一的本地账号，已停止")
        return Context(account.id, target, Rules.period(target))
    }
    private fun checkpoint(context: Context): Step? {
        val matches = store!!.read().checkpoints.filter { it.accountId==context.accountId && it.targetId==context.target.id && it.period==context.period }
        if (matches.isEmpty()) return null
        if (matches.size != 1) throw AdapterException(TaskStatus.UNKNOWN, "每日点赞续传记录重复，已停止")
        val value = matches.single().values
        val threadId = value["threadId"].orEmpty(); val forumId = value["forumId"].orEmpty(); val name=value["forumName"].orEmpty()
        val stage=value["stage"].orEmpty(); val likes=value["likeAttempts"]?.toIntOrNull(); val unlikes=value["unlikeAttempts"]?.toIntOrNull(); val cuid=value["cuid"].orEmpty()
        val protocol=value["agreeProtocol"] ?: "legacy"
        val repair=when(value["repairUsed"]) { null,"false" -> false; "true" -> true; else -> null }
        val candidateRepair=when(value["candidateRepairUsed"]) { null,"false" -> false; "true" -> true; else -> null }
        val valid = threadId.toLongOrNull()?.let { it>0 }==true && forumId==context.target.metadata["forumId"] && name.isNotBlank() &&
            protocol in setOf("legacy","obj_type") && repair!=null && candidateRepair!=null && (protocol!="legacy" || !repair) &&
            stage in setOf("selected","unlike_sent","like_sent","complete") && likes!=null && likes in 0..(if(repair==true)3 else 2) &&
            unlikes!=null && unlikes in 0..(if(repair==true)2 else 1) && !(likes==3 && unlikes==2) && TiebaLikeCodec.validCuid(cuid) &&
            (stage!="selected" || (likes==0 && unlikes==0 && repair==false)) &&
            (stage!="unlike_sent" || ((unlikes==1 || (repair==true && unlikes==2)) && likes==0)) &&
            (stage !in setOf("like_sent","complete") || (likes!=null && likes>=1))
        if (!valid) throw AdapterException(TaskStatus.UNKNOWN, "每日点赞续传记录无法核对，未重新选择或操作其他帖子")
        return Step(TiebaLikeCodec.Post(threadId,forumId,name),stage,likes!!,unlikes!!,cuid,protocol,repair!!,candidateRepair!!)
    }
    private fun save(context: Context, step: Step): Step {
        store!!.update { old ->
            if (old.accounts.none { it.id==context.accountId && it.platform==Platform.TIEBA && it.userId==context.target.metadata["ownerUserId"] })
                throw AdapterException(TaskStatus.UNKNOWN, "本地账号已变更，未执行点赞")
            val checkpoint=TaskCheckpoint(context.accountId,context.target.id,context.period,step.values())
            old.copy(checkpoints=old.checkpoints.filterNot { it.accountId==context.accountId && it.targetId==context.target.id && it.period==context.period }+checkpoint)
        }
        return step
    }
    private fun select(owner: String, bduss: String, forum: Target, excluded: Set<String> = emptySet()): Step {
        val id=forum.metadata.getValue("forumId");val name=forum.metadata.getValue("forumName")
        val body=request("/c/f/frs/page",TiebaLikeCodec.readFields(bduss)+mapOf("kw" to name,"pn" to "0","rn" to "10","rn_need" to "15","sort_type" to "0","is_good" to "0"))
        val posts=TiebaLikeCodec.posts(body,owner,id,name).filterNot {it.threadId in excluded}
        if (posts.isNotEmpty()) {
            val start=choose(posts.size)
            var mismatch: AdapterException?=null
            for(offset in 0 until minOf(3,posts.size)) {
                val post=posts[(start+offset)%posts.size]
                try { read(owner,bduss,post) } catch(e: AdapterException) {
                    if(!TiebaLikeCodec.explicitIdentityMismatch(e)) throw e
                    mismatch=e
                    continue
                }
                return Step(post,"selected",0,0,TiebaLikeCodec.newCuid())
            }
            throw mismatch!!
        }
        throw AdapterException(TaskStatus.UNKNOWN,"${name}吧本次没有可用帖子，未转到其他吧点赞；可稍后重试")
    }
    private fun read(owner: String, bduss: String, post: TiebaLikeCodec.Post): Boolean = TiebaLikeCodec.liked(
        request("/c/f/pb/page",TiebaLikeCodec.readFields(bduss)+mapOf("kz" to post.threadId,"pn" to "1","rn" to "2","r" to "0","lz" to "0")),owner,post)
    private fun result(status: TaskStatus, message: String) = TaskResult(status,message,experienceState=EXPERIENCE)
    private fun complete(context: Context, step: Step): TaskResult {
        save(context,step.copy(stage="complete"))
        return result(TaskStatus.SUCCESS,"已确认${step.post.forumName}吧帖子 ${step.post.threadId} 点赞生效；此吧今日仅处理这一帖")
    }
    private fun pending() = result(TaskStatus.PENDING,"当天所选帖子尚需执行剩余点赞步骤")
    private fun canReplaceCandidate(step: Step, error: AdapterException) = step.stage=="selected" && step.likes==0 && step.unlikes==0 &&
        !step.candidateRepairUsed && TiebaLikeCodec.explicitIdentityMismatch(error)
    /** One migration write only for a recorded, explicit legacy rejection, never an unknown timeout. */
    private fun canRepair(context: Context, step: Step): Boolean {
        if(step.protocol!="legacy" || step.repairUsed || !((step.stage=="like_sent" && step.likes==2) ||
            (step.stage=="unlike_sent" && step.unlikes==1))) return false
        val previous=store!!.read().records.lastOrNull { it.accountId==context.accountId && it.platform==Platform.TIEBA &&
            it.targetId==context.target.id && it.period==context.period && it.status!=TaskStatus.RUNNING } ?: return false
        return previous.status==TaskStatus.UNKNOWN && Regex("(?:^|[;\\s])error_code=300000(?:$|[;\\s])").containsMatchIn(previous.diagnostic)
    }

    fun query(owner: String, bduss: String, target: Target): TaskResult? {
        val context=context(owner,target)
        val step=checkpoint(context) ?: return null
        if (step.stage=="complete") return result(TaskStatus.ALREADY,"本地记录确认此吧今日帖子 ${step.post.threadId} 点赞任务已完成，未重复取消或点赞")
        val liked=try { read(owner,bduss,step.post) } catch(e: AdapterException) {
            if(canReplaceCandidate(step,e)) return pending()
            throw e
        }
        return when(step.stage) {
            "selected" -> pending()
            "unlike_sent" -> if (!liked || canRepair(context,step)) pending() else result(TaskStatus.UNKNOWN,"取消点赞是否生效仍待确认；未再次取消或更换帖子")
            "like_sent" -> when {
                liked -> complete(context,step)
                step.likes<2 || canRepair(context,step) -> pending()
                else -> result(TaskStatus.UNKNOWN,"同帖两次有限点赞尝试均未确认，已停止；请在贴吧查看")
            }
            else -> result(TaskStatus.UNKNOWN,"当天点赞状态待确认")
        }
    }
    fun execute(owner: String, bduss: String, tbs: String, target: Target, forums: List<Target>): TaskResult {
        val context=context(owner,target)
        val previous=checkpoint(context)
        if (previous?.stage=="complete") return result(TaskStatus.ALREADY,"本地记录确认此吧今日帖子 ${previous.post.threadId} 点赞任务已完成，未重复取消或点赞")
        val forum=forums.singleOrNull { it.type=="forum" && it.bound && it.id=="tieba:forum:${target.metadata["forumId"]}" &&
            it.metadata["forumId"]==target.metadata["forumId"] && it.metadata["ownerUserId"]==owner }
            ?: return result(TaskStatus.UNKNOWN,"此吧已不在当前关注范围，未转到其他吧或继续点赞")
        if (forum.metadata["forumName"].isNullOrBlank()) return result(TaskStatus.UNKNOWN,"此吧名称缺失，未选择或操作帖子")
        var step=previous ?: save(context,select(owner,bduss,forum))
        var liked=try { read(owner,bduss,step.post) } catch(e: AdapterException) {
            if(!canReplaceCandidate(step,e)) throw e
            // No write has been sent. Persist the single repair budget before even
            // searching; an interruption or failed search cannot reset this limit.
            step=save(context,step.copy(candidateRepairUsed=true))
            step=save(context,select(owner,bduss,forum,setOf(step.post.threadId)).copy(candidateRepairUsed=true))
            read(owner,bduss,step.post)
        }
        if (step.stage=="like_sent") {
            if (liked) return complete(context,step)
            if (step.likes>=2 && !canRepair(context,step)) return result(TaskStatus.UNKNOWN,"同帖已达到有限点赞尝试上限，未继续写入；请在贴吧查看")
        }
        if (step.stage=="unlike_sent" && liked) {
            if(!canRepair(context,step)) return result(TaskStatus.UNKNOWN,"取消点赞结果仍待确认，未再次取消或更换帖子")
            pace()
            step=save(context,step.copy(unlikes=step.unlikes+1,protocol="obj_type",repairUsed=true))
            TiebaLikeCodec.requireSuccess(request("/c/c/agree/opAgree",TiebaLikeCodec.writeFields(bduss,tbs,step.post,step.cuid,true)),"取消点赞")
            liked=read(owner,bduss,step.post)
            if(liked) return result(TaskStatus.UNKNOWN,"修正协议取消点赞后仍未确认，已用完一次修复机会；未继续写入")
        }
        if (step.stage=="selected" && liked) {
            pace()
            step=save(context,step.copy(stage="unlike_sent",unlikes=1,protocol="obj_type"))
            TiebaLikeCodec.requireSuccess(request("/c/c/agree/opAgree",TiebaLikeCodec.writeFields(bduss,tbs,step.post,step.cuid,true)),"取消点赞")
            liked=read(owner,bduss,step.post)
            if (liked) return result(TaskStatus.UNKNOWN,"取消点赞尚未确认，未再次取消或继续点赞")
        }
        // This can follow a confirmed unliked state or one failed same-thread like attempt.
        pace()
        step=save(context,step.copy(stage="like_sent",likes=step.likes+1,protocol="obj_type",repairUsed=step.repairUsed || step.likes>=2))
        TiebaLikeCodec.requireSuccess(request("/c/c/agree/opAgree",TiebaLikeCodec.writeFields(bduss,tbs,step.post,step.cuid,false)),"点赞")
        return if (read(owner,bduss,step.post)) complete(context,step)
            else result(TaskStatus.UNKNOWN,"点赞写入后尚未确认状态；保留当天同一帖子，稍后查询再决定是否重试")
    }
}
