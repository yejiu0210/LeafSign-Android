# 哔哩哔哩：大会员等级加速包签到

核查日期：2026-10-17（Asia/Shanghai）。接入级别：**官方当前网页源码支持，已验证无凭证只读接口；未完成真实账号签到验证**。解析器测试使用明确标记的合成响应，不代表账号实测。

## 精确业务及当前官方依据

工具内名称为“大会员等级加速包签到”。官方当前名称为“专属等级加速包（每日10经验）”，位于[手机会员中心的领经验模块](https://big.bilibili.com/mobile/index?location=drawExperienceModule)。[大会员官方公告](https://www.bilibili.com/opus/851175072551927841)于 2023-10-11 发布该权益；公告中的链接正是上述入口。

2026-10-17 直接读取该入口实际引用的[官方 JavaScript](https://s1.hdslb.com/bfs/static/jinkela/vip-home-page/static/index-v6lVeOq3.js)，SHA-256：

`fc3f10fd93ab7fdff1f201ae92414ed12a55286d82ae48cf6c01b6ef8f94c77c`

在此版本中交叉核对的短字段与业务标记：

- 模块：`drawExperienceModule` / `Experience` / `experience.state`。
- 条件：有效大会员、正式会员等级至少 LV1；当前文案要求观看视频 1 分钟，日限一次。2023 年公告只说观看任意一个视频，因此实现以本次读取的现行文案为准。
- 状态：0 可领取；1 已领取；2 尚未满足观看条件；3 失败；4 处理中。
- 执行：`POST /x/vip/experience/add`；结果还检查 `is_grant` 和 `type`。
- 这是同一权益的每日领取，没有发现必须先执行的独立“购买加速包”“开通加速包”或另一条“签到”请求。会员中心其他积分签到是独立业务，本适配器没有调用它。
- `is_grant=true,type=0` 时官方显示经验已领取；`is_grant=true,type!=0` 时官方提示系统维护、经验稍后发放。工具将受理结果和到账提示分别展示，未自行推算“已增加10经验”。

这证明了目标业务的对应关系，不证明用户账号资格、当前凭证或实际奖励到账。若用户官方页面出现不同名称、独立步骤或规则，应提供**已遮盖个人信息的入口截图或说明**后复核；不得改用其他签到。

## 登录、身份及凭证

第一版使用本地导入官方网页登录 Cookie；不收集账号密码，不要求在聊天发送凭证。至少需要 `SESSDATA`、`bili_jct`；若有 `buvid3`，提交请求时同时带官方当前使用的 `buvid`。仅保留已列明的 B 站 Cookie 名称，拒绝控制字符与多行输入。所有请求严格发往 `https://api.bilibili.com:443`，关闭重定向、环境 Cookie 与 HTTP 日志。

官方网页登录后，可在本人电脑浏览器的开发者工具 → 网络 → 已登录的 `api.bilibili.com` 请求 → 请求标头中复制 Cookie，并通过安全的本地方式传至本 App 的账号输入框。请只在自己的设备操作；不要将其贴进聊天、问题单或普通文件。Cookie 过期后重新登录官方页面，再使用账号页“更新凭证”。这一步不读取手机 B 站 App 的私有目录。

身份校验使用 `GET /x/web-interface/nav`：要求业务 `code=0`、`data.isLogin=true`、正数 `mid`；展示昵称和 UID。任务记录保存 `ownerUserId`。执行前的官方会员中心返回 UID 必须与任务所属 UID 一致，否则停止。

## 请求及结果判定

| 步骤 | 请求/字段 | 工具行为 |
| --- | --- | --- |
| 读取资格与当日状态 | `GET /x/vip/web/vip_center/v2?is_selected=false&select_modules=VipExclusive%2CBigPoint%2CComicShow`，读取 `vip_info` 与 `experience` | `mid=0` 为失效；`vip_status!=1` 为资格不足；没有该模块为尚未接入/需要入口复核 |
| 签到前检查 | `experience.state` | 只有明确为 0 才发起写请求；1 为今日已领取；2 提示手动观看视频后重试；4 为结果待确认 |
| 领取等级加速包 | `POST /x/vip/experience/add`，表单 `csrf=bili_jct`，存在时带 `buvid=buvid3` | `code=0` 仍须校验布尔 `is_grant=true` 与数字 `type`；字段缺失或格式改变为结果待确认 |
| 已领取响应 | `code=69198` | 再次只读查询；仅当官方当日状态明确为 1 才显示今日已签到；否则待确认 |
| 凭证问题 | `-101/-401`，或 `-111` 校验失败 | 停止该项，提示更新凭证；不记录凭证或原始响应 |
| 需验证/限制 | `-352/-412/73319`，或 `6034007` 请求频率限制 | 停止重试，提示官方手动处理；不绕过验证码 |
| 其他错误、超时 | 未列明业务码或网络异常 | 未知码绝不归类“已签到”；超时后调度器先用只读查询恢复结果 |

`-352/-412` 含义来自社区的[公共错误码记录](https://github.com/pskdje/bilibili-API-collect/blob/main/docs/misc/errcode.md)；`73319` 的手动验证处理在本次读取的官方 PC 卡券包脚本中可见。`69198/-111/6034007` 的领取接口记载见[大会员操作](https://github.com/pskdje/bilibili-API-collect/blob/main/docs/vip/action.md)。无法穷举平台业务码，未知响应始终保守处理。诊断仅包含固定接口名、数字业务码和状态，不存储服务端原文。

官方只确认每天一次；本次没有取得明确时区/重置钟点的官方书面规则。社区[权益状态资料](https://github.com/pskdje/bilibili-API-collect/blob/main/docs/vip/info.md)记载每日过期时间与下一周期时间戳。当前本地分组暂用北京时间 00:00，元数据明确 `resetVerified=false`，每次真正执行仍重新查询官方当日状态。不要把本地日期判断当作官方签到凭据。

## 实际验证记录及人工验收

本次已完成以下**无账号只读网络验证**：官方入口、其 HTML 引用的 JavaScript 可读取；官方会员中心 v2 返回 `code=0` 但 `vip_info.mid=0,experience=null`；`/x/vip/privilege/my` 返回 `code=-101`。它们分别证明接口存在与未登录结果，不能充当成功签到验证。本次未发送领取写请求。

`BilibiliAdapterTest` 覆盖：缺失/未知/字符串状态不会成为已签到、需要手动观看、HTTP 成功/业务 code 0 仍不足以确认发奖、明确未发放、延迟发放与已领取区分、过滤非 B 站凭证字段、异常不回显凭证；另有小数、数值字符串、布尔值与溢出回归。业务码、状态、发放类型和资格状态必须是严格整数 Number；UID 必须是正整数 Long。小数（包括 `0.0`）、字符串数值及溢出均不进行截断或隐式转换，缺失/格式未知的资格返回“结果待确认”。具体测试执行结果以总验证报告为准。

安装后最短真实验收：

1. 在官方入口确认出现“专属等级加速包”，记录当前状态及主站经验；在账号页本地导入 Cookie，核对昵称与 UID。
2. 若提示观看不足，在官方 App 正常观看视频至少 1 分钟后返回工具，点一键签到。
3. 核对工具只出现等级加速包结果，回到官方入口核对已领取。经验流水未明确证明到账时，不把工具的受理状态当到账证明。
4. 再执行一次应由只读查询判为今日已领取；更新为另一个账号的凭证必须被 UID 校验拦住。
5. 非大会员、LV0、过期 Cookie、超时与平台变化用独立测试账号或测试响应验证，不通过购买、续费、消耗资源来制造资格。

## 参考实现与维护

[BLTH](https://github.com/andywang425/BLTH)为 MIT，所读实际发布脚本为 7.3.8；[发布页](https://greasyfork.org/en/scripts/406048-bilibili-live-tasks-helper)仍有维护记录。其权益模块把 `type=9` 对应至等级加速包，调用同一 `experience/add`；这里只参考协议事实，未复制其程序代码。它将运行条件归在年度权益模块，不能据此收窄官方“有效大会员”的规则。

[BiliBiliToolPro](https://github.com/RayWangQvQ/BiliBiliToolPro)为 GPL-3.0，阅读时仍有活动记录；没有复用其代码或引入其广泛任务。[pskdje 的 API 文档存档](https://github.com/pskdje/bilibili-API-collect)为 CC-BY-NC 4.0，2026-01-28 已归档，只作为附加历史说明，维护核查须优先当前官方页面。

平台变更时先重取官方入口引用脚本并记录新 hash，再修改 `BilibiliAdapter` 的只读状态、写入调用和解析测试。保留严格的业务类型检查、目标 UID 检查和未知返回规则；禁用该项后可继续执行其他平台。此适配器没有观看心跳、播放、评论、投币、积分兑换、购买或续费功能。
