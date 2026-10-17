# 官方网页登录与自动导入

核查日期：2026-10-17。版本：0.4.1。用户在0.3.0交付后回复「测试完成，都可以正常签到」，原有四平台视为用户报告验收；开发侧没有本人凭证或独立真机验证。用户实际反馈 0.4.0 小黑盒电脑页面裁切、官方登录后又要求登录；小黑盒未通过验收，0.4.1 修复仍待真机重新验证。平台页面、验证码和 WebView 支持仍可能变化。

## 此次修复依据

0.4.1 小黑盒修复：官网电脑登录弹窗固定宽 570px，居中遮罩隐藏溢出内容，手机可能看不到左侧用户协议勾选框；官方独立登录中心则已有 ≤450px 的手机布局。用户补充登录后跳到 `https://xiaoheihe.cn/app/bbs/home`，公开读取证实裸域与 www 提供相同官网。0.4.0 精确白名单遗漏裸域，导航阻断还会设置 `failedNavigation`，使后续自动和手动身份检测提前返回。现补齐官方裸域、Cookie 来源和测试，使用已有官方参数 `force_relogin=true` 保持手机登录入口；小黑盒启用宽视口、完整页面缩放与双指缩放，并提供“重新打开手机版登录”。不自动勾选协议。

对裸域或 www 的已知首页返回地址，若本次 WebView 已有完整 Cookie，先快照会话并校验官方身份，避免要求用户再操作电脑首页弹窗；路径跳转本身仍不能证明登录成功。桌面 Edge 在 360 / 393 / 412×650 CSS 像素视区检查了官方手机登录中心，输入框矩形位于视区内且 `scrollWidth` 等于视区宽度。这属于网页布局验证，没有完成 Android WebView 的真实账号登录。详见 [小黑盒登录布局与裸域证据](evidence/heybox-login-layout.md)。

以下为此前 0.3.0 修复依据，原有四平台的用户验收不外推到小黑盒：

官方森空岛首页对 Android WebView UA 返回302到 m.skland.com，旧白名单遗漏此移动域；移动首页还实际是下载页，不能当登录页面。新入口改为官方鹰角账号中心。网易官方首页依赖 g_iframe 的 about:blank 初始化，旧子框架仅允许 HTTPS 会阻碍该流程；现在仅对子框架允许精确 about:blank，顶层仍限定官方 HTTPS。网易官网按 UA 的 Android/Mobile 分流，新版使用电脑网页模式并允许缩放，域名栏会明确提示。

这些是已核实的兼容缺陷，不能单凭截图证明整页白屏唯一根因。登录界面改为固定原生工具栏，首帧绘制后才初始化 WebView，处理系统栏 / 刘海 / 输入法边距；25秒页面超时、HTTP / SSL / 网络错误、空页面和渲染进程退出均有脱敏状态与重试。UI 逻辑依据 [Android WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient)；原生恢复路径的设备测试仅构建，尚未执行。

## 入口与授权

| 平台 | 本工具内的官方入口 | 自动提取及校验 |
| --- | --- | --- |
| 网易云音乐 | https://music.163.com/ （电脑网页） | 本工具 WebView 的 MUSIC_U、可选 __csrf；取得真实 UID 与昵称，只执行已确认的黑胶乐签 |
| 百度贴吧 | https://tieba.baidu.com/ | 本工具 WebView 的 BDUSS、可选 STOKEN；复用贴吧适配器校验身份并刷新关注列表 |
| 森空岛 | https://user.hypergryph.com/login | 仅在此精确官方 origin 读取 HG_SDK.User.getToken()，要求 result===true 与 data.token 字符串；随后由既有森空岛适配器换授权并校验真实 ID 与绑定游戏 / 角色。不混用 skland 域 Cookie |
| 哔哩哔哩 | https://passport.bilibili.com/login | 本工具 WebView 的 SESSDATA、bili_jct 等白名单 Cookie；复用 B 站适配器校验 UID 与等级加速包资格。网页登录不等于完成观看条件或领取 |
| 小黑盒 | https://login.xiaoheihe.cn/?redirect_url=https%3A%2F%2Fwww.xiaoheihe.cn%2F&force_relogin=true | 本工具 WebView 的 pkey / user_pkey、heybox_id / user_heybox_id，以及官网生成的可选 x_xhh_tokenid；可信首页返回时优先读取完整 Cookie，向官方 restore_login 校验 ID 并保留其刷新会话 |

检测到完整 Cookie 后最多自动进行三次不同授权的身份校验，避免重复轮询网络；也可点「登录完成，检测账号」。没有完整授权、验证不通过或官方验证码未完成时不会添加账号。自动导入仍经主界面再次校验和重复身份检查，更新时要求与旧账号平台 ID 相同。

0.4.1 小黑盒主框架精确允许 `xiaoheihe.cn`、`login.xiaoheihe.cn`、`www.xiaoheihe.cn`、`api.xiaoheihe.cn` 的 HTTPS 443 地址，Cookie 来源同为这四个站点，只取白名单字段。官方登录脚本的账号 ID / pkey 别名写入 `.xiaoheihe.cn`、Path=/、七天的共享 Cookie；未采用 CLI 模式将凭证拼进回调 URL。会话通过一次性内存票据传回，经再次身份校验保存。不读官方 App 私有数据，不自动生成安全 token。登录原生控件跟随本工具的外观设置，官网自身配色由站点和系统 WebView 支持决定。

