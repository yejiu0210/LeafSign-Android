# 平台适配器维护

入口为 `PlatformAdapter.validate / query / execute`，统一执行逻辑在 `core/Runner.kt`。平台实现相互独立；不需要插件框架或后端。

0.4.2 小黑盒改为读取**当前白名单官网页面的一次 Cookie 快照**，只提取 `pkey/user_pkey/heybox_id/user_heybox_id/x_xhh_tokenid`，不再合并多个域、路径或旧会话的 Cookie。`HeyboxLoginCookies` 拒绝冲突别名、访客占位值及不完整会话；仅在官方登录返回时自动检测，填写表单期间不拿访客状态请求身份校验。读取与验证均有超时和页面/会话代际检查，临时凭证不进 Bundle 或日志。官方新 `pkey` 按登录中心的 JavaScript `escape` Cookie 格式编码一次，已导入的 Cookie 保持原始字节，尤其不能把 `+` 当空格或把 `%3D` 二次编码。仍须由 `restore_login` 校验真实 UID；登录失败只展示固定脱敏原因，不能把本地 Cookie 存在当作登录成功。开发侧未独立完成真实账号授权，修复仍需本人手机复测。

0.4.1 核实小黑盒裸域与www提供相同官网，主框架增加精确 `xiaoheihe.cn`，避免拦截登录后返回并锁死检测。官方成功跳转时先读取会话并服务端验证身份；独立登录中心使用已查证的 force_relogin，保留正常移动UA，并为电脑页启用宽视口/overview/缩放兜底。不注入表单CSS、不替用户勾协议、不用CLI模式把凭证拼入URL。裸域、Cookie范围及布局证据见 `evidence/heybox-login-layout.md`。

0.4.0 新增 `HeyboxAdapter`，只使用已核实的官方网页签名协议与三个固定 API。`restore_login` 返回的新 `pkey` 必须通过 `Identity.updatedCredential` 供新账号保存，已存账号用平台、UID、原始凭证比较后更新；Runner 每次请求重新读取该账号最新凭证，避免用快照中失效的旧会话。任务中心唯一 type=sign 且明确 finish 才认为今日完成，写入未知但查询完成不冒报本次成功。见 `integration-heybox.md`。

贴吧新增独立目标 `tieba:like:<forumId>`，默认关闭，与签到勾选分开；“全选随机一赞 / 取消全部随机一赞”只修改点赞任务，不改变签到选择。`TiebaLikeTask` 的账号、目标、周期 checkpoint 在现有 AES-GCM 状态中保存，写请求前必须落盘；一旦发送操作，同吧当日固定一帖，正常任务取消最多一次、同帖点赞最多两次。恢复先查明确的本人点赞状态，不能推测未点赞或把点赞成功当作经验到账。0.4.1 针对旧 `content_type` 协议明确 `300000` 拒绝的单次同帖修复继续按原条件和预算执行，不给新协议增加次数。删除账号也删操作进度。详见 `integration-tieba-like.md`。

0.4.2 针对用户报告的**个别吧**身份冲突，先校验候选详情再保存；从随机起点最多读 3 篇，仅明确正整数帖子/吧 ID 矛盾可跳过，缺失、未知、认证、验证码与网络错误立即停止。旧 `selected` 且点赞和取消计数均为零的候选，只有明确公开 ID 冲突时可一次同吧替换；必须先持久化 `candidateRepairUsed=true`，再搜索并排除原帖，失败或重启也不重置标记。任何发送过写操作的进度不能换帖或清次数。详情必须继续准确匹配登录 UID、`thread.id`、主 `forum.id`；附加 `thread.fid` 仅缺省或精确整数 0 / 字符串 `"0"` 可视为未设置，显式 null、其他畸形值与冲突正数仍拒绝。匿名读取该吧 10 篇候选均未复现差异，不能声称零值、跨吧候选或任一假设已经确认为用户的唯一根因。

