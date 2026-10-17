# 贴吧逐吧每日随机点赞

核查日期：2026-10-17（Asia/Shanghai）。本文件只描述新增的逐吧点赞任务；原有普通签到见 [贴吧签到协议记录](integration-music-tieba.md)。

## 用户确认的范围

每个已启用的贴吧、每个账号、每天分别随机选一篇主题帖，不是从全部贴吧合计选一篇。独立任务 ID 为 `tieba:like:<forumId>`，类型为 `daily-like`，与该吧签到任务分别选择，**默认关闭**。用户只启用所需贴吧的点赞任务即可；没有启用的吧不点赞。

同一天选定的账号、吧和帖子持久固定；超时、失败、重启或重试不会换帖，也不会改去其他吧补做。没有可用候选帖子时保留该吧的失败或待确认结果，其他吧和账号可继续。

每吧每天最多取消一次已有点赞，并最多进行两次同一帖的有限点赞尝试。第二次只用于先查询确认尚未点赞后的恢复，不循环刷赞。验证码、风险限制或身份错误立即停止该项。

## 验证状态

已取得维护中的开源实现、许可及官方 HTTPS 匿名只读响应，新增点赞与取消点赞尚未使用真实账号验证。匿名检查没有携带 BDUSS / STOKEN，没有发起点赞或取消操作。用户 2026-10-17 报告原有四平台正常签到，是既有功能的用户报告，**不代表本次新点赞功能已通过真实账号验证**。

构建和隔离测试执行结果统一见 [验证记录](verification.md)。测试数据不会进入正式运行路径。

## 登录与账号校验

复用同一贴吧账号的官方网页登录及本地加密凭证，不另建登录体系。客户端接口以当前账号 BDUSS 校验；`/c/s/login` 必须返回有效 `user.id`、姓名及 `anti.tbs`，任务的 `ownerUserId` 必须一致。

候选列表与帖子查询再次核对响应 `user.id/is_login`、目标吧 ID 和主题帖 ID。匿名查询的 `has_agree=0` 不能用于判断某个已登录账号是否点赞。STOKEN 若存在仅按既有平台白名单管理，不跨账号、跨平台发送。

## 读取候选与帖子状态

| 操作 | HTTPS 接口 | 核对内容 |
|---|---|---|
| 读取所选关注吧候选主题帖 | `POST https://tiebac.baidu.com/c/f/frs/page` | 当前登录 UID、`forum.id` 与该任务吧 ID 相同，主题帖 ID 有效 |
| 读取当天固定主题帖 | `POST https://tiebac.baidu.com/c/f/pb/page` | 当前登录 UID、`forum.id`、`thread.id` 及主题帖 `thread.agree` |

读取使用已验证可返回 JSON 的旧客户端字段 `_client_type=2`、`_client_version=12.64.1.1`，并对表单签名。高版本可能改为 `page_data.feed_list`，不能把新旧形状混读；不识别的结构待确认。

从该吧有限条候选中本地随机选择，过滤明确广告、已删除或屏蔽项目。只从当前真实关注且用户启用的那个贴吧选帖，不读取任意推荐流并点赞。候选身份或吧信息不完整时停止该吧。

读取的是 `thread.agree.has_agree` 与 `agree_type`，不是 `post_list[0].agree` 的首楼回复状态。严格接受已知 0 / 1 并排除点踩；未知类型或数值不猜测。已有赞本身没有日期，不能据此认定今天执行过本任务。

## 点赞和取消接口

`POST https://tiebac.baidu.com/c/c/agree/opAgree`，`application/x-www-form-urlencoded`。