主文档仅允许 HTTPS 443 的确切官方主机：网易云 music.163.com / y.music.163.com / st.music.163.com / reg.163.com / dl.reg.163.com / passport.163.com；贴吧 tieba.baidu.com / wappass.baidu.com / passport.baidu.com / wappass.bdimg.com；森空岛 www.skland.com / m.skland.com / web-api.skland.com / user.hypergryph.com / as.hypergryph.com；B 站 passport.bilibili.com / www.bilibili.com / m.bilibili.com / big.bilibili.com / api.bilibili.com。精确名单位于 OfficialLoginPolicy.kt。页面 HTTPS 子资源与安全的 about:blank 子框架可加载；不向子资源主动注入平台 Cookie。需要其他主机时先核实；不开放通配或 HTTP。

森空岛官方 user-center 当前前端 [index.62f8883d.js](https://web.hycdn.cn/user-center/index.62f8883d.js) 自身调用 `HG_SDK.User.getToken()` 并验证 result / data.token，登录完成转到 /userInfo；因此自动检测仅在该路径触发，用户也可手动检测。SDK来自 [官方 bootstrap](https://web.hycdn.cn/hg_web_sdk/lib/sdk.entry.js)，当前会话读取由 SDK 自行选择正确端点，未硬编码已失效的 token_by_cookie 兼容路径。每次 JS 回读3秒、整体12秒超时；取值和身份校验时隐藏网页，临时随机 window 属性读取后删除，不写 DOM / URL / 日志。具体公开资源哈希见 `evidence/login-research-sources.md`。

## 会话、凭证和截图

- WebView 是本工具自己的会话。普通 Android 应用使用独立 UID 和沙盒，即使知道另一个 App 路径或授予文件权限，也不能因此读取其私有登录文件。参见 [Android 应用沙盒](https://source.android.com/docs/security/app-sandbox)。不使用 Root、全文件权限或读取分身私有数据。
- 每次开始前先清 Cookie / WebStorage，等待 Cookie 清理回调后才加载官网；正常取消或导入后停止、销毁 WebView，清缓存 / WebStorage，并等待 Cookie 清理回调才返回。清理使用进程共享栅栏，10秒等待超时不取消底层清理；下一次登录必须等旧回调完成，不能让迟到清理擦除新账号会话。第二个森空岛账号必须自己登录，不能复制第一个账号的授权。此操作不注销官方 App 的登录。
- WebView 没有通用无痕保证。它可能临时将站点会话存到本 App 的私有 WebView 数据中；代码不调用 CookieManager.flush、不恢复 WebView state，正常结束清理会话。进程被强杀无法保证完成当次清理，下一次打开登录页会先清理旧会话。正式持久凭证仍通过 Android Keystore AES-GCM 加密保存，不进入普通导出或备份；不能宣称 WebView 从不产生临时明文数据。
- Activity 结果只返回一次性随机票据，凭证仅在进程内存传递；票据两分钟后无效，平台不匹配或重复领取均拒绝，进程中断需重新登录。没有密码输入控件、JS 原生桥、调试开关、文件 / content URL 访问、混合内容、SSL 错误忽略或外部自定义协议启动。
- 整个 App 未设置 FLAG_SECURE，方便截图反馈。本地手动凭证输入保持遮罩。森空岛 SDK 取 token / 身份校验阶段隐藏原生 WebView，兼容旧授权 JSON 地址也保持隐藏；官方手机号、昵称等仍需本人遮盖。

WebView 安全配置依据：[Android WebView 原生桥安全说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。森空岛新版采用官方账号中心自身 SDK，不再假定账号中心和 skland 社区共享站点会话。

## 跳转官方 App 的授权边界

当前公开森空岛 bridge 的 skland://main/home、skland://web 只说明启动或打开页面。没有查证可供任意第三方 Android App 使用的授权注册和 callback，也没有查证网易云此类回调能取得黑胶乐签所需 MUSIC_U。仅启动成功不能记为授权成功，不提供虚假的「从原版 / 分身自动取号」功能。此版本不要求 Root、读取 App 私有目录或绕过验证码；两平台继续使用官方网页本次会话。

## 验收与故障

1. 覆盖安装 0.4.1，账号 → 添加账号 → 选平台 / 备注 → 官方网页登录并自动导入。网易使用可缩放电脑页面，森空岛打开鹰角账号中心。小黑盒应先显示手机登录中心，完成后由工具校验并返回账号页；若进入官网电脑弹窗，可双指缩放或点“重新打开手机版登录”，协议本人勾选。完成官网登录后核对昵称与 ID，再选择目标。已有网易账号先校验 / 刷新，再勾选黑胶乐签。
2. 另加一个森空岛账号，网页登录必须出现新会话，核对不同 ID 和各自角色 / 区服。尝试重复导入第一个账号应被拒绝，更新已有账号时登录另一账号也应拒绝。
3. 在首页 / 记录页截图应可保存；登录页应始终看到原生标题 / 返回，网页故障时可见固定原因与重试。森空岛授权检测期间只能看到原生进度，不能看到 token。设备或工作资料策略仍可能另行禁止截图。
4. 取消、旋转、后台进程中断或票据过期时不能产生未经校验的新账号。官方页面可能不支持 WebView，验证码只能本人完成。遇到需要跳转官方 App 的页面可选网页的其他方式，或使用本地手动凭证备用入口。

网站登录适配与签到适配分开维护。页面打不开时记录平台、手机 Android / WebView 版本、允许截图的脱敏页面及固定错误提示，不提供账号密码或原始 Cookie。没有真实账号完成验收前，不能将“构建通过”写成“登录成功”。
