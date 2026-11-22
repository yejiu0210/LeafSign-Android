package cn.onetap.checkin.platform

import cn.onetap.checkin.core.*
import cn.onetap.checkin.core.Target
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

class NeteaseYunbeiTest {
    private val credential="MUSIC_U=fixture-sensitive; __csrf=fixture-csrf; other=drop"
    private val target=Target(NeteaseAdapter.YUNBEI_ID,NeteaseAdapter.YUNBEI_TYPE,"云贝签到",metadata=mapOf("ownerUserId" to "123"))
    private val identity="""{"code":200,"account":{"id":123},"profile":{"userId":123,"nickname":"测试"}}"""
    private val balancePath = "/v1/user/info"
    private data class Call(val path:String,val eapi:Boolean,val data:JSONObject,val cookie:Map<String,String>)
    private inner class Api(vararg responses:Any, val balanceResponse: String = """{"code":200,"userPoint":{"balance":42}}""") {
        val queue=ArrayDeque(responses.toList())
        val calls=mutableListOf<Call>()
        val waits=mutableListOf<Long>()
        val adapter=NeteaseAdapter(pause=waits::add) {path,data,cookie,eapi->
            calls+=Call(path,eapi,JSONObject(data.toString()),cookie)
            when (path) {
                "/nuser/account/get" -> JSONObject(identity)
                balancePath -> JSONObject(balanceResponse)
                else -> when(val next=queue.removeFirst()) {
                    is Exception -> throw next
                    is RawResponse -> NeteaseAdapter.parseYunbeiChannelResponse(next)
                    else -> JSONObject(next.toString())
                }
            }
        }
        val writes get()=calls.filter {it.path=="/pointmall/user/sign"}
    }
    private fun state(signed:Boolean)="""{"code":200,"data":{"signed":$signed}}"""

    @Test fun targetIsDefaultEnabledForOrdinaryUsersAndAccountConcurrencyIsOne() {
        val api=Api();val actual=api.adapter.validate(credential)
        val task=actual.targets.single {it.id==NeteaseAdapter.YUNBEI_ID}
        assertEquals("yunbei",task.type);assertTrue(task.bound)
        assertEquals("true",task.metadata["defaultEnabled"])
        assertEquals("Asia/Shanghai",task.metadata["resetZone"]);assertEquals("0",task.metadata["resetHour"])
        assertEquals(1,api.adapter.maxTargetConcurrency)
        assertEquals(listOf("/nuser/account/get"),api.calls.map {it.path})
    }

    @Test fun onlyConsistentBooleanFieldsAreReliableAcrossBothContainers() {
        for(field in listOf("signed","sign","isSign","todaySigned")) for(value in listOf(true,false)) for(nested in listOf(true,false)) {
            val body=JSONObject().put("code",200)
            if(nested)body.put("data",JSONObject().put(field,value))else body.put(field,value)
            assertEquals(if(value)TaskStatus.ALREADY else TaskStatus.PENDING,NeteaseAdapter.parseYunbeiState(body)?.status)
        }
        for(raw in listOf("""{"code":200}""","""{"code":200,"data":{"signed":1}}""",
            """{"code":200,"signed":"true"}""","""{"code":200,"signed":true,"data":{"sign":false}}""","""{"code":999}"""))
            assertNull(NeteaseAdapter.parseYunbeiState(JSONObject(raw)))
        assertEquals(TaskStatus.EXPIRED,assertThrows(AdapterException::class.java) {
            NeteaseAdapter.parseYunbeiState(JSONObject("""{"code":301,"message":null}"""))
        }.status)
    }

    @Test fun successfulEapiSignAndConfirmedReadIncludeNumericReward() {
        val api=Api(state(false),"""{"code":200,"data":{"point":5}}""",state(true))
        val result=api.adapter.execute(credential,target)
        assertEquals(TaskStatus.SUCCESS,result.status);assertEquals("云贝签到成功，获得 5 云贝",result.message)
        assertEquals(listOf(Stat("云贝", "42")), result.stats)
        assertEquals(listOf("/nuser/account/get","/point/signed/get","/pointmall/user/sign","/point/signed/get",balancePath),api.calls.map {it.path})
        assertTrue(api.calls.drop(1).dropLast(1).all {it.eapi && it.data.length()==0})
        assertFalse(api.calls.last().eapi)
        assertEquals(0, api.calls.last().data.length())
        assertTrue(api.calls.all {it.cookie.keys==setOf("MUSIC_U","__csrf")})
        assertEquals("code=200",Rules.safeDiagnostic(result.diagnostic))
    }

    @Test fun selfCheckPendingDowngradesAndUnavailableSchemaPreservesAcknowledgedSuccess() {
        for((last,expected) in listOf(state(false) to TaskStatus.UNKNOWN,"""{"code":200,"data":{}}""" to TaskStatus.SUCCESS)) {
            val api=Api("""{"code":200,"data":{}}""","""{"code":200}""",last,last,last,last)
            val result=api.adapter.execute(credential,target)
            assertEquals(expected,result.status);assertEquals(1,api.writes.size)
            assertEquals(listOf(1000L,1500L,2500L),api.waits)
            if(expected==TaskStatus.UNKNOWN)assertEquals("结果未确认，请稍后重试",result.message)
        }
    }

    @Test fun delayedYunbeiStateConfirmationOnlyRepeatsTheRead() {
        val api=Api(state(false),"""{"code":200,"data":{"point":5}}""",state(false),state(false),state(true))
        val result=api.adapter.execute(credential,target)
        assertEquals(TaskStatus.SUCCESS,result.status)
        assertEquals("云贝签到成功，获得 5 云贝",result.message)
        assertEquals(listOf(1000L,1500L),api.waits)
        assertEquals(1,api.writes.size)
        assertEquals(4,api.calls.count { it.path=="/point/signed/get" })
    }

