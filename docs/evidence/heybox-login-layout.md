# 小黑盒登录布局与裸域跳转补充核查

核查日期：2026-10-17（Asia/Shanghai）。仅公开页面/脚本和匿名 GET，没有登录、验证码或个人凭证请求。

已检查实际交付0.4.0 APK的DEX：包含官方独立登录中心URL常量。该包从登录中心开始，用户截图中的社区电脑页属于后续导航；没有据此推断用户操作错误。

桌面Edge窄视口复查（高度650）：宽360时输入框横向范围96–330和30–232；宽393时112.5–346.5和46.5–248.5；宽412时122–356和56–258。三者document.scrollWidth均等于视口宽度，无横向裁切。这是官网响应式页面布局证据，不是Android WebView、验证码或真实登录通过证据。

## 截图裁切原因

官网 website 1.2.42 的 [官方 CSS](https://static.max-c.com/static/heybox/webapp/@heybox-spa/website/app/index-B413ANO2.css)，SHA256 `03763552d367bb8a054cde7fdcd107fe5efd310c3a41fcbe7667cdacc5f6ebc7`：

- `.website-login-mask` fixed/inset0，display:flex/center，overflow:hidden。
- `.website-login__panel` width570px、height328px，右侧二维码220px。
- 手机宽393 CSS像素时，居中的570px容器左侧约−88.5px，左侧用户协议选择框可落到视区之外；该弹窗没有手机断点。
- 官网 HTML 没有 viewport meta。

独立 [官方登录中心](https://login.xiaoheihe.cn/)：

- HTML viewport width=device-width,initial-scale1,maximum-scale1,user-scalable=no。
- CSS `index-q2aKZr-E.css` 在 max-width450px：装饰栏隐藏、main100%、form-shell100vw、code/password form300px、footer隐藏。
- 官方 JS `index-CscaK6Wd.js` 的 `$q(authenticated,force_relogin)` 仅当 force_relogin 不为字符串 true 才允许 bootstrap 已登录自动跳转。`force_relogin=true` 是已见参数。

登录中心 [官方 CSS](https://static.max-c.com/static/heybox/webapp/@heybox-spa/login/app/index-q2aKZr-E.css) SHA256 `ded08b52f781547bc0d094929d1dde7b51d6a394c927418da68444fdb8bfaf14`；[官方 JS](https://static.max-c.com/static/heybox/webapp/@heybox-spa/login/app/index-CscaK6Wd.js) SHA256 `bedb5bb2b786c8d87b576a6eb54ba61c26846a4da0b6d223676128656693c63f`。

## 登录后的裸域

用户报告官方登录后跳往 `https://xiaoheihe.cn/app/bbs/home`，又出现登录弹窗。

匿名移动 UA 读取以下三页都 HTTP200，无 Set-Cookie；页面正文完全相同：

- https://xiaoheihe.cn/app/bbs/home
- https://www.xiaoheihe.cn/app/bbs/home
- https://xiaoheihe.cn/

UTF8保存正文 SHA256：0e5518704a7bfebb579f02b308fe7644ef8438e531d8f9bf26cc92a437e25315。
它们均加载官方 website1.2.42 / web_version3.0 和同一官网脚本/样式。公开HTML可证裸域由同一官方网站提供，不能证明真实账号登录有效。
单独复核裸域社区请求，最终 URL 仍为 `https://xiaoheihe.cn/app/bbs/home`，无 Location 标头；没有 HTTP 跳转到 www。

## 官方 Cookie、跳转、origin

登录 JS `Jd(hostname)` 对 login.xiaoheihe.cn 返回 `.xiaoheihe.cn`；`Ve` 写 document.cookie 设置 expires、Path=/、Domain=Jd(hostname)。
`zH` 在认证成功时通过 Ve 同步 heybox_id/user_heybox_id/pkey/user_pkey 四项，默认 cookieTTL=d7。zH 不依赖本地存储来保存这四个凭证字段。
官方安全组件 Qq 单独生成 `x_xhh_tokenid`，Path=/、Domain=xiaoheihe.cn、14天；工具不得伪造。

因此原本对 www/login/api 的 CookieManager.getCookie 应能读到共享根域 Cookie；缺少裸域不能单独证明 Cookie 未取得。为了兼容 host-only/path 特例仍需加入精确裸域及可信当前路径。

普通非CLI登录的 h$ 仅将浏览器跳到解码 redirect_url，不把凭证拼到URL；mode=cli分支才附加 heybox_id/pkey/expire_at/state 等参数，本工具不需要采用这个分支。非CLI无redirect_url则跳 www.xiaoheihe.cn。
origin 由 i$ 从查询参数读入，传入短信发送和code登录；只发现 origin=wow 时不自动拼国家区号，其余情况下拼区号。普通网站跳转 origin为空，open网站使用heybox_open；未见必须设置其他origin才能获取共享Cookie的依据。

## 0.4.0 Android 代码的确定问题

0.4.0 OfficialLoginPolicy 对 Heybox 仅允许 www/login/api 三个域，漏掉 xiaoheihe.cn。

- shouldOverrideUrlLoading 拦截真实裸域主导航并调用 failPage。
- failPage 将 failedNavigation=true。
- detect() 遇到 failedNavigation 直接返回；因此即使之前登录已生成共享Cookie，自动和手动身份检测都可能被封住，直到重新加载。

## 0.4.1 实现与验证边界

已精确补齐裸域 HTTPS443 allowlist 和 Cookie 来源；对裸域 / www 的 `/` 或 `/app/bbs/home`（及末尾斜杠）返回地址，如果当前会话已有完整 Cookie，先读取并向官方身份接口校验，不必在社区页再次操作电脑登录弹窗。没有完整凭证不会凭地址变化添加账号。

独立手机登录中心增加 `force_relogin=true`；保留正常移动 UA。仅为小黑盒新增 WebView `useWideViewPort` / `loadWithOverviewMode` 和内置缩放，兼容无 viewport 的电脑官网；按钮“重新打开手机版登录”返回官方独立登录中心。不自动点击 / 勾选用户协议，不注入样式、密码或验证码处理，不把页面跳转当身份验证成功。Cookie 值保持私密，不将地址查询或完整响应写入日志。

开发侧以桌面 Edge 在 360 / 393 / 412×650 CSS 像素视区读取官方手机登录页，检查输入框矩形位于视区内，且页面 `scrollWidth` 等于视区宽度。仅进行了公开页面布局检查，没有输入个人手机号、密码、验证码，没有登录或执行签到。它不能替代 Android WebView、Redmi K90 Ultra / Android17 的实际登录、协议操作和签到验收。

截至本记录，用户报告的是 0.4.0 失败现象；0.4.1 的真实手机修复效果尚待用户验证。构建、静态测试与设备验证分别记录于 [验证记录](../verification.md)。
