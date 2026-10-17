# 森空岛接入核查与维护

核查日期：2026-10-17（Asia/Shanghai）。范围仅国服森空岛的游戏每日签到：明日方舟与明日方舟：终末地。原版 App 与分身登录同一平台，本工具按森空岛 `userId` 区分独立账号；不读取原版或分身的私有数据，不需要启动它们。

## 当前验证层级

| 项目 | 已完成 | 尚未完成 |
| --- | --- | --- |
| 官方页面及实际协议 | 已获取官方签到网页、官网引用的公开前端脚本；分别核对两游戏请求及状态格式 | 官方没有对第三方开放的稳定性承诺 |
| 无账号真实网络验证 | 官方绑定、方舟状态未登录返回；终末地游客日历；授权测试凭证失效返回 | 登录成功、真实角色绑定、真实签到奖励 |
| 账号隔离实现 | 每次执行用本次凭证解析 `userId`，检查目标的 `ownerUserId`，重新获取绑定并匹配角色完整 ID | 两个本人真实账号及真实 Android 手机验收 |
| 自动签到周期 | 两游戏官方规则分别明确 UTC+8 每日一次、每月 1 日 00:00 重置月进度；实现遵从实时服务端签到状态 | 每日自然日 0 点由 UTC+8 每日规则推导，没有另写“每天 00:00 重置”，因此保守标 `resetVerified=false` |

**没有执行真实账号签到，不能将构建通过、模拟单元测试或未登录网络探测视为真实账号验证。** APK 中所有结果来自实际请求或本地未执行状态，没有生产模拟成功分支。

## 官方入口与证据