夜间模式存为 `Settings.themeMode`（SYSTEM / LIGHT / DARK），缺失或未知值回退跟随系统；旧加密数据无需迁移或清空。`ThemePalette` 同时供 Compose 和原生登录控件使用，官网不注入改色 CSS；WebView 仅开启系统支持的深色算法。

0.3.0 登录入口在 `login/WebLoginActivity.kt`，固定原生控件与WebView分离；`OfficialLoginPolicy` 管精确导航，`WebLoginCookieClear` 的进程栅栏保证超时/Activity取消不会取消底层清理。只负责官网授权和身份校验，不执行签到。`LoginResultBroker` 只传一次性内存票据，不能把 Credential 放进 Activity result / Bundle / saved state。森空岛仅精确官方portal调用其已核对SDK，临时值读取后delete；JS3秒/整体12秒超时，读取与校验期间隐藏网页。保留主框架HTTPS、仅安全about:blank子frame、SSL拒绝和会话清理。见 `integration-web-login.md`。

网易当前目标为黑胶乐签：`netease:vip:music-sign`，协议见 `integration-netease-vip.md`。不复用旧ordinary dailyTask的-2或完成记录；状态查询只能调用minidesk，history/detail属于两步写链路。EAPI固定ECB是服务端协议兼容包络，不能自行改AES模式；仍必须保留TLS和本地AES-GCM。未知明细即使查到今日完成也只显示ALREADY，不宣称本次未知请求成功。

- `validate` 必须取得可辨认的用户 ID，列出实际绑定目标，不能用备注或本地序号当平台身份。凭证更新必须与已有账号 ID 一致。`Target.metadata.ownerUserId` 随目标持久化，角色还应保存准确的游戏、角色和服务器字段。
- `query` 只能调用已查证的只读接口。`PENDING` 必须明确证明尚可执行；无法判断返回 null 或 UNKNOWN，绝不能把“查询没有找到”直接当未签到。
- `execute` 只做用户选择的普通任务，检验目标所属账号。未知结构或字段返回 UNKNOWN。不要把任意非零错误归为 ALREADY；应核实该接口该业务的准确判定。
- `NetClient` 强制 HTTPS、443 端口、逐平台精确域名白名单、禁止重定向、禁止自动网络重试、默认 40 秒总超时。增加域名先核实所有权，不能让用户填写任意中转地址。勿增加包含请求头/请求体的日志拦截器。
- 请求后超时可能已执行。Runner 先查状态，只有明确 PENDING 才重发一次；身份验证等只读操作仅重试一次。过期、风控、验证码和资格不足不自动重试。
- 每条记录关联内部 accountId、目标 id、本地暂定日周期。后台和前台共用进程 Mutex，执行中先落盘；中断恢复为 INTERRUPTED。业务日精确边界仍需平台官方证据和真实跨日测试；更新 reset metadata 后同步修订文档与测试。
- 日志只保留数值业务码、HTTP 码、异常类型及明确列入白名单的适配器固定原因。贴吧身份诊断限于 `tieba field=thread_id|forum_id|redundant_fid expected=<公开数字ID> actual=<公开数字ID或missing/zero/invalid>` 的完整格式，不含账号 UID、昵称或任意服务器字符串。不保存完整平台返回，不导出 Credential，不把服务器消息直接传 UI。原始未知响应只能在用户本机临时调试，先脱敏并核对字段。
- 单元测试 fixtures 只在 src/test；设备测试只在 src/androidTest，生产 APK 无演示账号和模拟成功开关。修改协议后测试准确成功、重复、未登录、未绑定、资格不足、缺字段、未知码、超时、并发和串号。
- 本地状态全部由 Android Keystore AES-GCM 加密，存放 noBackupFilesDir，AtomicFile 原子替换并支持备份恢复；损坏时禁止静默清空。不要把 Token 存入普通 SharedPreferences、Bundle、SavedStateHandle、日志或崩溃平台。

优先从对应官方入口当前加载的脚本验证字段，其次审查有明确许可、近期维护的开源实现。记录核查日期、URL、commit 或静态资源哈希、字段路径、证据等级。依赖版本固定，协议改变时仅修该适配器和其测试。
