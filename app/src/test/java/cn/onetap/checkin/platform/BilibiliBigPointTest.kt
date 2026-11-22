package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class BilibiliBigPointTest {
    private val credential="SESSDATA=fixture-sensitive; bili_jct=fixture-csrf; DedeUserID=123; buvid3=fixture-device; unknown=drop"
    private val target=Target(BilibiliAdapter.BIGPOINT_ID,BilibiliAdapter.BIGPOINT_TYPE,"大积分签到",metadata=mapOf("ownerUserId" to "123"))
    private data class Call(val method:String,val url:String,val body:JSONObject?,val headers:Map<String,String>)
    private inner class Api(vararg responses:Any,val vip:Int=1,val uid:Long=123) {
        val calls=mutableListOf<Call>();val queue=ArrayDeque(responses.toList())
        val adapter=BilibiliAdapter {method,url,body,headers->
            calls+=Call(method,url,body,headers)
            if(url.endsWith("/nav"))JSONObject("""{"code":0,"data":{"isLogin":true,"mid":$uid,"uname":"fixture","vip":{"status":$vip},"level_info":{"current_level":6}}}""")
            else when(val next=queue.removeFirst()) {is Exception->throw next;else->JSONObject(next.toString())}
        }
        val writes get()=calls.filter {it.method=="POST"}
    }
    private fun state(signed:Boolean)="""{"code":0,"data":{"three_day_sign":{"signed":$signed},"big_point":{"point":42}}}"""

    @Test fun newTargetRequiresActiveVipAndLeavesLevelAccelerationTargetIntact() {
        for(vip in listOf(0,1,2,3)) {
            val api=Api(vip=vip);val identity=api.adapter.validate(credential)
            val task=identity.targets.single {it.id==BilibiliAdapter.BIGPOINT_ID}
            assertEquals(vip==1,task.bound);assertEquals("true",task.metadata["defaultEnabled"])
            assertEquals("Asia/Shanghai",task.metadata["resetZone"]);assertEquals("0",task.metadata["resetHour"])
            assertEquals(if(vip==1)"大积分签到" else "大积分签到（需大会员）",task.label)
            assertEquals("vip-level-acceleration",identity.targets.first().id)
            assertTrue(identity.targets.first().bound);assertEquals(1,api.adapter.maxTargetConcurrency)
            assertEquals(1,api.calls.size)
        }
    }

    @Test fun queryUsesOfficialParametersAndOnlyBooleanSignedWithNumericBalance() {
        val api=Api(state(true));val result=api.adapter.query(credential,target)
        assertEquals(TaskStatus.ALREADY,result?.status);assertEquals("今日已签到",result?.message)
        assertEquals(listOf(Stat("大积分", "42")), result?.stats)
        val request=api.calls.last();val url=request.url.toHttpUrl()
        assertEquals("/x/vip/vip_center/sign_in/three_days_sign",url.encodedPath)
        for((key,value) in mapOf("csrf" to "fixture-csrf","mobi_app" to "android","platform" to "android","build" to "8451100","disable_rcmd" to "0"))assertEquals(value,url.queryParameter(key))
        assertEquals(url.queryParameter("t")!!.toLong()/1000,url.queryParameter("ts")!!.toLong())
        assertEquals("https://big.bilibili.com/mobile/bigPoint/task",request.headers["Referer"])
        assertEquals("application/json, text/plain, */*",request.headers["Accept"])
        assertFalse(request.headers.getValue("Cookie").contains("drop"));assertTrue(api.writes.isEmpty())
    }

    @Test fun missingOrNonBooleanQueryStateAndNonzeroCodesNeverImplyAlreadySigned() {
        for(raw in listOf("{}","""{"code":0}""","""{"code":0,"data":{"three_day_sign":{"signed":1}}}""",
            """{"code":0,"data":{"three_day_sign":{"signed":"true"}}}""","""{"code":999,"message":"已签到"}"""))
            assertNull(BilibiliAdapter.parseBigPointState(JSONObject(raw)))
        assertEquals(TaskStatus.EXPIRED,assertThrows(AdapterException::class.java) {
            BilibiliAdapter.parseBigPointState(JSONObject("""{"code":-101}"""))
        }.status)
    }

    @Test fun successfulSignUsesJsonPostAndConfirmsWithFreshRead() {
        val api=Api(state(false),"""{"code":0,"data":{"score":5,"count":2,"duration":3}}""",state(true))
        val result=api.adapter.execute(credential,target)
        assertEquals(TaskStatus.SUCCESS,result.status)
        assertEquals("签到成功，获得 5 积分，连续签到 2/3 天",result.message)
        assertEquals(listOf(Stat("大积分", "42")), result.stats)
        val write=api.writes.single();val url=write.url.toHttpUrl()
        assertEquals("/pgc/activity/score/task/sign2",url.encodedPath)
        assertEquals("android",url.queryParameter("mobi_app"));assertEquals("fixture-csrf",url.queryParameter("csrf"));assertEquals("android",url.queryParameter("platform"))
        assertEquals("application/json",write.headers["Content-Type"]);assertEquals("https://big.bilibili.com/mobile/index",write.headers["Referer"])
        assertEquals("phone",write.body!!.getString("device"));assertEquals(write.body.getLong("t")/1000,write.body.getLong("ts"))
        assertEquals(4,api.calls.size)
    }

    @Test fun explicitPendingSelfCheckDowngradesWhileNullSelfCheckPreservesSuccess() {
        for((last,status) in listOf(state(false) to TaskStatus.UNKNOWN,"""{"code":0,"data":{}}""" to TaskStatus.SUCCESS)) {
            val api=Api("""{"code":0,"data":{}}""","""{"code":0,"data":{"vipScore":8}}""",last)
            val result=api.adapter.execute(credential,target)
            assertEquals(status,result.status);assertEquals(1,api.writes.size)
            if(status==TaskStatus.UNKNOWN)assertEquals("结果未确认，请稍后重试",result.message)
        }
    }

    @Test fun expiredAlreadyIneligibleUnknownAndMissingBusinessDataAreConservativeAndRedacted() {
        for((raw,status) in listOf("""{"code":-101,"message":"fixture-sensitive 已签到"}""" to TaskStatus.EXPIRED,
            """{"code":1,"message":"fixture-sensitive 已签到"}""" to TaskStatus.ALREADY,
            """{"code":2,"message":"已完成"}""" to TaskStatus.ALREADY,
            """{"code":3,"message":"需要大会员 fixture-csrf"}""" to TaskStatus.INELIGIBLE,
            """{"code":4,"message":"fixture-sensitive"}""" to TaskStatus.UNKNOWN,
            """{"code":0}""" to TaskStatus.UNKNOWN,"""{"code":0,"data":{}}""" to TaskStatus.UNKNOWN)) {
            val api=Api(state(false),raw);val result=api.adapter.execute(credential,target)
            assertEquals(status,result.status);assertEquals(1,api.writes.size)
            assertFalse((result.message+result.diagnostic).contains("fixture"))
            assertEquals(result.diagnostic,Rules.safeDiagnostic(result.diagnostic))
        }
        val result=BilibiliAdapter.parseBigPointSign(JSONObject("""{"code":0,"data":{"score":"fixture-sensitive","count":"fixture-csrf"}}"""))
        assertEquals("签到成功",result.message)
    }

    @Test fun alreadySignedNonVipAndWrongIdentityDoNotWrite() {
        val already=Api(state(true));assertEquals(TaskStatus.ALREADY,already.adapter.execute(credential,target).status);assertTrue(already.writes.isEmpty())
        val nonVip=Api(vip=0);assertEquals(TaskStatus.INELIGIBLE,nonVip.adapter.execute(credential,target).status);assertEquals(1,nonVip.calls.size)
        val wrong=Api(uid=456);assertEquals(TaskStatus.EXPIRED,wrong.adapter.execute(credential,target).status);assertEquals(1,wrong.calls.size)
        val unbound=Api();assertEquals(TaskStatus.INELIGIBLE,unbound.adapter.execute(credential,target.copy(bound=false)).status);assertTrue(unbound.calls.isEmpty())
    }

    @Test fun timeoutNeverRepeatsWriteAndPostWriteReadFailureIsNotMisclassifiedAsWriteTimeout() {
        val write=Api(state(false),IOException("fixture-sensitive"))
        assertThrows(IOException::class.java) {write.adapter.execute(credential,target)};assertEquals(1,write.writes.size)
        val read=Api(state(false),"""{"code":0,"data":{"score":5}}""",IOException("fixture-sensitive"))
        val result=read.adapter.execute(credential,target)
        assertEquals(TaskStatus.UNKNOWN,result.status);assertEquals(1,read.writes.size)
        assertFalse(result.message.contains("fixture-sensitive"))
    }
}