- [森空岛官网](https://www.skland.com/)：登录鹰角通行证。
- [明日方舟每日签到](https://game.skland.com/arknights/sign-in)。官方前端路由明确注册 `/src/pages/arknights/sign-in/index.tsx`。
- [终末地每日签到](https://game.skland.com/endfield/sign-in)。已实际获取 HTML，标题为《明日方舟：终末地》游戏工具。
- [官方客服：如何绑定游戏角色](https://customer-service.hypergryph.com/app/skland/question/ART176759521161909512843765)：官服角色自动绑定，B 服需本人按官方流程验证绑定。
- [官方客服：签到后没有奖励](https://customer-service.hypergryph.com/app/skland/question/ART176759524527362866962367)：先检查签到页面选中的绑定角色。
- [官方客服：本地时间异常](https://customer-service.hypergryph.com/app/skland/question/ART176759467144396093787760)：启用手机系统自动时间。

直接协议证据来自官网 HTML 实际引用的资源，均于核查日期下载检查，不采用搜索摘要作为协议依据：

| 官方资源 | 本次核对内容 | SHA-256 |
| --- | --- | --- |
| [游戏工具服务定义](https://assets.skland.com/_static_assets/game-tools/dist-BBdbtn84.js) | 方舟 `Cp.getCheckInInfo/checkIn`；终末地 `Al.getCheckInInfo/checkIn`；两套独立 Codec | `D817AF3530A921453BA07D29A8448957C12A9E6E81932A20F4F23E6E517F152D` |
| [官网公共请求库](https://bbs.hycdn.cn/skland-fe-static/skland-bbs/7905.3be0d11f.js) | 签名材料拼接顺序、HMAC 后 MD5、platform=3/vName=1.0.0、官方域名、签名过期刷新 | `C892E8D380FCF37CF1166946B29AA330A634EDF6716E73D758786398B3E976ED` |
| [绑定与登录库](https://bbs.hycdn.cn/skland-fe-static/skland-bbs/3158.cd61aa37.js) | `/api/v1/game/player/binding`、`generate_cred_by_code`、官方 OAuth host | 动态文件名是本次版本证据 |
| [方舟签到状态模型](https://assets.skland.com/_static_assets/game-tools/signIn-VUHuQLUU.js) | 日历的 available 状态、服务端 records | 动态文件名是本次版本证据 |
| [终末地签到状态模型](https://assets.skland.com/_static_assets/game-tools/checkIn-DMQbaHWi.js) | `hasToday`、角色初始化及状态刷新 | 动态文件名是本次版本证据 |
| [两游戏官方中文规则](https://assets.skland.com/_static_assets/game-tools/locales/zh_Hans.json) | 方舟 `ak_sign_timezone_desc/ak_sign_rules_content_detail1/2`；终末地 `sign_utc_time/sign_tip_1..5`；两者均每日一次、UTC+8、每月 1 日 00:00 月进度重置、游戏内邮件发奖可能延迟 | `BFC6ECF463AC9CA2573DA319AB006EA09EFD8E054E7D13CA22A8B4E2949B399B` |

这些公开脚本不是官方第三方 API 文档。该适配器使用从官方前端与社区实际实现交叉核对的协议，属于非官方接入；接口变化时应重新核对。

## 本机授权与凭证更新

第一版使用手动导入本人凭证，实际授权仍通过鹰角官方服务器完成：

1. 在手机或本人电脑的浏览器打开 [森空岛官网](https://www.skland.com/)，完成官方登录与需要的验证码。
2. 同一浏览器访问 [官方凭证查看接口](https://web-api.skland.com/account/info/hg)，复制 `data.content` 值。
3. 在本 App 的“账号 → 添加森空岛账号”本地输入。可粘贴纯 Token 或完整官方返回 JSON。不要发到聊天、网盘或其他服务。
4. “校验并添加”实际向官方换取 `cred` 与签名 Token，查询森空岛 `userId` 与绑定角色后才保存。检查账号 ID、角色昵称和区服，备注“主账号”。
5. 添加分身账号前，在用于获取 Token 的官方浏览器退出当前账号并登录另一个账号，重复上述步骤，备注“分身账号”。以工具内展示的两个不同森空岛 ID 为准，不以备注作为身份依据。
6. 失效时重新走官方登录，在 App 对应账号中“更新凭证”。更新后的身份必须仍是原账号。

可选本机 JSON：`{"token":"本人官方Token","dId":"本人官方网页请求中的设备标识"}`。只在平台要求设备验证时按官方网页本人会话提供 `dId`；App 不伪造设备指纹、不运行第三方数美中转、不绕过验证码。也支持 `{"cred":"本人官方cred"}`，用官方 `GET /api/v1/auth/refresh` 取得当前签名 Token，再独立查询身份。手工导入字段只在加密凭证中保存，不作为普通目标 metadata。

官方查看接口会提示该凭证能代表通行证登录且不应泄露。其失效时间没有稳定官方承诺，第一版不声称固定 30 天或永久有效。受 WAF 或验证码阻止时停止该项并显示“需要手动验证”，请本人到官网处理后再更新；不会使用公共中转。

## 凭证生命周期与身份

仅向以下 HTTPS 精确域名发送有关凭证：

- `as.hypergryph.com`：`POST /user/oauth2/v2/grant`，JSON `appCode=4ca99fa6b56cc2ba, token, type=0`；必须 `status=0` 且 `data.code` 存在。
- `zonai.skland.com`：`POST /api/v1/user/auth/generate_cred_by_code`，JSON `code, kind=1`；必须 `code=0` 且 `data.cred/data.token` 存在。
- `zonai.skland.com`：`GET /api/v1/user/teenager`，取 `data.teenager.userId` 作为森空岛身份；不信任导入 JSON 声称的 userId。
- `zonai.skland.com`：`GET /api/v1/game/player/binding`，必须 `code=0` 且 `data.list` 是数组。

每次执行与查询重新建立当前会话，不保存跨账号共享的 cred 或全局签名密钥。目标 metadata 仅保存 `ownerUserId/uid/roleId/serverId` 等身份与任务字段。凭证由统一 Keystore 加密存储层管理。

签名材料：`path + 原始POST体或原始GET查询串 + timestamp + 固定顺序头JSON`；头 JSON 顺序为 `platform,timestamp,dId,vName`。HMAC-SHA256 结果编码成小写十六进制字符串，再对该字符串取 MD5。平台请求统一使用已核实的 web 参数 `platform=3,vName=1.0.0`，`dId` 默认为空或本人显式导入的官方值。签名与发送共用同一序列化 body。该算法不是本项目发明，已对照官方公开请求库；仍需本人真实账号验证最终兼容性。

## 两款游戏分别处理

| 项目 | 明日方舟 | 明日方舟：终末地 |
| --- | --- | --- |
| 绑定分组 | `appCode=arknights` | `appCode=endfield` |
| 角色 | 每个 `bindingList[]` 的 `uid,nickName,channelMasterId,channelName` | 遍历全部 `bindingList[].roles[]` 的 `roleId,nickname,serverId,serverName`，必要时用官方 `defaultRole` 对象 |
| 目标唯一 ID | appCode + binding uid + channelMasterId | appCode + binding uid + roleId + serverId |
| 状态 GET | `/api/v1/game/attendance?gameId=1&uid=...` | `/web/v1/game/endfield/attendance` |
| POST | `/api/v1/game/attendance`，当前官方前端只发送 `{"uid":"..."}` | `/web/v1/game/endfield/attendance`，零长度请求体 |
| 额外角色头 | 无，uid 明确指定角色 | `sk-game-role: 3_{roleId}_{serverId}`，GET 和 POST 都带 |
| 签到响应 | `data.awards[].resource.name` 与 `count` | `data.ts,awardIds[],resourceInfoMap`，按奖品 ID 关联名称数量 |
| 状态依据 | daily calendar 的 available；没有可领项时必须有与 `currentTs` 同一 UTC+8 日期的 `records[].ts` 才认“已签到” | 必须存在真正 Boolean 类型 `data.hasToday`；字段缺失不能当 false |
| 无绑定 | 完整有效绑定列表中没有该游戏或 bindingList 为空 →“未绑定” | 同左。已绑定但缺少角色另行提示本人确认，不伪造角色 ID |

**不能把终末地绑定 uid 或 channelMasterId 当成角色 roleId/serverId。** 同时不调用森空岛“登岛检票”或积分签到，避免扩展任务范围。

两款游戏的官方中文规则分别说明：签到奖励发送到当前绑定角色的游戏内邮箱，可能因网络或维护延迟，通常要求最迟 24 小时内发放；邮件有效期 30 日。因此 App 显示接口确认的签到与奖励明细，不声称邮件已经到账或已经在游戏内领取。

旧开源程序中存在方舟 body 额外传 `gameId=channelMasterId`、终末地使用 `/api/v1/...` 的版本。本实现优先采用本次直接取得的官方前端格式；不在写请求超时后通过切换路径盲目再签到。

## 结果判定与重试边界

- `SUCCESS`：明确业务 `code=0`，且对应游戏奖励结构完整并可解析至少一项名称和数量。奖励信息不代表邮件已经在游戏内领取。
- `ALREADY`：只读状态明确确认，或非零返回明确包含“请勿重复签到”等已核对的业务文本。**不会单凭 10001 或 HTTP 200 判断已签到。**
- `UNBOUND`：完整绑定列表证明未绑定；不算签到失败。
- `INELIGIBLE`：官方绑定字段明确角色已删除或封禁，停止写请求。
- `EXPIRED`：平台 code=10002；授权 status=3；或明确失效文本。官方请求库遇到 10000 会尝试刷新签名会话，但这不足以证明登录已失效，因此本实现单独遇到 10000 仍保留“结果待确认”，不猜测其完整业务含义。
- `MANUAL`：验证码、风险验证、设备时间验证或 HTTP 403/412/429；停止该项重试。
- `UNKNOWN`：缺失业务码、结构变化、未知码、成功码但无可确认奖励、无法确认的日历、超时或非 JSON。只记录代码和白名单字段存在性；不记录响应全文、凭证、头或原始请求体。
- 网络超时后统一执行器可调用本适配器的只读 `query()`。如果仍未知，保留“结果待确认”；不会自动重放写请求。本人后续主动重试时也先查询实时状态。

终末地游客日历即使返回 `code=0,hasToday=false` 也不能证明某账号状态。本实现 query 和 execute 在任何状态查询之前都必须校验真实森空岛身份及目标归属。

## 本次真实无账号请求记录

以下请求无真实凭证，也没有执行签到写入：

| 请求 | 实际结果 |
| --- | --- |
| GET `https://zonai.skland.com/api/v1/game/player/binding` | `{"code":10002,"message":"用户未登录","timestamp":"1792217380"}` |
| GET `https://zonai.skland.com/api/v1/game/attendance?gameId=1` | `code=10002,message=用户未登录,timestamp=1792217381` |
| GET `https://zonai.skland.com/web/v1/game/endfield/attendance` | `code=0,data.currentTs=1792217380,data.hasToday=false`，31 项日历，所有 available/done 为 false；游客可见奖池，**不是账号验证** |
| POST 官方 grant，使用明确无效的测试值 `AAAAAAAAAAAAAAAAAAAAAAAA` | `{"msg":"登录已过期，请重新登录","status":3,"type":"A"}`；这确认失效判定，不是登录成功 |

空 Token 和非 Base64 测试字符串返回仅含 msg 的请求格式错误；本实现不会将缺失 status 的响应当成成功。

## 真实账号最短验收

1. 本机依次添加两个不同森空岛 ID，分别勾选方舟与终末地实际角色/区服。某游戏没绑定应看到“未绑定”。
2. 先只启用主账号的一个方舟角色，点击一键签到，在记录页核对账号、UID、区服、实际奖励；同时打开官方同角色签到页核对。
3. 再启用分身账号及终末地角色，逐项核对；主账号凭证绝不能改变分身任务结果。用相同 Token 再添加应被统一账号层拒绝。
4. 同日重试应由 GET 确认已签。检查游戏内邮件或奖励详情确认实际到账，不能仅凭接口成功假定游戏内邮件已领取。
5. 断网/超时、错凭证和进程退出分别检查“失败或待确认/登录失效/执行中断”；未知返回不得改成成功。

## 开源审查与许可证

协议交叉核对使用 [FrostN0v0/nonebot-plugin-skland](https://github.com/FrostN0v0/nonebot-plugin-skland)，commit [`886d19decf32a7bfb2980adba9254b41d1aadb2f`](https://github.com/FrostN0v0/nonebot-plugin-skland/tree/886d19decf32a7bfb2980adba9254b41d1aadb2f)，2026-09-22，241 次提交，MIT。重点文件：`api/request.py`、`api/login.py`、`schemas/binding.py`、两游戏 sign schema、`services/binding.py`。本适配器为 Kotlin 独立实现，优先采用当前官方网页核实的格式，不直接搬运该项目的日志或重试实现。

另审查 [sjtt2/endfield_auto_sign](https://github.com/sjtt2/endfield_auto_sign/tree/05b8fd52e83fea67aec755e127cb9f006a531f80)，commit `05b8fd52e83fea67aec755e127cb9f006a531f80`（2026-07-27）。仓库未提供 LICENSE，仅参考协议差异，没有复制代码。[sglkc/endfield-auto-daily](https://github.com/sglkc/endfield-auto-daily) 的只读状态用法仅用于查找官方入口，海外服务域名未加入此国服适配器。

维护时先下载当前官方签到页实际引用的服务文件，核对两套 API、GET 状态、绑定 schema、签名材料。用独立测试 fixture 验证解析后，再由本人在 App 内验收真实账号；禁止以旧错误码表猜测新业务意义。

FrostN0v0 MIT notice（保留来源许可）：

```text
MIT License
Copyright (c) 2025 FrostN0v0

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