| 字段 | 主题帖操作取值 |
|---|---|
| `BDUSS` | 当前已验证账号的会话凭证 |
| `_client_version` | `22.12.1.0` |
| `agree_type` | `2` 表示点赞；`5` 是点踩，不用于取消 |
| `content_type` | `3` 表示主题帖 |
| `op_type` | `0` 点赞、`1` 取消当前点赞 |
| `post_id` | `0`，不对首楼回复另行点赞 |
| `thread_id` | 当天该吧固定选中的主题帖 ID |
| `tbs` | 当前账号 `/c/s/login` 返回的请求校验值 |
| `cuid` | 按已核查算法从本工具实例标识派生，保存在该次操作上下文；不用他人的设备值 |
| `sign` | 原始表单字段按键排序，串接 `key=value` 后附 `tiebaclient!!!`，计算 UTF-8 MD5 小写十六进制 |

只有明确且类型可核对的 `error_code=0` 才接受该次操作响应；HTTP 200、缺少错误码、未知错误码或结构变化都不能单独判成功。写入之后还必须查询同一主题帖状态。

`cuid_galaxy2` 的参考公式包含 `MD5("com.baidu" + android_id)`、`|V` 和 heliosHash Base32；heliosHash 涉及 CRC32、XXH32 与 40 位位域。实现使用下述 Unlicense 代码说明的实际算法，不省略字段，不把随机 UUID 或 `-` 冒充完整 CUID，不读取其他 App 的设备私有数据。

## 执行、恢复与结果

1. 校验当前账号身份、所选贴吧归属及关注状态。
2. 为 `账号 ID + tieba:like:<forumId> + 当日周期` 建立独立 checkpoint，固定帖子及吧。每个吧的阶段、尝试计数和结果互不共用。
3. 查询固定帖的当前点赞状态。若已赞，最多取消一次并查询确认取消；若未赞，直接进入点赞。
4. 每次写入**之前**持久保存阶段与尝试计数，再发送请求。进程中断不会将未完成步骤写成成功。
5. 点赞后查询同帖状态；只有可验证的当前账号、吧、帖子与点赞状态一致才完成。已发送但结果未知时先查询；不能盲目再取消、换帖或无限重发。
6. 已完成的本地当天任务再次触发时不重复取消或点赞。未完成且已达到一次取消、两次同帖点赞上限时停止，留待本人查看。

服务器“当前已赞”与工具“今天已完成这次操作”是不同证据。未建立今天的操作历史时，不把昨天留下的赞当成今天已执行；已持久记录的完成阶段才阻止重复执行。

| 情况 | 结果 |
|---|---|
| 本次已发送点赞，复查确认同帖赞生效 | 点赞完成 / 点赞操作已确认 |
| 本地当日记录已完成 | 今日点赞任务已完成，不重复操作 |
| 登录身份不符或失效 | 登录失效，停止该吧 |
| 验证码、安全验证或风险限制 | 需要手动验证，停止该吧 |
| 取消、点赞或查询超时，未知响应，结构不一致 | 结果待确认；保留同帖上下文 |
| 对应吧无可用帖子、取消未确认或达到次数上限 | 不换帖、不转移到其他吧补做，保留原因 |

工具以 Asia/Shanghai 午夜划分本地每日操作上限；尚未核实平台点赞经验的精确每日重置时刻，`resetVerified=false`。只处理当天任务，不把昨天未完成步骤移到今天补做。

## 经验与其他行为

