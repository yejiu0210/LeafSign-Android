# 森空岛手机登录修复调研

核查日期：2026-10-17。全部请求均未提供用户账号或凭证；证据级别为官方公开代码与匿名 HTTP 请求。没有真实账号登录、真机 WebView 或 App 授权成功证据。

## 已证实的问题与修复方向

对 `https://www.skland.com/` 使用 Android WebView UA，服务端返回 **302 Location: https://m.skland.com/**。自动跟随后最终 HTTP 200，1542 字节；桌面 UA 不跳转，3950 字节。v0.2.0 的主框架白名单未包含 `m.skland.com`，因此会阻止这一正常官方移动跳转。这是实际接入缺陷；不能单独解释截图中连原生标题栏都消失的现象，原生首帧/布局也需修复。

本次对比 UA：

```
Mozilla/5.0 (Linux; Android 17; K90 Ultra Build/TEST; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36
```

`m.skland.com/assets/env.js` 根据官方 m host 或 `/h` 路径选择 mobile 环境。其公开 route map 中 mobile basename `/h`，`/` 是 Download，`/login` 是 Login。因此普通社区首页不适合作为手机登录起点。`https://m.skland.com/h/login` 匿名 HTTP 200、不跳转；但 Login 页 chunk 实际只是渲染 Home，未证实会自动弹出登录框，需要用户点官网登录。

优先建议用真正的官方通行证登录页 **https://user.hypergryph.com/login**：同一 Android UA 下 HTTP 200、不跳转，HTML 1023 字节（随后加载较大 JS）。其官方 user-center 前端有明确 `/login` route，初始化 `window.HG_SDK`，并且自身用以下调用取得本次登录 token：

```javascript
const result = await window.HG_SDK.User.getToken();
if (result.result === true && typeof result.data?.token === 'string') {
  // 此 token 是本次工具 WebView 官方网页登录产生的鹰角通行证 token。
}
```

只允许在精确的 `https://user.hypergryph.com` origin 下进行这一读取。可把异步结果暂存随机命名的内存 JS 属性，通过 evaluateJavascript 回读并立即 delete；不得写入 DOM、URL、日志、Intent 或 SavedState。得到 token 后走已有 SklandAdapter 的 token → 官方 grant → cred + 签名密钥 → 用户/绑定校验流程。无需把 user.hypergryph.com 的 Cookie 发往 skland 域。

该 portal 当前成功路径也有官方源码依据：pI 登录布局检测已登录用户且已同意最新协议（或处于注销冷静期）后，执行 `history.replace(decodeURIComponent(query.redirect) || '/userInfo')`；未同意新协议先弹协议更新。可以定时仅检查精确 origin 下的非敏感 `location.pathname === '/userInfo'`，达到后才隐藏 WebView 并读取 token，避免在仍输入验证码时每次自动检查都隐藏页面。React SPA 的 history 改动不一定产生 WebView onPageFinished，因此不能仅依赖该回调。路径也只能触发检查，不可替代 adapter 身份与资格校验。

## 官方 SDK 当前实现

- HG web SDK bootstrap 当前公开版本 3.0.3，banner Published at 2026-09-30 15:23:48。
- bootstrap 同时动态装载 HG_ACCOUNT_SDK；本次未版本化默认文件对应 3.5.0 的分块路径。
- web SDK 的 `User` 代理到 `window.HG_ACCOUNT_SDK.User`；user-center 自身对 `getToken()` 结果检查 `result` 与 `data.token`。
- account SDK 模块 7933 的 getToken() 调模块6474.U2('hg')，后者向 apiServer **POST `/account/info/hg/check`**，严格判 `code === 0` 后返回 `data.content`。
- user-center 下 apiServer 默认 **https://web-api.hypergryph.com**。Skland 官网初始化会显式把同一 SDK apiServer 设为 **https://web-api.skland.com**，两个站点的服务端会话不能混用。
- 当前匿名 `POST https://web-api.hypergryph.com/account/info/hg/check` 返回 HTTP401：`{"message":"Unauthorized","statusCode":401}`。这说明端点受登录保护，不是登录成功证据。
- 源码还存在旧兼容分支 GET `/user/info/v1/token_by_cookie`，但本次匿名 `https://as.hypergryph.com/user/info/v1/token_by_cookie` 实际返回 HTTP404 `404 page not found`，**不要把它硬编码成已验证登录能力**。应调用 SDK 决定其实际兼容路径。
- 当前匿名旧 `GET https://web-api.skland.com/account/info/hg` 也是 HTTP401，不能让新 portal 登录后继续假定旧 skland 域有会话。
- 森空岛官网自身 `User.UI.login({oauth:{appCode:'4ca99fa6b56cc2ba',grantType:0}},callback)`；成功 code0 且 oauthInfo.code 才走 `generate_cred_by_code`。appCode 是官网自身的客户端标识，不能据此声称本工具有正式第三方 OAuth 注册授权。

