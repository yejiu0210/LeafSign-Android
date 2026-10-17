# 小黑盒每日签到

核查日期：2026-10-17（Asia/Shanghai）。此功能新增于 0.4.0，0.4.1 修复网页登录布局及登录后跳转检测。公开资料、匿名协议检查、隔离测试、浏览器布局检查、真机验证是不同证据等级，不能互相替代。

## 业务与验证状态

目标为小黑盒普通每日签到，用户提供的业务说明是「登录后进入即可签到，在任务里查看是否完成」。工具只执行每日签到并复查任务中心，不调用补签、H币、抽奖、奖励领取、分享或评论接口。

本次取得了官方网页登录协议、当前网页签名源码和匿名只读接口响应。用户实际反馈 0.4.0 小黑盒出现电脑弹窗裁切，且在官方登录中心登录后转往裸域社区页面再次要求登录；**小黑盒未通过用户验收，0.4.1 修复仍待真机重新验证**，也没有真实账号签到或跨日重置证据。用户于 2026-10-17 报告原有四平台均可正常签到，此为用户报告，不能作为新增小黑盒的验证证据。当前构建及自动测试结果统一见 [验证记录](verification.md)。

## 官方入口、登录与凭证

[小黑盒官网](https://www.xiaoheihe.cn/) 本次匿名 HTTP 200，website 版本 `1.2.42`，网页协议 `web_version=3.0`。官网使用的 [官方移动登录中心](https://login.xiaoheihe.cn/?redirect_url=https%3A%2F%2Fwww.xiaoheihe.cn%2F&force_relogin=true) 本次匿名 HTTP 200，带移动 viewport，login 版本 `1.0.1`。`force_relogin=true` 是官方登录脚本已有参数，用于阻止初始化恢复会话后直接跳回官网。

App 内通过官方页面完成登录及本人验证，再自动导入本次会话。需要时可在账号页本地手动导入 Cookie；不向聊天提供凭证，不读取其他 App 的私有登录文件。

| 字段 | 用途与处理 |
|---|---|
| `heybox_id` / `user_heybox_id` | 同义账号 ID；只作会话候选，必须再向服务端验证 |
| `pkey` / `user_pkey` | 同义登录会话凭证；至少需要其中一个 |
| `x_xhh_tokenid` | 官方页面安全组件自行生成的风控 token；可保留本次页面真实生成的值，不自行伪造，也不称作账号主登录凭证 |

仅允许上述 Cookie 名字；其他平台的 Cookie 不会转发。登录会话 Cookie 可从 `xiaoheihe.cn`、`login.xiaoheihe.cn`、`www.xiaoheihe.cn`、`api.xiaoheihe.cn` 的本次 WebView 会话取得。官方登录脚本将账号 ID 和 pkey 的两个别名分别写入 `Domain=.xiaoheihe.cn; Path=/` 的共享 Cookie，默认有效期七天；所以原来的 www/login/api 来源理论上也能读取共享会话，不能仅以漏掉裸域认定 Cookie 丢失。平台 API 只向 `https://api.xiaoheihe.cn:443` 发送平台凭证，保留 TLS 校验并禁止重定向。凭证通过主工程的 Android Keystore 密钥加密保存，不进入普通日志、导出或源码。

登录失效时重新打开官方登录页更新凭证；验证码、补充身份或二次验证由本人在官网完成。工具不会自行实现密码登录协议或绕过验证。

## 0.4.1 登录兼容修复

用户截图中的官网弹窗固定宽 570px，遮罩居中且隐藏溢出内容，手机上左侧协议勾选框可能被裁掉。独立官方登录中心已有 ≤450px 的手机布局，表单宽 300px。0.4.1 继续使用该手机入口，提供“重新打开手机版登录”，并为小黑盒开启 WebView 宽视口、完整页面缩放及双指缩放，以兼容再次进入电脑官网的情况。协议由本人阅读和勾选，不注入样式或自动点击协议。

用户补充的登录后地址为 `https://xiaoheihe.cn/app/bbs/home`。该裸域与 www 对应地址匿名返回相同官网 HTML；0.4.0 只允许 www/login/api，阻断裸域后又以 `failedNavigation` 阻止身份检测。0.4.1 精确加入裸域 HTTPS 443；到达裸域或 www 的已知首页返回路径时，若本次会话已有完整 Cookie，先读取并向官方校验身份，不要求在首页弹窗再登录一次。没有完整 Cookie 或身份未校验成功，仍不能添加账号。

桌面 Edge 在 360 / 393 / 412×650 CSS 像素视区内已检查独立登录中心：输入框位于视区内，页面 `scrollWidth` 与视区宽度相等。此项只证明官网的浏览器手机布局，**不等同于 Android WebView 登录、用户协议操作或真实账号签到通过**。具体公开资源与代码依据见 [登录布局与裸域证据](evidence/heybox-login-layout.md)。

## 身份验证与会话刷新

`GET /account/restore_login` 必须满足：

- 业务 `status` 精确为 `ok`。
- `result.profile.heybox_id` 或 `result.account_detail.userid` 为有效正整数 ID；同时存在时必须相同。
- `result.pkey` 是有效、非空的会话字符串。
- 服务端 ID 与导入 Cookie 的 ID 一致。

昵称来自服务端 `profile.nickname`，缺失时显示服务端 UID。不会将用户填写的 Cookie ID 直接当作校验结果；身份冲突立即停止。

官方 `restore_login` 会更新会话。适配器将返回的新 `pkey` 同步给两个别名，并通过 `Identity.updatedCredential` 交回账号保存流程；本次后续任务查询与签到立即使用新会话。已存账号只在平台、服务端 UID、原始凭证字符串仍与本次输入一致时原子更新，防止覆盖用户刚更换的凭证。账号记录、任务归属及历史仍以独立本地账号和服务器 UID 隔离。

## 请求与结果判定

| 操作 | 接口 | 使用限制 |
|---|---|---|
| 校验及刷新身份 | `GET /account/restore_login` | 必须核对服务端身份与新会话 |
| 读取签到任务 | `GET /task/list_v2/` | 只识别唯一 `type=sign` 的任务 |
| 普通每日签到 | `GET /task/sign_v3/sign` | 只有任务查询明确为 `todo` 才发送 |

签到和任务形状依据下述公开 HAR 文档，属于可能变化的非公开客户端契约，不是平台承诺稳定的第三方开放 API。

任务查询必须 `status=ok`，且 `result.task_list[].tasks[]` 中恰好一个任务的 `type` 为 `sign`。只识别 `state=finish` 和 `state=todo`；缺失、重复或未知形状都保留“结果待确认”，不尝试写入。

| 证据 | 工具结果 |
|---|---|
| 写入前任务 `state=finish` | 今日已签到，不重复请求签到 |
| 任务明确 `state=todo` | 待签到，可执行普通签到 |
| 写入 `status=ok`、`result.state=success`，且复查任务 `state=finish` | 签到成功 |
| 写入 `ignore`、`fail`、缺失或未知状态，但复查任务为 `finish` | 今日已签到；说明本次写入结果未单独确认 |
| 写入后任务仍为 `todo`，或任务结构无法核对 | 结果待确认，不宣称成功 |
| 官方已见 `login` / `relogin` | 登录失效，停止该项重试 |
| 官方已见验证码、二次验证或补充身份状态 | 需要手动验证，停止该项重试 |
| 未知业务状态、非 JSON、网络异常 | 结果待确认；不依据 HTTP 200 推断成功 |

诊断使用固定说明，不保留原始响应、服务器消息、Cookie 或 token。适配器没有自动领取其他任务奖励、补签或消费资源的路径。

尚未找到普通每日签到精确重置时刻的官方承诺。工具以 Asia/Shanghai 午夜划分显示周期，并标记 `resetVerified=false`；服务端即时查询优先，不能根据本地日期推断已签。

## 资料与核查证据

直接读取的官方资源：

- [官网当前 JS](https://static.max-c.com/static/heybox/webapp/@heybox-spa/website/app/index-Q5il7rGn.js)，SHA256：`4a2778c1cbb7b0d4e7ac2de839187796b3148db049cb6532634b67531418045d`。
- [登录中心当前 JS](https://static.max-c.com/static/heybox/webapp/@heybox-spa/login/app/index-CscaK6Wd.js)，SHA256：`bedb5bb2b786c8d87b576a6eb54ba61c26846a4da0b6d223676128656693c63f`。

`HeyboxWebSigning` 独立表达当前官方网页公开的 `Wwe` / `Tr` / `zM.g` 数学协议；不使用伪造 IMEI、他人设备证明或风控 token。请求使用网页字段 `os_type=web`、`web_version=3.0`、`version=999.0.4`，nonce 为通用请求随机数。开发过程中直接执行下载的官方 JS，得到下列确定性向量供测试比对：

| 路径 | 秒时间戳 | nonce | 官方 JS 输出 |
|---|---|---|---|
| `/task/list_v2/` | `1792200000` | `0123456789ABCDEF0123456789ABCDEF` | `YZP3V05` |
| `/account/restore_login` | `1792200000` | `FEDCBA9876543210FEDCBA9876543210` | `D7YIU46` |
| `/task/sign_v3/sign` | `1792200000` | `00000000000000000000000000000000` | `DDWS724` |

按上述网页协议匿名读取身份、任务列表与签到日历，均获得 HTTP 200、`status=login`、请登录后使用该功能；相同任务请求缺少签名时为 `failed`、非法请求。这只证明匿名请求可达到认证门槛，不证明真实账号登录或签到可用。匿名探测没有访问任何签到写入接口。

开源参考与许可：

- [QinglongCheckinScripts 的公开 HAR 业务文档](https://github.com/wentaoabcd/QinglongCheckinScripts/blob/main/docs/heybox-api.md)：记载 2026-05-24、Android `1.3.382` 的签到及任务形状。仓库本次最后 push 为 2026-05-24，没有明确许可证，当前目录已无 Heybox.py。本工程仅研究事实性协议线索，没有复制实现；这些形状仍待本次真实账号验证。
- [heybox-cli](https://github.com/shadowfish07/heybox-cli)：[MIT](https://github.com/shadowfish07/heybox-cli/blob/main/LICENSE)，本次最后 push 为 2026-10-01。用于对照官方 QR / 网页登录与 Cookie 命名；其签名算法与今日官网不同，没有采用或复制其实现。只读搜索可用性不能证明签到可用性。

## 最短人工验证

1. 覆盖安装 0.4.1，账号页选择“小黑盒”，通过官方网页登录。应先显示手机登录中心；完成登录后应自动校验并返回工具，核对显示昵称和 UID。
2. 仅启用小黑盒每日签到，执行一次，确认工具结果及官方 App「任务」中签到状态一致。
3. 再次执行应显示今日已签到；不会补签或领取其他任务。
4. 更新凭证后重新核对 UID；需要手动验证时先到官方页面完成。
5. 若又显示官网电脑弹窗，可双指缩放，或点“重新打开手机版登录”。协议必须本人勾选；没有返回工具时点击“登录完成，检测账号”，反馈固定状态提示和脱敏截图。

反馈错误时提供工具显示的状态和脱敏提示即可，不发送 Cookie、token 或登录密码。
