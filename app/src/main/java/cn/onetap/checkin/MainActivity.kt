package cn.onetap.checkin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import cn.onetap.checkin.core.*
import cn.onetap.checkin.login.LoginResultBroker
import cn.onetap.checkin.login.WebLoginActivity
import cn.onetap.checkin.theme.LocalThemePalette
import cn.onetap.checkin.theme.OneTapTheme
import cn.onetap.checkin.theme.ThemeMode
import cn.onetap.checkin.theme.ThemePalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as CheckInApp
            val state by app.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = state.settings.themeMode.isDark(systemDark)
            val colors = ThemePalette.resolve(state.settings.themeMode, systemDark)
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(colors.background))
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colors.background
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = colors.background
                }
            }
            OneTapTheme(state.settings.themeMode) {
                AppScreen(app) { if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        (application as CheckInApp).clockTick.value=System.currentTimeMillis()
    }
}

private val pad=Modifier.fillMaxWidth().padding(horizontal=18.dp)
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AppScreen(app: CheckInApp,onNotification: ()->Unit) {
    val state by app.state.collectAsStateWithLifecycle()
    val busy by app.busy.collectAsStateWithLifecycle()
    val progress by app.progress.collectAsStateWithLifecycle()
    val clockTick by app.clockTick.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while(true){ delay(60_000);app.clockTick.value=System.currentTimeMillis() } }
    var tab by remember { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing=state.accounts.firstOrNull { it.id==editingId }
    var error by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Account?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    val labels=listOf("今日","账号","记录","设置")
    fun mutate(action: (AppState)->AppState) { app.scope.launch { Runner.mutex.withLock { try { app.store.update(action);app.refresh() } catch(_:Exception){error="保存本地数据失败，请检查设备存储和密钥状态"} } } }
    if(app.storageError.isNotEmpty()) { Surface(Modifier.fillMaxSize()) { Text(app.storageError,Modifier.padding(30.dp)) };return }
    Scaffold(bottomBar={ NavigationBar { labels.forEachIndexed { i,label -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("✓","人","≡","⚙")[i])},label={Text(label)}) } } }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(10.dp))
            Text(if(tab==0)"一键签到" else labels[tab],pad,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            if(busy||inspecting) { LinearProgressIndicator(pad);Text(if(inspecting)"正在联系官方平台校验身份…" else progress.ifBlank {"准备执行…"},pad,style=MaterialTheme.typography.bodySmall) }
            when(tab) {
                0 -> {
                    val all=remember(state,clockTick){state.accounts.flatMap { a -> a.targets.filter { it.bound&&it.id in a.enabled }.map { a to it } }}
                    val count=all.count { (a,t)->Rules.completed(Rules.latest(state,a,t)?.status?:TaskStatus.PENDING) }
                    Card(pad) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("今天 $count / ${all.size} 项已完成",style=MaterialTheme.typography.titleLarge)
                        Text("按平台、账号和角色分别记录。今日按北京时间暂定，服务端查询优先。未绑定游戏不计入任务。",style=MaterialTheme.typography.bodySmall)
                        Button(onClick={app.scope.launch { app.execute();app.notifyResult() }},enabled=!busy&&!inspecting&&all.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text(if(busy)"正在签到…" else "一键签到") }
                        if(state.settings.offerCatchup && all.any { (a,t)->Rules.latest(state,a,t)==null }) Text("发现今日未完成任务，点击一键签到可补跑今天。",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={app.scope.launch {app.execute(true)}},enabled=!busy&&state.records.any {it.status in setOf(TaskStatus.FAILED,TaskStatus.UNKNOWN,TaskStatus.INTERRUPTED)}){Text("仅重试失败 / 待确认项")}
                    } }
                    Platform.entries.forEach { platform ->
                        Card(pad) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(platform.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            if(platform==Platform.BILIBILI)Text("大会员等级加速包签到",color=MaterialTheme.colorScheme.primary)
                            val accounts=state.accounts.filter {it.platform==platform}
                            if(accounts.isEmpty())Text("尚未添加账号",style=MaterialTheme.typography.bodyMedium)
                            accounts.forEach { a ->
                                Text("${a.note.ifBlank { a.displayName }} · ${a.displayName}",fontWeight=FontWeight.SemiBold)
                                if(a.credential.isBlank())Text("登录失效 · 已退出本地授权")
                                a.targets.forEach { t ->
                                    val r=Rules.latest(state,a,t)
                                    val status=if(!t.bound)TaskStatus.UNBOUND else r?.status?:TaskStatus.PENDING
                                    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        Text(listOf(t.game,t.label,t.server).filter{it.isNotBlank()}.distinct().joinToString(" · "))
                                        Text(if(t.bound&&t.id !in a.enabled)"已停用" else Rules.resultTitle(status,t.id),color=statusColor(status),style=MaterialTheme.typography.labelLarge)
                                        if(r!=null&&t.id in a.enabled){Text(r.message,style=MaterialTheme.typography.bodySmall);if(r.experienceState.isNotBlank())Text("经验：${r.experienceState}",style=MaterialTheme.typography.bodySmall)}
                                    }
                                }
                            }
                        } }
                    }
                    Text("接入依据已查阅，真实账号验证需在本机添加账号后完成。所有结果来自平台业务响应；未知返回保留为待确认。",pad,style=MaterialTheme.typography.bodySmall)
                }
                1 -> {
                    Button(onClick={editingId=null;adding=true},enabled=!busy&&!inspecting,modifier=pad){Text("添加账号")}
                    Text("森空岛主账号和分身账号在此分别授权。相同平台身份不能重复添加。",pad,style=MaterialTheme.typography.bodySmall)
                    state.accounts.forEach { a ->
                        Card(pad){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("${a.platform.title} · ${a.note.ifBlank { a.displayName }}",fontWeight=FontWeight.Bold)
                            Text("${a.displayName} · ID ${a.userId}")
                            Text("身份校验：${formatTime(a.checkedAt)}",style=MaterialTheme.typography.bodySmall)
                            if(a.details.isNotBlank())Text(a.details,style=MaterialTheme.typography.bodySmall)
                            if(a.platform==Platform.TIEBA){
                                Text("每个吧的随机点赞可单独勾选，默认关闭；每吧每天各选一帖，已赞则取消一次再赞。经验由贴吧判定。更新后先校验 / 刷新以载入。",style=MaterialTheme.typography.bodySmall)
                                FlowRow {
                                    TextButton(onClick={mutate{s->s.copy(accounts=s.accounts.map{if(it.id==a.id)it.copy(enabled=it.enabled+it.targets.filter{t->t.bound&&t.type=="forum"}.map{t->t.id})else it})}},enabled=!busy){Text("全选贴吧")}
                                    TextButton(onClick={mutate{s->s.copy(accounts=s.accounts.map{if(it.id==a.id)it.copy(enabled=it.enabled-it.targets.filter{t->t.type=="forum"}.map{t->t.id}.toSet())else it})}},enabled=!busy){Text("取消全部贴吧")}
                                    TextButton(onClick={mutate{s->s.copy(accounts=s.accounts.map{if(it.id==a.id)it.copy(enabled=it.enabled+it.targets.filter{t->t.bound&&t.type=="daily-like"}.map{t->t.id})else it})}},enabled=!busy&&a.credential.isNotBlank()){Text("全选随机一赞")}
                                    TextButton(onClick={mutate{s->s.copy(accounts=s.accounts.map{if(it.id==a.id)it.copy(enabled=it.enabled-it.targets.filter{t->t.type=="daily-like"}.map{t->t.id}.toSet())else it})}},enabled=!busy){Text("取消全部随机一赞")}
                                }
                            }
                            a.targets.forEach { t -> Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked=t.id in a.enabled,onCheckedChange={v->mutate{s->s.copy(accounts=s.accounts.map{if(it.id==a.id)it.copy(enabled=if(v)it.enabled+t.id else it.enabled-t.id)else it})}},enabled=!busy&&t.bound&&a.credential.isNotBlank())
                                Text(listOf(t.game,t.label,t.server,if(!t.bound)"未绑定" else "").filter{it.isNotBlank()}.distinct().joinToString(" · "),style=MaterialTheme.typography.bodyMedium)
                            } }
                            Row { TextButton(onClick={
                                inspecting=true;app.scope.launch { Runner.mutex.withLock {try{
                                    val id=app.adapters.getValue(a.platform).validate(a.credential)
                                    app.store.update {Rules.addOrUpdate(it,id,a.platform,a.credential,a.note,a.id)};app.refresh();error="身份校验通过，已刷新可选角色 / 贴吧"
                                }catch(e:Exception){error=validationError(e,a.credential)}finally{inspecting=false}} }
                            },enabled=!busy&&!inspecting&&a.credential.isNotBlank()){Text("校验 / 刷新")};TextButton(onClick={editingId=a.id;adding=true},enabled=!busy&&!inspecting){Text("备注 / 更新凭证")}}
                            Row {TextButton(onClick={mutate{s->s.copy(accounts=s.accounts.map{if(it.id==a.id)it.copy(credential="",enabled=emptySet())else it})}},enabled=!busy&&!inspecting){Text("退出本地授权")};TextButton(onClick={delete=a},enabled=!busy&&!inspecting){Text("删除账号和记录",color=MaterialTheme.colorScheme.error)}}
                        }}
                    }
                }
                2 -> {
                    Text("最近 ${state.records.size} 条 · 最多保留 3000 条加密本地记录",pad,style=MaterialTheme.typography.bodySmall)
                    if(state.records.isEmpty())Text("还没有签到记录",pad)
                    state.records.asReversed().take(300).forEach { r -> Card(pad){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text("${r.platform.title} · ${r.accountLabel}",fontWeight=FontWeight.SemiBold)
                        Text(r.task);Text("${formatTime(r.time)} · 周期 ${r.period}",style=MaterialTheme.typography.bodySmall)
                        Text(Rules.resultTitle(r.status,r.targetId),color=statusColor(r.status));Text(r.message,style=MaterialTheme.typography.bodySmall)
                        if(r.experienceState.isNotBlank())Text("经验：${r.experienceState}",style=MaterialTheme.typography.bodySmall)
                        if(r.diagnostic.isNotBlank())Text("诊断：${r.diagnostic}",style=MaterialTheme.typography.bodySmall)
                    }}}
                }
                3 -> {
                    Card(pad){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("外观",style=MaterialTheme.typography.titleMedium)
                        ThemeMode.entries.forEach { mode ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=state.settings.themeMode==mode,onClick={mutate{s->s.copy(settings=s.settings.copy(themeMode=mode))}},enabled=!busy)
                                Text(mode.title)
                            }
                        }
                        Text("跟随系统会随手机的深色模式切换；设置即时生效并保存在本机。",style=MaterialTheme.typography.bodySmall)
                    }}
                    Card(pad){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text("任务开关和角色选择",style=MaterialTheme.typography.titleMedium);Text("在账号页勾选要签到的角色或贴吧。未绑定的游戏无需启用。")
                        SettingSwitch("打开 App 时提示补跑今日未完成任务",state.settings.offerCatchup,!busy){mutate{s->s.copy(settings=s.settings.copy(offerCatchup=it))}}
                        SettingSwitch("签到结果通知",state.settings.notifications,!busy){v->if(v)onNotification();mutate{s->s.copy(settings=s.settings.copy(notifications=v))}}
                    }}
                    Card(pad){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text("每日后台签到（可选）",style=MaterialTheme.typography.titleMedium)
                        val canAuto=state.records.any {Rules.completed(it.status)}
                        SettingSwitch("启用 WorkManager 后台任务",state.settings.auto,!busy&&canAuto){v->mutate{s->val setting=s.settings.copy(auto=v);app.schedule(setting);s.copy(settings=setting)}}
                        if(!canAuto)Text("先在前台完成至少一项真实签到，再启用自动签到。",style=MaterialTheme.typography.bodySmall)
                        var time by remember(state.settings.hour,state.settings.minute){mutableStateOf("%02d:%02d".format(state.settings.hour,state.settings.minute))}
                        OutlinedTextField(time,{time=it.take(5)},label={Text("北京时间 HH:mm")},singleLine=true,enabled=!busy)
                        TextButton(onClick={val p=time.split(':');val h=p.getOrNull(0)?.toIntOrNull();val m=p.getOrNull(1)?.toIntOrNull();if(h in 0..23&&m in 0..59)mutate{s->val setting=s.settings.copy(hour=h!!,minute=m!!);app.schedule(setting);s.copy(settings=setting)}else error="时间格式应为 00:00 至 23:59"},enabled=!busy){Text("保存时间")}
                        Text("按约定时间附近执行，受系统省电、网络和厂商限制影响。强制停止后需重新打开 App。仅补跑当天任务，不使用补签卡或付费资源。",style=MaterialTheme.typography.bodySmall)
                    }}
                    Card(pad){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("本地安全",style=MaterialTheme.typography.titleMedium)
                        Text("凭证和账号记录由 Android Keystore 密钥加密；禁用备份和明文 HTTP。页面允许截图，手动凭证输入保持遮掩。凭证只发往对应平台核实的官方域名。",style=MaterialTheme.typography.bodySmall)
                        Text("版本 ${BuildConfig.VERSION_NAME} · Android 8.0+ · 无服务器 · 不绕过验证码",style=MaterialTheme.typography.bodySmall)
                    }}
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if(adding)AccountDialog(app,editing,onClose={adding=false;editingId=null},onError={error=it})
    if(error.isNotBlank())AlertDialog(onDismissRequest={error=""},title={Text("提示")},text={Text(error)},confirmButton={TextButton(onClick={error=""}){Text("确定")}})
    delete?.let { a -> AlertDialog(onDismissRequest={delete=null},title={Text("删除此账号？")},text={Text("删除 ${a.note.ifBlank{a.displayName}} 的本地凭证、选择、操作进度和全部历史记录。不会退出官方 App。")},confirmButton={TextButton(onClick={mutate{s->Rules.removeAccount(s,a.id)};delete=null}){Text("删除")}},dismissButton={TextButton(onClick={delete=null}){Text("取消")}}) }
}