## 原版 App 跳转与授权边界

官方 bridge 公布的 schema 包括 `skland://main/home`、`skland://web?url=...`、detail、profile、cate。这些是启动或打开内容页面的功能。公开 bridge 的 `openAuthPage` 只在森空岛自己的原生 App 环境中可用，调用 `SKNavigation.openAuthenticationPage`；普通 web 分支直接返回 false。

本次未找到公开第三方 Android 登录 SDK、第三方 client 注册入口、允许任意 App 接收的授权 callback/redirectUri，或可把原 App 登录状态授权给本工具的正式协议。不能把 launch intent、deep link 打开成功当作获取授权。可以提供“打开官方 App”帮助完成官方操作，但它不自动向本工具回传凭证，也不能据此读取分身私有文件。

## WebView 子资源检查

没有在 HG SDK 主包发现必须依赖 blob/about:blank 的登录实现。m 站公共代码存在读取同源 iframe Cookie 的工具，并明确忽略 iframe 初始 `about:blank` load；这不能证明它是登录所必需，但也不宜用仅允许 HTTPS 的全局子框架策略阻止安全的 `about:blank` 初始化。验证码使用官方 geetest4，通过动态 SDK chunk 加载。不得绕过验证码或忽略 SSL 错误。

## 官方资源与本地 SHA-256

| 资源 URL | 本地文件 | SHA-256 |
| --- | --- | --- |
| https://www.skland.com/ → https://m.skland.com/ (Android UA) | work/skland-mobile.html | AC68B432F082DFEBA5D09C56C15345DCF93FD5C698D66B0A544DA67E58DF6A58 |
| https://www.skland.com/ (desktop UA) | work/skland-desktop.html | E923B56DF1DB03CCE0A7EF34AED2C6E6335AEEE41F7F93A24E75D28D9CAEDB9C |
| https://user.hypergryph.com/login | work/hg-login-mobile.html | 36678C87B39487D43939B968A59DC83E356D9D55D7E0D542C031C3ACDB67EA05 |
| https://web.hycdn.cn/user-center/index.62f8883d.js | work/hg-user-center.js | D68EB0CCE8E051998331BDA3EDB0F7960F6C34FE353BFA0324E0AFBEB5512422 |
| https://web.hycdn.cn/hg_web_sdk/lib/sdk.entry.js | work/hg-sdk.entry.js | 3F6963249801A6F8B810DAC23227C9DF5E50CF383642341FDA22E728FC94CFC2 |
| https://web.hycdn.cn/hg_web_sdk/lib/3.0.3/hg-web-sdk.min.js | work/hg-web-sdk.min.js | 7D33CA2CAC765F5A7C960554FFD8EDF02B857AC5F4E5C2B68020A677F980FEB0 |
| https://web.hycdn.cn/hg_account_web_sdk/lib/hg-account-web-sdk.min.js | work/hg-account-web-sdk.min.js | E92144981EF80B214A13D334043DB95204AE9700E46722E7003F05F08892B29C |
| https://web.hycdn.cn/skland/site/index.a9fea7.js | work/skland-mobile-index.js | F5F670850BB3C5F2A56E8B566A62B681522EE0DE3473C321E9531754E0EEB8D6 |
| https://web.hycdn.cn/skland/site/7285.29150f.js | work/skland-mobile-login-page.js | 59516C2C3C9C2456737454215A499EC04699678DA9781C3A1325BE6E2C265A0A |
| https://m.skland.com/assets/env.js | work/skland-mobile-env.js | E8B61642639C471256029466484DDA0E2CB2E4A2BED482B334270B087EC83DFC |

以上 hash 对应本次下载字节，用于复查公开前端版本，不是接口稳定性承诺。

## 附：网易公开首页 iframe 证据

`work/netease-vip-research/login-mobile.html` 当前公开 HTML 明确包含 `<iframe name="contentFrame" id="g_iframe" ... src="about:blank">`，之后脚本通过 `_iframe.contentWindow.location.replace(_url)` 载入内容；重建 iframe 分支也先设置 `src='about:blank'`。全局仅允许 HTTPS 的子框架策略可能阻止这一正常初始化。可仅对子框架允许 about:blank，顶层仍仅允许对应官方 HTTPS 域，不能据此开放顶层任意scheme。该 HTML 没有 blob: 使用证据。`_getBase()` 对 login 等类型会选择 `y.music.163.com`，需核实相应精确官方导航host。没有因此完成网易真实账号登录验证。