    @Test fun alreadySignedSkipsWriteAndWrongOwnerNeverTouchesYunbeiEndpoints() {
        val api=Api(state(true))
        assertEquals(TaskStatus.ALREADY,api.adapter.execute(credential,target).status);assertTrue(api.writes.isEmpty())
        val wrong=Api()
        assertEquals(TaskStatus.EXPIRED,wrong.adapter.execute(credential,target.copy(metadata=mapOf("ownerUserId" to "456"))).status)
        assertEquals(1,wrong.calls.size)
    }

    @Test fun balanceCandidatesAndMissingFieldsUseOneReadForTheAccount() {
        for ((payload, expected) in listOf(
            """{"code":200,"data":{"userPoint":{"balance":"28"}}}""" to listOf(Stat("云贝", "28")),
            """{"code":200,"pointsBalance":0}""" to listOf(Stat("云贝", "0")),
            """{"code":200,"userPoint":{"balance":true},"data":{"pointsBalance":"private-value"}}""" to emptyList()
        )) {
            val api = Api(state(true), state(true), balanceResponse = payload)
            val result = api.adapter.query(credential, target)!!
            assertEquals(TaskStatus.ALREADY, result.status)
            assertEquals(expected, result.stats)
            assertEquals(if (expected.isEmpty()) "code=200; stats 未识别：字段 balance,pointsBalance" else "code=200",
                result.diagnostic)
            assertEquals(result, api.adapter.execute(credential, target))
            assertEquals(1, api.calls.count { it.path == balancePath })
            assertTrue(api.writes.isEmpty())
        }
    }

    @Test fun businessCodesMapWithoutEchoingMessagesOrCredentialValues() {
        val cases=listOf("""{"code":301,"message":"fixture-sensitive"}""" to TaskStatus.EXPIRED,
            """{"code":-2}""" to TaskStatus.ALREADY,"""{"code":999,"message":"fixture-sensitive 已签到"}""" to TaskStatus.ALREADY,
            """{"code":998,"message":"重复"}""" to TaskStatus.ALREADY,"""{"code":997,"message":"fixture-csrf 验证"}""" to TaskStatus.MANUAL,
            """{"code":996,"message":"风控"}""" to TaskStatus.MANUAL,"""{"code":995,"message":"频繁"}""" to TaskStatus.MANUAL,
            """{"code":994,"message":"fixture-sensitive","data":{"point":"fixture-csrf"}}""" to TaskStatus.UNKNOWN)
        for((raw,status) in cases) {
            val api=Api(state(false),raw);val result=api.adapter.execute(credential,target)
            assertEquals(status,result.status);assertEquals(1,api.writes.size)
            assertFalse((result.message+result.diagnostic).contains("fixture"))
            assertEquals(result.diagnostic,Rules.safeDiagnostic(result.diagnostic))
        }
        for(field in listOf("point","yunbei","score","sign")) {
            val body=JSONObject().put("code",200).put("data",JSONObject().put(field,3))
            assertTrue(NeteaseAdapter.parseYunbeiSign(body).message.contains("获得 3 云贝"))
        }
        assertEquals("云贝签到成功",NeteaseAdapter.parseYunbeiSign(JSONObject("""{"code":200,"data":{"point":"fixture-sensitive","sign":true}}""")).message)
    }

    @Test fun rejectedOrNonJsonChannelFallsBackOnceButBusinessJsonNeverDoes() {
        for(rejection in listOf(RawResponse(200,"<html>fixture-sensitive</html>",emptyList()),RawResponse(503,"unavailable",emptyList()),
            AdapterException(TaskStatus.UNKNOWN,"fixture-sensitive","HTTP 502"))) {
            val api=Api(state(false),rejection,"""{"code":200}""",state(true))
            assertEquals(TaskStatus.SUCCESS,api.adapter.execute(credential,target).status)
            assertEquals(listOf(true,false),api.writes.map {it.eapi})
        }
        val api=Api(state(false),RawResponse(403,"""{"code":301}""",emptyList()))
        assertEquals(TaskStatus.EXPIRED,api.adapter.execute(credential,target).status)
        assertEquals(1,api.writes.size)
    }

    @Test fun readChannelCanFallBackAndBothUnusableWriteChannelsStopAfterTwoAttempts() {
        val read=Api(RawResponse(500,"not-json",emptyList()),state(true))
        assertEquals(TaskStatus.ALREADY,read.adapter.query(credential,target)?.status)
        assertEquals(listOf(true,false),read.calls.filter { it.path == "/point/signed/get" }.map {it.eapi})
        val write=Api(state(false),RawResponse(500,"not-json",emptyList()),RawResponse(200,"also-not-json",emptyList()))
        val error=assertThrows(AdapterException::class.java) {write.adapter.execute(credential,target)}
        assertEquals(TaskStatus.UNKNOWN,error.status);assertEquals(2,write.writes.size)
    }

    @Test fun timedOutWriteNeverBlindlyFallsBackAndPostWriteReadTimeoutDoesNotEscapeAsWriteTimeout() {
        val write=Api(state(false),IOException("fixture-sensitive"))
        assertThrows(IOException::class.java) {write.adapter.execute(credential,target)}
        assertEquals(1,write.writes.size)
        val read=Api(state(false),"""{"code":200}""",*Array(4) { IOException("fixture-sensitive") })
        val result=read.adapter.execute(credential,target)
        assertEquals(TaskStatus.UNKNOWN,result.status);assertEquals(1,read.writes.size)
        assertFalse(result.message.contains("fixture-sensitive"))
    }
}
