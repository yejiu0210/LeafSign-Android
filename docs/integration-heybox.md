# 小黑盒每日签到

0.4.0 首次加入，0.4.4 因网页登录始终不通过而移除，0.5.5 起改用扫码登录重新加入，0.5.6 修正扫码请求的签名。核查日期：2026-11-22（Asia/Shanghai）。

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

## 签到

| 步骤 | 请求 |
| --- | --- |
| 身份校验 | `/account/restore_login` |
| 查询今日状态 | `/task/list_v2/` |
| 签到 | `/task/sign_v3/sign` |

- 签到前先查任务中心，只有明确是「待签到」才发送签到；签到后再查一次，确认完成才记为成功。
- `status` 为 `login` / `relogin` 时记为登录失效，提示重新扫码登录。
- 只执行普通每日签到，不调用补签、H 币、抽奖、领奖、分享或评论接口。