尚无当前官方规则或真实响应证明“主题帖点赞一次”或“取消后重赞”必定发放当天经验。[贴吧官方经验加速说明](https://c.tieba.baidu.com/tbmall/tshow?tab=detail) 不能据此推导该行为的经验奖励。因此仅显示点赞操作和状态确认，经验为“待官方确认”，不虚构到账数值。

不自动评论、发帖、点踩、投票、分享、领取任务奖励或购买会员。上游对频繁调用点赞接口的风险说明只用于控制本次有限次数，不扩展为其他自动化任务。

## 源码、许可与匿名证据

主要参考 [lumina37/aiotieba](https://github.com/lumina37/aiotieba)，[Unlicense](https://github.com/lumina37/aiotieba/blob/a5472bef5bbae454fce33ed03bcbeaf7950d1bcb/LICENSE)。本次 master 提交 `a5472bef5bbae454fce33ed03bcbeaf7950d1bcb`，提交时间 `2026-10-11T02:41:45Z`。

- [agree/_api.py](https://github.com/lumina37/aiotieba/blob/a5472bef5bbae454fce33ed03bcbeaf7950d1bcb/src/aiotieba/api/agree/_api.py)：点赞/取消的字段和错误响应。
- [client.py](https://github.com/lumina37/aiotieba/blob/a5472bef5bbae454fce33ed03bcbeaf7950d1bcb/src/aiotieba/client.py)：主题帖调用语义及频繁调用限制说明。
- [const.py](https://github.com/lumina37/aiotieba/blob/a5472bef5bbae454fce33ed03bcbeaf7950d1bcb/src/aiotieba/const.py)：客户端版本与域名。
- [get_threads/_api.py](https://github.com/lumina37/aiotieba/blob/a5472bef5bbae454fce33ed03bcbeaf7950d1bcb/src/aiotieba/api/get_threads/_api.py) 与 [issue #222](https://github.com/lumina37/aiotieba/issues/222)：旧版列表协议与高版本结构差异。
- [cuid.c](https://github.com/lumina37/aiotieba/blob/a5472bef5bbae454fce33ed03bcbeaf7950d1bcb/src/aiotieba/helper/crypto/csrc/tbcrypto/cuid.c)：CUID 派生算法。

aiotieba 当前部分读取使用 protobuf / HTTP transport，本工程没有复制明文 HTTP 传输，采用已匿名核实的官方 HTTPS JSON 返回。

辅助字段语义参考 [TiebaLite 的 ThreadContentBean.kt](https://github.com/zzc10086/TiebaLite/blob/4e96c26ecbdfd81d9ddffba1eb3a3f1d2a455163/app/src/main/java/com/huanchengfly/tieba/post/api/models/ThreadContentBean.kt) 和 [ThreadActivity.kt](https://github.com/zzc10086/TiebaLite/blob/4e96c26ecbdfd81d9ddffba1eb3a3f1d2a455163/app/src/main/java/com/huanchengfly/tieba/post/activities/ThreadActivity.kt)，提交 `4e96c26ecbdfd81d9ddffba1eb3a3f1d2a455163`（2023-10-01，较旧），Apache-2.0。仅研究事实性 `thread.agree` 字段，没有复制其乐观 UI 或实现代码。

2026-10-17 无凭证只读检查：

| 接口 | 结果与限制 | 开发记录 SHA256 |
|---|---|---|
| 签名 `/c/f/frs/page` | `error_code=0`，吧 ID 4536、10 个主题帖，含 `agree.has_agree/agree_type`；不能代表登录用户状态 | `48b2d3e04369705f7afb7412985b62f9afc6df6890c5f8a31c0eac6cdb6482b6` |
| 签名 `/c/f/pb/page` | `error_code=0`，主题帖 10754086366、吧 ID 4536，`thread.agree` 与匿名 `user.is_login=0/id=0` | `52a942324f79183086f9fe128c11761d32655714a0e8112da4d6065e9252ef34` |

以上仅证明当前公开读取结构可用。写入、登录用户状态、取消后重赞与真实经验均待本人真机验证；未知错误码没有据此推导含义。

## 最短人工验证

1. 对已添加的贴吧账号执行“校验 / 刷新”，在任务选择里只启用一个吧的“每日随机点赞一帖”。
2. 执行一次，查看结果中的具体吧和帖子；到官方贴吧核对该主题帖点赞状态。经验以官方记录为准。
3. 同一天再次执行，应不重复取消或点赞；再启用第二个吧验证独立选择与记录。
4. 发生网络中断时只重试该失败项，确认仍使用当天同一吧、同一帖。验证码须本人完成，不发送登录凭证给聊天。
