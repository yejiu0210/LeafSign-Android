# 网易云音乐与百度贴吧接入核查

**0.3.0 变更：用户已明确网易云目标为「黑胶乐签」，当前实现见 [integration-netease-vip.md](integration-netease-vip.md)。本文件中的 ordinary dailyTask 是 0.1.0 / 0.2.0 历史协议记录，已从生产执行路径移除。百度贴吧部分继续适用。**

核查日期：2026-10-17（Asia/Shanghai）。本文件区分协议资料、实现验证与真实账号验证。当前没有获得用户在手机本地完成的登录授权，**没有真实账号签到验证**。正式适配器发送真实 HTTPS 请求，不包含模拟成功分支。

## 证据与可信范围

| 项目 | 结论 |
|---|---|
| 官方公开签到开发文档 | 未发现这两个日常业务面向第三方的稳定开放 API 文档；不能将下面的社区协议当作官方支持的开放平台 |
| 官方业务入口 | 网易云音乐官方 App 每日签到 / [网易云音乐官网](https://music.163.com/)；贴吧各吧首页签到 / [贴吧官方签到排行榜帮助](https://tieba.baidu.com/sign/index)明确说明每日签到 |
| 协议依据 | 已通过 GitHub connector 读取下列项目当前分支的实际代码，而非只参考文章或搜索摘要 |
| 可用性 | 源码资料支持；必须在用户设备中导入凭证、身份校验并实际签到后才能标记“真实账号验证通过” |
| 日周期 | 以 Asia/Shanghai 每日 00:00 作为本地计划假设。未获得官方精确重置时刻的可靠规则文档，也未做真实账号跨日实验，因此所有任务写入 `resetVerified=false`。服务端明确状态优先；不会根据本地日期推断已签到 |

### 主要一手代码来源

1. [NeteaseCloudMusicApiEnhanced/api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)：MIT。当前仓库未归档；本次读到 `util/config.json` 修改于 2026-09-27，`util/option.js` 修改于 2026-07-15。`module/daily_signin.js` 修改于 2024-10-27。接口文件较旧，维护活跃不等于这个接口已被当前真实账号验证。
   - [daily_signin.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/daily_signin.js)：`/api/point/dailyTask`，`type=0` Android 日常签到；注释明确 200 成功、-2 重复签到、301 未登录。
   - [user_account.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/user_account.js)：`/api/nuser/account/get`，weapi。
   - [login_status.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/login_status.js)：另一账户状态入口 `/api/w/nuser/account/get`；本实现只使用上一条 `user_account` 对应入口。
   - [crypto.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/crypto.js)、[request.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/request.js)、[config.json](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/config.json)：weapi AES-CBC 两层加密、RSA 无填充及 `music.163.com` 路由。
   - [项目自己的文档](https://docs-neteasecloudmusicapi.focalors.ltd/)说明 type=0 / type=1 的业务区别；App 固定 type=0，不并发执行两个签到类型。
2. [lumina37/aiotieba](https://github.com/lumina37/aiotieba)：Unlicense。当前未归档；本次读取 `sign_forum/_api.py` 修改于 2026-10-01，`login/_classdef.py` 修改于 2026-10-02。下列代码是当前协议核心依据。
   - [login/_api.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/login/_api.py)、[login/_classdef.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/login/_classdef.py)：`https://tiebac.baidu.com/c/s/login`，字段 `_client_version`、`bdusstoken`；响应 `user.id/name/portrait`、`anti.tbs`。
   - [get_follow_forums/_api.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/get_follow_forums/_api.py)、[对应数据结构](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/get_follow_forums/_classdef.py)：`/c/f/forum/like`，`friend_uid`、`page_no`、`page_size`；`forum_list.non-gconforum/gconforum`、`has_more`。
   - [sign_forum/_api.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/sign_forum/_api.py)：`/c/c/forum/sign`；BDUSS、版本、吧名 kw、tbs；业务成功还必须有正数 `user_info.sign_bonus_point`。
   - [get_self_follow_forums/_api.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/get_self_follow_forums/_api.py)、[对应数据结构](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/get_self_follow_forums/_classdef.py)、[client.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/client.py)：只读 `https://tieba.baidu.com/c/f/forum/forumGuide`，`sort_type=3`、`call_from=3`、`Subapp-Type: hybrid`；`like_forum[].forum_id/is_sign`。该接口最多 200 个吧；本实现不把缺失的吧当成“未签到”。
   - [helper/crypto/sign.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/helper/crypto/sign.py)、[盐值常量](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/helper/crypto/const.py)、[core/http.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/core/http.py)、[const.py](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/const.py)：请求字段按键排序，拼接原始 `key=value` 后加 `tiebaclient!!!`，UTF-8 MD5；当前参考版本 `22.12.1.0`；官方客户端域名 `tiebac.baidu.com`。
3. `160002` 的重复签到语义由独立实际实现相互印证：[starudream/sign-task 的 Go API 包](https://pkg.go.dev/github.com/starudream/sign-task/pkg/tieba/api)与 [Alcatraz-Zhang/TiebaSignIn 的客户端源码](https://github.com/Alcatraz-Zhang/TiebaSignIn/blob/feat/keepalive-liskin/tieba_client.py)。仅参考此错误码含义；未复制其旧 HTTP 传输、客户端伪造参数、重试或补签策略。

## 网易云音乐（历史普通签到，已移除）

**准确业务：** 每日签到，`dailyTask(type=0)`。不是自动播放、音乐合伙人、黑胶任务、云贝兑换或连续签到奖励领取。不开启 type=1 的第二次网页签到。文档中的历史经验值未在真实账号上核实，App 不展示“已到账 N 经验”。

**登录与凭证：** 第一版使用用户自行在浏览器登录官方 `music.163.com` 后，手动向 App 本地输入 `MUSIC_U` 的值，或含 `MUSIC_U` 的 Cookie。可同时包含 `__csrf`。适配器只发送这两个名字的 Cookie；其他平台 Cookie 被忽略。没有实现短信密码登录、Cookie 自动刷新或读取官方 App 私有文件。失效时重新官方登录并在账号页更新。不要将凭证发到聊天中。

**身份校验：** 请求账户接口，必须 `code=200`、`account.id` 为正数；若同时出现 `profile.userId` 则必须与账户 ID 一致。昵称来自 `profile.nickname`，否则展示 UID。任务的 `ownerUserId` 保留稳定 UID；每次写入前重新校验身份与 UID，凭证换成另一个账号时停止，要求重新选择任务。

**实际请求：** App 内实现来源明确的 weapi 加密，通过 `https://music.163.com/weapi/nuser/account/get` 校验身份，再向 `https://music.163.com/weapi/point/dailyTask` POST 表单（加密内容 `type:0`、当前 CSRF）。TLS 校验保留，不跟随重定向，不使用任何 API 公共代理或自建中转。加密常量是协议公钥/固定参数，不是用户凭证。

| 响应依据 | 展示 |
|---|---|
| 明确业务 `code=200` | 签到成功 |
| 明确业务 `code=-2` | 今日已签到 |
| `code=301`，或身份接口没有已登录账户 | 登录失效 |
| 明确要求验证码、安全验证、操作频繁 | 需要手动验证，停止重试 |
| 缺失 code、未知 code、非 JSON、写入超时 | 结果待确认；不会当成已签 |

尚无独立可靠只读接口可确认**这一项 Android dailyTask**当天状态，因此 `query()` 返回 null。写入超时后不自动重发，也不通过另一个签到业务推断结果。用户可在官方 App 确认后重试本项。

## 百度贴吧

**准确业务：** 关注的贴吧普通每日签到。按每个贴吧分别执行；选择范围由本工具本地任务设置决定。没有使用 SVIP 一键签到、成长等级签到、补签卡、付费补签、发帖或回帖。

**登录与凭证：** 从用户自行登录的贴吧官网复制 BDUSS 值，或含 BDUSS 的 Cookie，粘贴到 App 本地输入页。可以包含 STOKEN，供网页状态查询使用；登录身份、分页关注列表和逐吧签到均以 BDUSS 客户端协议执行。适配器只接收并发送 BDUSS / STOKEN 白名单字段。没有假设能读取百度 App 的私有数据。更新凭证的方法是官方重新登录后重新复制。

**身份和选择：** `/c/s/login` 必须明确 `error_code=0`，且返回正整数 `user.id` 和格式有效的 `anti.tbs`；用户可看姓名和 UID 防止串号。通过 `/c/f/forum/like`，每页 200 个，按 `has_more` 继续，合并两类 forum_list；不会依赖最多 200 个吧的 forumGuide 来获取“全部”。若分页中断、结构变化或超过 100 页限制，则拒绝保存不完整列表。没有关注吧会显示“未绑定/尚未关注贴吧”。每个目标保存自己的 UID、吧 ID、实际吧名，签到前重新校验 UID。

**请求与状态：** 对经过核实的官方 HTTPS 主机 `tiebac.baidu.com` 发送签名表单。tbs 每次从当前账号取得，不跨账号缓存。客户端签到请求包含 BDUSS、`_client_version=22.12.1.0`、kw、tbs 和根据上述实际源码计算的 sign；每次写入至少相隔 5 秒。平台要求验证码时返回“需要手动验证”，不会尝试识别或绕过。

| 响应依据 | 展示 |
|---|---|
| `error_code=0` 且 `user_info.sign_bonus_point>0` | 签到成功 |
| 明确 `error_code=160002` | 今日已签到 |
| `error_code=0` 但缺失/为零的奖励字段 | 结果待确认，不猜测是重复签到 |
| 身份缺失、明确未登录/凭证过期语义 | 登录失效 |
| 明确验证码/安全验证/频繁限制 | 需要手动验证 |
| 明确封禁、禁止签到或吧关闭 | 失败；查看官方详情 |
| 未知错误码或响应结构 | 结果待确认 |

只读状态入口 forumGuide 中若明确找到了同一个 forum_id：`is_sign=1` 为今日已签到、`is_sign=0` 为待签到，其他值不做判断。如果 Cookie 缺 STOKEN 导致网页查询不可用，或目标不在最多 200 个结果中，只读查询返回 null；**缺失不是未签到证据**。普通签到本身仍可通过客户端协议执行。超时之后，统一执行器只能在只读查询明确返回未签到时有限重试，否则保留结果待确认。

使用网页签到状态前，还必须通过 [get_selfinfo_moindex 实际源码](https://github.com/lumina37/aiotieba/blob/master/src/aiotieba/api/get_selfinfo_moindex/_api.py)对应的 `https://tieba.baidu.com/mo/q/newmoindex?need_user=1` 校验网页身份，要求 `no=0` 且 `data.id` 与当前 BDUSS 登录得到的 UID 相等。防止用户意外混贴另一个账号的 STOKEN 时，把另一账号的已签状态当成本账号结果；身份不一致立即停止。

## 人工验证最短路径

1. 在手机安装测试 APK。在官方浏览器登录并自行提取对应平台 Cookie，在 App 本地账号页导入；不要将凭证发到聊天。确认显示的姓名及 UID 与自己当前账号一致。
2. 先只启用一个平台的一项任务。网易云用尚未签到的账号；贴吧先选一个已关注吧。点击一键签到，对照官方入口确认实际状态，再核对 App 记录。
3. 再执行同一项，核对“今日已签到”；选择贴吧部分吧后执行，确认未选吧没有被本工具写入。关注超过 200 个吧时检查分页获取数量。
4. 更新为无效凭证，应无法通过身份校验；把另一账号的凭证用于原目标时，应停止执行并提示身份改变。
5. 签到请求发出时切断网络，App 应留下结果待确认。恢复网络后，贴吧可查询到的任务先查询；网易云保持待确认，不自动重发。不因 HTTP 200 或空 JSON 写入成功状态。
6. 跨 Asia/Shanghai 00:00 前后，对照官方状态记录一次，才能将精确周期标记为真实账号验证。当前版本不得宣称已核实边界。

## 维护和验证边界

- 只修改对应适配器和针对该协议的测试；不要把未知错误码加入 ALREADY，除非有该平台当前实际响应或维护项目源码证据。
- weapi 请求与贴吧 MD5 拼接应使用独立协议向量测试；纯解析测试至少覆盖成功、已签、失效、验证码、零奖励、缺失字段及未知返回。模拟测试不等于真实平台验证。
- 不记录完整服务器响应、HTTP Cookie、请求体、tbs 或凭证；诊断仅保留固定 endpoint 名称和可解析的业务数字 code。用户输入的其他 Cookie 不发送。
- 本地凭证加密、账号去重、执行锁、日志存储及后台调度由主工程统一实现；适配器不自行持久化凭证。
- 源码协议会变化。重新阅读当前分支，核对平台域名、字段和身份链后再修改。使用固定域名允许名单，保留 TLS 证书校验和禁止跨站重定向。

## 上游许可声明

网易云 weapi 协议实现参考上述 MIT 项目。以下保留其原始许可：

```text
The MIT License (MIT)

Copyright (c) 2013-2022 Binaryify

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

贴吧协议参考 aiotieba，按其 [Unlicense](https://github.com/lumina37/aiotieba/blob/master/LICENSE) 许可使用；本工程没有引入 aiotieba Python 运行时依赖。
