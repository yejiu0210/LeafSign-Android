# 小黑盒每日签到

0.4.0 首次加入，0.4.4 因网页登录始终不通过而移除，0.5.5 起改用扫码登录重新加入，0.5.6 修正扫码请求的签名，0.5.14 起改为「检测 + 打开 App 签到」。核查日期：2026-11-25（Asia/Shanghai）。

## 状态

- 0.5.6 起本人真机扫码登录成功。
- 每日签到的跨日重置时刻尚未拿到官方证据，以任务中心的实时状态为准。

## 登录：扫码

协议来自官方登录页 `login.xiaoheihe.cn` 的脚本，并做过匿名实测；请求参数和签名参照公开实现 [XiaHouSheng/heybox-core](https://github.com/XiaHouSheng/heybox-core)。

| 步骤 | 请求 | 说明 |
| --- | --- | --- |
| 生成二维码 | `api.xiaoheihe.cn/account/get_qrcode_url/` | 返回 `result.qr_url` 和有效期 `expire`（秒）。二维码内容就是 `qr_url`，轮询用它的 `qr` 参数 |
| 轮询 | `GET /account/qr_state/?qr=…` | 约 1.5 秒一次。`result.error`：`wait` 等待扫码，`ready` 已扫码，`ok` 已确认，其他值视为过期；外层 `need_google_check` 表示开启了二次验证 |
| 取凭据 | 确认响应 | 依次从 `result`（`profile.heybox_id` 或 `account_detail.userid`，以及 `pkey`）、响应的 Set-Cookie、补调一次 `restore_login` 中获取 |

- 所有请求都带网页端参数（`os_type=web`、`x_app=heybox_website`、`version=999.0.4` 等）和 `hkey` / `_time` / `nonce` 签名（`HeyboxWebSigning`）。0.5.5 没带签名，服务端回了 `ok` 却不下发凭据。
- 仍取不到凭据时，提示里只附带字段名、Cookie 名和 `restore_login` 状态，不含任何值，便于排查。
- 网页登录不再提供：0.4.x 的手机 WebView 登录有弹窗裁切、跳转后要求重复登录、授权不生效等问题，始终未通过验收。

## 凭证

| 字段 | 用途 |
| --- | --- |
| `heybox_id` / `user_heybox_id` | 账号 ID，必须再经服务端校验 |
| `pkey` / `user_pkey` | 登录会话凭证 |
| `x_xhh_tokenid` | 官方安全组件生成的风控 token，有就保留，不自行伪造 |

只保存这几个名字的 Cookie，凭证用 Android Keystore 密钥加密，只发往 `api.xiaoheihe.cn`。

**pkey 轮换**：`restore_login` 每次都可能返回新的 pkey，旧值随即失效。0.4.x 一次签到会多次校验，并在适配器和 Runner 两处各自回写，最新 pkey 可能没保存下来，下次就报「授权已过期」。现在每轮只在身份校验时调用一次 `restore_login`，新凭证通过 `Identity.updatedCredential` 交给 Runner 统一保存，后续请求都用新值。

## 签到：只检测，打开 App 完成

| 步骤 | 请求 |
| --- | --- |
| 身份校验 | `/account/restore_login` |
| 查询今日状态 | `/task/list_v2/` |

**0.5.14 起一叶签不再发送签到请求**，只检测今天签了没有：
- 已签到：显示「今日已签到」。
- 未签到：显示「待打开 App」，今日页给出「打开小黑盒签到」按钮。打开小黑盒 App 时它会自己完成签到；切回一叶签后会单独复查这一项。定时签到后如果还没签，会发一条通知，点击直接打开小黑盒。

**为什么不再用接口签到**（2026-11-25 核查）：签到接口 `/task/sign_v3/sign` 现在只接受 App 端请求。参考仍在维护的开源实现 [Cansiny0320/heybox-daily](https://github.com/Cansiny0320/heybox-daily)，它需要三样东西：
1. 从 App 原生代码逆向出的签名算法（魔改 SHA-512，锁定 App 1.3.398 版本，App 更新就可能失效）；
2. 运行小黑盒风控 SDK 生成的设备令牌 `x_xhh_tokenid`；
3. App 端手机验证码登录（其文档注明可能会让手机上的小黑盒退出登录）。

第 2 点等于绕过平台的风控检测，三者都很脆弱，所以一叶签不采用。网页端的任务列表读取仍然可用。

- `status` 为 `login` / `relogin` 时记为登录失效，提示重新扫码登录。
- 不调用补签、H 币、抽奖、领奖、分享或评论接口。