@Composable private fun AccountDialog(app:CheckInApp,previous:Account?,onClose:()->Unit,onError:(String)->Unit) {
    var platform by rememberSaveable {mutableStateOf(previous?.platform?:Platform.NETEASE)}
    var note by rememberSaveable {mutableStateOf(previous?.note?:"")}
    var credential by remember {mutableStateOf("")}
    var saving by remember {mutableStateOf(false)}
    var loggingIn by remember {mutableStateOf(false)}
    var help by remember {mutableStateOf(false)}
    var manual by rememberSaveable {mutableStateOf(false)}
    val context=androidx.compose.ui.platform.LocalContext.current
    fun saveCredential(raw: String) {
        if(raw.isBlank()){onError("请先完成官方网页登录，或在本机输入有效凭证");return}
        saving=true
        val chosenPlatform=platform
        val chosenNote=note
        app.scope.launch {Runner.mutex.withLock {try {
            val identity=app.adapters.getValue(chosenPlatform).validate(raw)
            app.store.update{Rules.addOrUpdate(it,identity,chosenPlatform,raw,chosenNote,previous?.id)}
            app.refresh();credential="";onClose()
        }catch(e:Exception){onError(validationError(e,raw))}finally{saving=false}}}
    }
    val officialLogin=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        loggingIn=false
        if(result.resultCode==Activity.RESULT_OK) {
            val raw=result.data?.getStringExtra(WebLoginActivity.EXTRA_TICKET)?.let {LoginResultBroker.take(it,platform)}
            if(raw==null)onError("登录结果已过期或进程中断，请重新进行官方网页登录")
            else saveCredential(raw)
        }
    }
    AlertDialog(onDismissRequest={if(!saving){credential="";onClose()}},title={Text(if(previous==null)"添加账号" else "更新账号")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(previous==null)Platform.entries.forEach { p -> Row(verticalAlignment=Alignment.CenterVertically){RadioButton(platform==p,{if(platform!=p){platform=p;credential=""}},enabled=!saving);Text(p.title)} }
            else Text("${previous.platform.title} · ${previous.displayName}")
            OutlinedTextField(note,{note=it.take(40)},label={Text("备注，如主账号 / 分身账号")},enabled=!saving,singleLine=true)
            Button(onClick={
                if(!loggingIn){loggingIn=true;try{officialLogin.launch(Intent(context,WebLoginActivity::class.java).putExtra(WebLoginActivity.EXTRA_PLATFORM,platform.name))}catch(_:Exception){loggingIn=false;onError("未能打开登录页，请重新打开应用后再试")}}
            },enabled=!saving&&!loggingIn,modifier=Modifier.fillMaxWidth()){Text(if(loggingIn)"官方登录处理中…" else "官方网页登录并自动导入")}
            Text("在本工具内登录所选平台，识别身份后自动保存。每次网页登录使用独立的临时会话；不会读取原版或分身 App 的私有文件。",style=MaterialTheme.typography.bodySmall)
            if(platform==Platform.NETEASE)Text("签到目标：黑胶乐签。旧版普通每日签到已停用，不执行听歌、评论、兑换或购买任务。",style=MaterialTheme.typography.bodySmall)
            if(previous!=null)Text("更新登录必须仍为 ${previous.displayName}；其他账号请另行添加。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={manual=!manual}){Text("手动导入凭证（备用）")}
            if(manual){
                Text(credentialHint(platform),style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(credential,{credential=it},label={Text(if(previous!=null)"新凭证（留空保留）" else "本地输入凭证")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!saving,modifier=Modifier.fillMaxWidth(),maxLines=4)
                Text("凭证只在本机输入，不要发送到聊天。网页登录遇到平台不支持或风控时可使用此备用方式。",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={help=!help}){Text("如何获取 / 更新凭证")}
            }
            if(help){
                Text(credentialHelp(platform),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(officialUrl(platform))))}){Text("打开官方入口")}
            }
            if(saving)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(onClick={
        val raw=credential.trim().ifBlank {previous?.credential?:""}
        saveCredential(raw)
    },enabled=!saving&&(previous!=null||manual)){Text(if(previous!=null)"保存备注 / 手动凭证" else "校验手动凭证并保存")}},dismissButton={TextButton(onClick={credential="";onClose()},enabled=!saving){Text("取消")}})
}
@Composable private fun SettingSwitch(label:String,value:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onChange,enabled=enabled)}}
@Composable private fun statusColor(status:TaskStatus):Color=when(status){TaskStatus.SUCCESS,TaskStatus.ALREADY->Color(LocalThemePalette.current.success);TaskStatus.FAILED,TaskStatus.EXPIRED->MaterialTheme.colorScheme.error;TaskStatus.UNKNOWN,TaskStatus.INTERRUPTED,TaskStatus.MANUAL->Color(LocalThemePalette.current.warning);else->MaterialTheme.colorScheme.onSurfaceVariant}
private fun formatTime(time:Long)=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(time))
private fun validationError(e:Exception,credential:String)=when(e){is AdapterException->"${e.status.title}：${Rules.safeMessage(e.message,credential)}";is IllegalArgumentException->Rules.safeMessage(e.message?:"凭证格式错误",credential);is java.io.IOException->"连接平台失败，请检查网络后重试";else->"未能可靠校验身份，请检查凭证格式与接入说明（${e.javaClass.simpleName}）"}
private fun credentialHint(p:Platform)=when(p){Platform.NETEASE->"MUSIC_U 值，或含 MUSIC_U 的网易云 Cookie";Platform.TIEBA->"BDUSS 值，或含 BDUSS 的百度 Cookie";Platform.SKLAND->"鹰角通行证 token，或官方凭证 JSON";Platform.BILIBILI->"含 SESSDATA 和 bili_jct 的 B 站 Cookie";Platform.HEYBOX->"含 heybox_id 和 pkey 的本人小黑盒 Cookie"}
private fun officialUrl(p:Platform)=when(p){Platform.NETEASE->"https://music.163.com/";Platform.TIEBA->"https://tieba.baidu.com/";Platform.SKLAND->"https://user.hypergryph.com/login";Platform.BILIBILI->"https://big.bilibili.com/mobile/index?location=drawExperienceModule";Platform.HEYBOX->"https://xiaoheihe.cn/app/bbs/home"}
private fun credentialHelp(p:Platform)=when(p){
    Platform.NETEASE->"在官方网易云网页版登录。桌面浏览器开发者工具 → Application / 存储 → Cookies → music.163.com，复制 MUSIC_U 到本机输入页。失效后重新登录获取。不要安装来历不明的抓包证书。此版不提供未经验证的密码登录。"
    Platform.TIEBA->"在官方贴吧网页版登录。浏览器开发者工具 → 存储 → Cookies 中复制 BDUSS（若有 STOKEN 可一并输入）。保存后会获取账号身份和关注列表，支持全选或只勾选部分贴吧。失效后从官方重新获取。"
    Platform.SKLAND->"首选上方「官方网页登录并自动导入」，在官方鹰角账号中心完成本次登录。两个账号分别添加并登录，保存后核对不同昵称 / ID 和角色。若你已有本人官方通行证 token 或 {\"cred\":\"官方cred\"}，可在此本地输入，失效需重新授权。账号中心和旧 skland 网页不是同一会话，不要混用 Cookie。不会读取原版或分身 App 的私有数据。"
    Platform.BILIBILI->"在官方 B 站网页版登录，从浏览器开发者工具 → 存储 → Cookies 复制 SESSDATA 和 bili_jct，可一并包含 DedeUserID。Cookie 在本机使用。需先满足大会员与等级资格，并手动看视频至少一分钟；本工具只领取等级加速包，不执行播放或其他签到。"
    Platform.HEYBOX->"首选上方官方网页登录，在小黑盒社区点击「登录」完成账号登录和必要验证后自动导入。电脑弹窗可双指缩放，协议由本人勾选。备用方式是在自己的官方浏览器会话中取得 heybox_id/pkey（或 user_heybox_id/user_pkey），可含官网生成的 x_xhh_tokenid；这是安全组件值，本工具不会伪造。凭证仅在本机输入。只执行每日签到，随后从任务中心确认签到任务，不补签、不领其他奖励。"
}
