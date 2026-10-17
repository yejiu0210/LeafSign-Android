# 平台适配器维护

入口为 `PlatformAdapter.validate / query / execute`，统一执行逻辑在 `core/Runner.kt`。平台实现相互独立；不需要插件框架或后端。

0.3.0 登录入口在 `login/WebLoginActivity.kt`，固定原生控件与WebView分离；`OfficialLoginPolicy` 管精确导航，`WebLoginCookieClear` 的进程栅栏保证超时/Activity取消不会取消底层清理。只负责官网授权和身份校验，不执行签到。`LoginResultBroker` 只传一次性内存票据，不能把 Credential 放进 Activity result / Bundle / saved state。森空岛仅精确官方portal调用其已核对SDK，临时值读取后delete；JS3秒/整体12秒超时，读取与校验期间隐藏网页。保留主框架HTTPS、仅安全about:blank子frame、SSL拒绝和会话清理。见 `integration-web-login.md`。

网易当前目标为黑胶乐签：`netease:vip:music-sign`，协议见 `integration-netease-vip.md`。不复用旧ordinary dailyTask的-2或完成记录；状态查询只能调用minidesk，history/detail属于两步写链路。EAPI固定ECB是服务端协议兼容包络，不能自行改AES模式；仍必须保留TLS和本地AES-GCM。未知明细即使查到今日完成也只显示ALREADY，不宣称本次未知请求成功。

- `validate` 必须取得可辨认的用户 ID，列出实际绑定目标，不能用备注或本地序号当平台身份。凭证更新必须与已有账号 ID 一致。`Target.metadata.ownerUserId` 随目标持久化，角色还应保存准确的游戏、角色和服务器字段。
- `query` 只能调用已查证的只读接口。`PENDING` 必须明确证明尚可执行；无法判断返回 null 或 UNKNOWN，绝不能把“查询没有找到”直接当未签到。
- `execute` 只做用户选择的普通任务，检验目标所属账号。未知结构或字段返回 UNKNOWN。不要把任意非零错误归为 ALREADY；应核实该接口该业务的准确判定。
- `NetClient` 强制 HTTPS、443 端口、逐平台精确域名白名单、禁止重定向、禁止自动网络重试、默认 40 秒总超时。增加域名先核实所有权，不能让用户填写任意中转地址。勿增加包含请求头/请求体的日志拦截器。
- 请求后超时可能已执行。Runner 先查状态，只有明确 PENDING 才重发一次；身份验证等只读操作仅重试一次。过期、风控、验证码和资格不足不自动重试。
- 每条记录关联内部 accountId、目标 id、本地暂定日周期。后台和前台共用进程 Mutex，执行中先落盘；中断恢复为 INTERRUPTED。业务日精确边界仍需平台官方证据和真实跨日测试；更新 reset metadata 后同步修订文档与测试。
- 日志只保留数值业务码、HTTP 码、异常类型；不保存完整平台返回，不导出 Credential，不把服务器消息直接传 UI。原始未知响应只能在用户本机临时调试，先脱敏并核对字段。
- 单元测试 fixtures 只在 src/test；设备测试只在 src/androidTest，生产 APK 无演示账号和模拟成功开关。修改协议后测试准确成功、重复、未登录、未绑定、资格不足、缺字段、未知码、超时、并发和串号。
- 本地状态全部由 Android Keystore AES-GCM 加密，存放 noBackupFilesDir，AtomicFile 原子替换并支持备份恢复；损坏时禁止静默清空。不要把 Token 存入普通 SharedPreferences、Bundle、SavedStateHandle、日志或崩溃平台。

优先从对应官方入口当前加载的脚本验证字段，其次审查有明确许可、近期维护的开源实现。记录核查日期、URL、commit 或静态资源哈希、字段路径、证据等级。依赖版本固定，协议改变时仅修该适配器和其测试。
