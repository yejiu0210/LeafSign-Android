# 官方网页登录与自动导入

核查日期：2026-10-17。版本：0.2.0。实现及 JVM 票据测试不等于真机网页登录验证；目前没有本人账号或可用 Android 设备完成网页登录。平台的页面结构、验证码和 WebView 支持可能变化。

## 入口与授权

| 平台 | 本工具内的官方入口 | 自动提取及校验 |
| --- | --- | --- |
| 网易云音乐 | https://music.163.com/ | 本工具 WebView 的 MUSIC_U、可选 __csrf；复用网易云适配器取得真实 UID 与昵称 |
| 百度贴吧 | https://tieba.baidu.com/ | 本工具 WebView 的 BDUSS、可选 STOKEN；复用贴吧适配器校验身份并刷新关注列表 |
| 森空岛 | https://www.skland.com/ | 完成官方登录后，在同一个隐藏 WebView 导航到 https://web-api.skland.com/account/info/hg，仅取 data.content；再由森空岛适配器校验真实 ID 与绑定游戏 / 角色 |
| 哔哩哔哩 | https://passport.bilibili.com/login | 本工具 WebView 的 SESSDATA、bili_jct 等白名单 Cookie；复用 B 站适配器校验 UID 与等级加速包资格。网页登录不等于完成观看条件或领取 |

检测到完整 Cookie 后最多自动进行三次不同授权的身份校验，避免重复轮询网络；也可点「登录完成，检测账号」。没有完整授权、验证不通过或官方验证码未完成时不会添加账号。自动导入仍经主界面再次校验和重复身份检查，更新时要求与旧账号平台 ID 相同。

主文档仅允许 HTTPS 443 的确切官方主机：网易云 music.163.com / st.music.163.com / reg.163.com / dl.reg.163.com / passport.163.com；贴吧 tieba.baidu.com / wappass.baidu.com / passport.baidu.com / wappass.bdimg.com；森空岛 www.skland.com / web-api.skland.com / user.hypergryph.com / as.hypergryph.com；B 站 passport.bilibili.com / www.bilibili.com / m.bilibili.com / big.bilibili.com / api.bilibili.com。页面 HTTPS 子资源仍可加载官方网页所需静态资源和验证码；没有向子资源主动注入平台 Cookie。需要白名单外登录域时必须先核实，当前会阻止跳转。

## 会话、凭证和截图

- WebView 是本工具自己的会话。普通 Android 应用使用独立 UID 和沙盒，即使知道另一个 App 路径或授予文件权限，也不能因此读取其私有登录文件。参见 [Android 应用沙盒](https://source.android.com/docs/security/app-sandbox)。不使用 Root、全文件权限或读取分身私有数据。
- 每次开始前先清 Cookie / WebStorage，等待 Cookie 清理回调后才加载官网；正常取消或导入后停止、销毁 WebView，清缓存 / WebStorage，并等待 Cookie 清理回调才返回。第二个森空岛账号必须自己登录，不能复制第一个账号的授权。此操作不注销官方 App 的登录。
- WebView 没有通用无痕保证。它可能临时将站点会话存到本 App 的私有 WebView 数据中；代码不调用 CookieManager.flush、不恢复 WebView state，正常结束清理会话。进程被强杀无法保证完成当次清理，下一次打开登录页会先清理旧会话。正式持久凭证仍通过 Android Keystore AES-GCM 加密保存，不进入普通导出或备份；不能宣称 WebView 从不产生临时明文数据。
- Activity 结果只返回一次性随机票据，凭证仅在进程内存传递；票据两分钟后无效，平台不匹配或重复领取均拒绝，进程中断需重新登录。没有密码输入控件、JS 原生桥、调试开关、文件 / content URL 访问、混合内容、SSL 错误忽略或外部自定义协议启动。
- 已移除整个 App 的 FLAG_SECURE，方便截图反馈。本地手动凭证输入保持遮罩。森空岛授权 JSON 在导航前立即隐藏原生 WebView，返回官网也等安全页面完成后才恢复，避免截图包含原始 token。官网手机号、昵称等个人信息仍需本人遮盖。

WebView 安全配置依据：[Android WebView 原生桥安全说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。森空岛当前官方静态脚本 `3158.cd61aa37.js` 模块 `91577` 显示 SK_OAUTH_CRED_KEY 存在于官方 Cookie / localStorage，OAuth host 为 web-api.skland.com；此次导入仍采用既有官方 account/info/hg 数据，不新增猜测的账号登录 API。

## 验收与故障

1. 安装 0.2.0，账号 → 添加账号 → 选平台 / 备注 → 官方网页登录并自动导入。正常完成官网验证后检查返回的昵称与 ID，再选择签到目标。
2. 另加一个森空岛账号，网页登录必须出现新会话，核对不同 ID 和各自角色 / 区服。尝试重复导入第一个账号应被拒绝，更新已有账号时登录另一账号也应拒绝。
3. 在首页 / 记录页截图应可保存；进入森空岛授权检测期间只能看到原生进度，不能看到 JSON。设备或工作资料策略仍可能另行禁止截图。
4. 取消、旋转、后台进程中断或票据过期时不能产生未经校验的新账号。官方页面可能不支持 WebView，验证码只能本人完成。遇到需要跳转官方 App 的页面可选网页的其他方式，或使用本地手动凭证备用入口。

网站登录适配与签到适配分开维护。页面打不开时记录平台、手机 Android / WebView 版本、允许截图的脱敏页面及固定错误提示，不提供账号密码或原始 Cookie。没有真实账号完成验收前，不能将“构建通过”写成“登录成功”。
