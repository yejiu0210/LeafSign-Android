package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/** One liked thread per account/forum/local day. Sent writes are checkpointed before network I/O. */
internal class TiebaLikeTask(
    private val store: StateStore?,
    private val request: (String, Map<String,String>) -> JSONObject,
    private val choose: (Int) -> Int = { Random.nextInt(it) },
    private val pace: (String) -> Unit = { },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) {
    companion object {
        const val TARGET_PREFIX = "tieba:like:"
        private const val MAX_REPLACEMENTS = 2
        private const val UNCONFIRMED = "点赞结果未确认，请稍后重试"
        fun target(owner: String, forumId: String, forumName: String, available: Boolean = true) =
            Target("$TARGET_PREFIX$forumId", "daily-like", if(available) "${forumName}吧 · 每日随机点赞一帖" else "每日随机点赞一帖（尚未关注贴吧）", bound=available,
                metadata=mapOf("ownerUserId" to owner, "forumId" to forumId, "forumName" to forumName,
                    "defaultEnabled" to "false", "resetZone" to "Asia/Shanghai", "resetHour" to "0",
                    "resetVerified" to "false", "resetEvidence" to "工具本地每账号每吧每日限一帖；平台精确重置时间未确认"))
    }
    private data class Context(val accountId: String, val target: Target, val period: String)
    private data class Step(val post: TiebaLikeCodec.Post, val stage: String, val likes: Int, val unlikes: Int, val cuid: String,
        val protocol: String = "obj_type", val repairUsed: Boolean = false, val candidateRepairUsed: Boolean = false,
        val replacements: Int = 0, val excludedThreads: Set<String> = emptySet(), val retryRejectedLike: Boolean = false) {
        fun values() = mapOf("threadId" to post.threadId, "forumId" to post.forumId, "forumName" to post.forumName,
            "stage" to stage, "likeAttempts" to likes.toString(), "unlikeAttempts" to unlikes.toString(), "cuid" to cuid,
            "agreeProtocol" to protocol, "repairUsed" to repairUsed.toString(), "candidateRepairUsed" to candidateRepairUsed.toString(),
            "replacementCount" to replacements.toString(), "excludedThreadIds" to excludedThreads.joinToString(","),
            "retryRejectedLike" to retryRejectedLike.toString())
    }
    private fun context(owner: String, target: Target): Context {
        val forumId=target.metadata["forumId"].orEmpty();val name=target.metadata["forumName"].orEmpty()
        if (target.id != "$TARGET_PREFIX$forumId" || forumId.toLongOrNull()?.let {it>0}!=true || name.isBlank() || name.any {it.code<32} ||
            target.type != "daily-like" || target.metadata["ownerUserId"] != owner)
            throw AdapterException(TaskStatus.UNKNOWN, "点赞任务已变化，请在账号页刷新")
        val state = store?.read() ?: throw AdapterException(TaskStatus.UNKNOWN, "暂时无法读取点赞进度")
        val account = state.accounts.singleOrNull { it.platform == Platform.TIEBA && it.userId == owner }
            ?: throw AdapterException(TaskStatus.UNKNOWN, "暂时无法确认账号")
        return Context(account.id, target, Rules.period(target))
    }
    private fun checkpoint(context: Context): Step? {
        val matches = store!!.read().checkpoints.filter { it.accountId==context.accountId && it.targetId==context.target.id && it.period==context.period }
        if (matches.isEmpty()) return null
        if (matches.size != 1) throw AdapterException(TaskStatus.UNKNOWN, "点赞记录异常，请稍后重试")
        val value = matches.single().values
        val threadId = value["threadId"].orEmpty(); val forumId = value["forumId"].orEmpty(); val name=value["forumName"].orEmpty()
        val stage=value["stage"].orEmpty(); val likes=value["likeAttempts"]?.toIntOrNull(); val unlikes=value["unlikeAttempts"]?.toIntOrNull(); val cuid=value["cuid"].orEmpty()
        val protocol=value["agreeProtocol"] ?: "legacy"
        val repair=when(value["repairUsed"]) { null,"false" -> false; "true" -> true; else -> null }
        val candidateRepair=when(value["candidateRepairUsed"]) { null,"false" -> false; "true" -> true; else -> null }
        val replacements=(value["replacementCount"] ?: if(candidateRepair==true) "1" else "0").toIntOrNull()
        val excluded=value["excludedThreadIds"].orEmpty().takeIf { it.isNotEmpty() }?.split(',').orEmpty().toSet()
        val retryRejected=when(value["retryRejectedLike"]) { null,"false" -> false; "true" -> true; else -> null }
        val valid = threadId.toLongOrNull()?.let { it>0 }==true && forumId==context.target.metadata["forumId"] && name.isNotBlank() &&
            protocol in setOf("legacy","obj_type") && repair!=null && candidateRepair!=null && (protocol!="legacy" || !repair) &&
            replacements!=null && replacements in (if(candidateRepair==true) 1 else 0)..MAX_REPLACEMENTS &&
            excluded.size<=replacements && excluded.all { it.toLongOrNull()?.let { id -> id>0 }==true } &&
            retryRejected!=null && (!retryRejected || (stage=="like_sent" && likes==1 && protocol=="obj_type")) &&
            stage in setOf("selected","unlike_sent","like_sent","complete") && likes!=null && likes in 0..(if(repair==true)3 else 2) &&
            unlikes!=null && unlikes in 0..(if(repair==true)2 else 1) && !(likes==3 && unlikes==2) && TiebaLikeCodec.validCuid(cuid) &&
            (stage!="selected" || (likes==0 && unlikes==0 && repair==false)) &&
            (stage!="unlike_sent" || ((unlikes==1 || (repair==true && unlikes==2)) && likes==0)) &&
            (stage !in setOf("like_sent","complete") || (likes!=null && likes>=1))
        if (!valid) throw AdapterException(TaskStatus.UNKNOWN, "暂时无法确认点赞进度")
        return Step(TiebaLikeCodec.Post(threadId,forumId,name),stage,likes!!,unlikes!!,cuid,protocol,repair!!,
            candidateRepair!!,replacements!!,excluded,retryRejected!!)
    }
    private fun save(context: Context, step: Step): Step {
        store!!.update { old ->
            if (old.accounts.none { it.id==context.accountId && it.platform==Platform.TIEBA && it.userId==context.target.metadata["ownerUserId"] })
                throw AdapterException(TaskStatus.UNKNOWN, "账号已变更，请重新登录")
            val checkpoint=TaskCheckpoint(context.accountId,context.target.id,context.period,step.values())
            old.copy(checkpoints=old.checkpoints.filterNot { it.accountId==context.accountId && it.targetId==context.target.id && it.period==context.period }+checkpoint)
        }
        return step
    }
    private fun select(owner: String, bduss: String, forum: Target, excluded: Set<String> = emptySet()): TiebaLikeCodec.Post? {
        val id=forum.metadata.getValue("forumId");val name=forum.metadata.getValue("forumName")
        val body=request("/c/f/frs/page",TiebaLikeCodec.readFields(bduss)+mapOf("kw" to name,"pn" to "0","rn" to "10","rn_need" to "15","sort_type" to "0","is_good" to "0"))
        val posts=TiebaLikeCodec.posts(body,owner,id,name).filterNot {it.threadId in excluded}
        if (posts.isEmpty()) return null
        val start=choose(posts.size)
        for(offset in 0 until minOf(10,posts.size)) {
            val post=posts[(start+offset)%posts.size]
            try {
                if (!read(owner,bduss,post)) return post
            } catch(e: AdapterException) {
                if (!rejectUnwrittenCandidate(e)) throw e
            }
        }
        return null
    }
    private fun read(owner: String, bduss: String, post: TiebaLikeCodec.Post): Boolean = TiebaLikeCodec.liked(
        request("/c/f/pb/page",TiebaLikeCodec.readFields(bduss)+mapOf("kz" to post.threadId,"pn" to "1","rn" to "2","r" to "0","lz" to "0")),owner,post)
    private fun result(status: TaskStatus, message: String) = TaskResult(status,message)
    private fun complete(context: Context, step: Step): TaskResult {
        save(context,step.copy(stage="complete",retryRejectedLike=false))
        return result(TaskStatus.SUCCESS,"${step.post.forumName}吧点赞成功")
    }
    private fun pending() = result(TaskStatus.PENDING,"待点赞")
    private fun unconfirmed(diagnostic: String = "") = TaskResult(TaskStatus.UNKNOWN,UNCONFIRMED,diagnostic)
    private fun noPosts(forum: Target) = result(TaskStatus.UNKNOWN,"${forum.metadata.getValue("forumName")}吧暂无可用帖子，请稍后重试")
    private fun rejectUnwrittenCandidate(error: AdapterException) = TiebaLikeCodec.explicitIdentityMismatch(error) ||
        TiebaLikeCodec.unavailableCandidate(error) || TiebaLikeCodec.unidentifiedForumCandidate(error) ||
        TiebaLikeCodec.dislikedCandidate(error)
    private fun canReplaceRejectedCandidate(step: Step, error: AdapterException) = step.stage=="selected" &&
        !step.candidateRepairUsed && rejectUnwrittenCandidate(error)

    private fun canRetryLike(context: Context, step: Step) = step.retryRejectedLike || canRepair(context,step)

    /** One migration write only for a recorded, explicit legacy rejection, never an unknown timeout. */
    private fun canRepair(context: Context, step: Step): Boolean {
        if(step.protocol!="legacy" || step.repairUsed || step.stage!="like_sent" || step.likes!=2) return false
        val previous=store!!.read().records.lastOrNull { it.accountId==context.accountId && it.platform==Platform.TIEBA &&
            it.targetId==context.target.id && it.period==context.period && it.status!=TaskStatus.RUNNING } ?: return false
        return previous.status==TaskStatus.UNKNOWN && Regex("(?:^|[;\\s])error_code=300000(?:$|[;\\s])").containsMatchIn(previous.diagnostic)
    }

    fun query(owner: String, bduss: String, target: Target): TaskResult? {
        val context=context(owner,target)
        val step=checkpoint(context) ?: return null
        if (step.stage=="complete") return result(TaskStatus.ALREADY,"今日已点赞")
        val liked=try { read(owner,bduss,step.post) } catch(e: AdapterException) {
            if(canReplaceRejectedCandidate(step,e)) return if(step.replacements<MAX_REPLACEMENTS) pending() else unconfirmed(e.diagnostic)
            throw e
        }
        return when(step.stage) {
            "selected" -> pending()
            "unlike_sent" -> pending()
            "like_sent" -> when {
                liked -> complete(context,step)
                canRetryLike(context,step) -> pending()
                else -> unconfirmed()
            }
            else -> unconfirmed()
        }
    }
    fun execute(owner: String, bduss: String, tbs: String, target: Target, forums: List<Target>): TaskResult {
        val context=context(owner,target)
        val previous=checkpoint(context)
        if (previous?.stage=="complete") return result(TaskStatus.ALREADY,"今日已点赞")
        val forum=forums.singleOrNull { it.type=="forum" && it.bound && it.id=="tieba:forum:${target.metadata["forumId"]}" &&
            it.metadata["forumId"]==target.metadata["forumId"] && it.metadata["ownerUserId"]==owner }
            ?: return result(TaskStatus.UNKNOWN,"你已取消关注此吧")
        if (forum.metadata["forumName"].isNullOrBlank()) return result(TaskStatus.UNKNOWN,"吧名有变化，请在账号页刷新")
        var step=previous ?: run {
            val post=select(owner,bduss,forum) ?: return noPosts(forum)
            save(context,Step(post,"selected",0,0,TiebaLikeCodec.newCuid()))
        }
        while (true) {
            val liked=try { read(owner,bduss,step.post) } catch(e: AdapterException) {
                if(!canReplaceRejectedCandidate(step,e)) throw e
                null
            }
            if (step.stage=="like_sent") {
                if (liked==true) return complete(context,step)
                if (!canRetryLike(context,step)) return unconfirmed()
                break
            }
            // An unliked legacy thread gets its original like restored.
            if (liked==false) break
            if (step.replacements>=MAX_REPLACEMENTS) return unconfirmed()
            // Keep the old thread and stage until a replacement is verified.
            step=save(context,step.copy(replacements=step.replacements+1,
                excludedThreads=step.excludedThreads+step.post.threadId, candidateRepairUsed=step.candidateRepairUsed || liked==null))
            val post=select(owner,bduss,forum,step.excludedThreads) ?: return noPosts(forum)
            step=save(context,Step(post,"selected",0,0,TiebaLikeCodec.newCuid(),candidateRepairUsed=step.candidateRepairUsed,
                replacements=step.replacements,excludedThreads=step.excludedThreads))
        }
        pace(owner)
        step=save(context,step.copy(stage="like_sent",likes=step.likes+1,protocol="obj_type",
            repairUsed=step.repairUsed || step.likes>=2,retryRejectedLike=false))
        val writeDiagnostic=try {
            TiebaLikeCodec.requireSuccess(request("/c/c/agree/opAgree",TiebaLikeCodec.writeFields(bduss,tbs,step.post,step.cuid)),"点赞")
            ""
        } catch(e: IOException) {
            if (Thread.currentThread().isInterrupted) throw e
            "exception=${e.javaClass.simpleName}"
        } catch(e: AdapterException) {
            if (e is HttpStatusException && e.isTransient && !Thread.currentThread().isInterrupted) e.diagnostic
            else {
                if (step.likes==1 && e.status==TaskStatus.UNKNOWN && e.diagnostic=="tieba error_code=300000")
                    save(context,step.copy(retryRejectedLike=true))
                throw e
            }
        }
        var diagnostic=writeDiagnostic
        for (delay in listOf(0L,800L,1500L)) {
            if (delay>0) pause(delay)
            val liked=try {
                read(owner,bduss,step.post)
            } catch(e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                if (diagnostic.isEmpty()) diagnostic="exception=${e.javaClass.simpleName}"
                false
            } catch(e: AdapterException) {
                if (e.status!=TaskStatus.UNKNOWN) throw e
                if (diagnostic.isEmpty()) diagnostic=e.diagnostic
                false
            }
            if (liked) return complete(context,step)
        }
        return unconfirmed(diagnostic)
    }
}
