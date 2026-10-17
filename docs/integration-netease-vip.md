# 网易云黑胶乐签（0.3.0）

核查日期：2026-10-17（Asia/Shanghai）。用户已明确业务为 **黑胶乐签**，不是 ordinary dailyTask(type=0)，也不是会员任务的「领取全部成长值」。旧普通签到写路径已移除，旧记录不映射到新任务。

## 业务与官方证据

入口为网易云音乐内「黑胶乐签」，官网登录入口 https://music.163.com/。本次不带任何账号的只读请求 `https://interfacepc.music.163.com/eapi/vipnewcenter/app/minidesk/music/sign/pc`，业务参数 `type=1,e_r=false`，返回 `code=200`、`data.text=10月黑胶乐签`、`data.subText=每月打卡得VIP体验` 和布尔 `today/sign` 字段；匿名今日项没有有效 true。这直接核实了业务名和状态结构，也证明 **HTTP / code 200 不代表登录或完成**。脱敏选字段证据见 `evidence/netease-vip-anonymous-state.json`。

没有真实账号写入或真机签到验证。官方匿名文案不能证明每种账号均有资格；也没有足够证据把签到强制限定为付费会员。不因用户未买会员而引导购买或自动开通。

## 协议源码与许可

- [Enhanced vip_sign.js 固定 commit](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/63f669d7f85f6c053ef2398e3c3bed66f02785e1/module/vip_sign.js)：2026-06-30，两个步骤为 `vip-center-bff/task/sign`（weapi，空业务参数）和 `vipnewcenter/app/level/user/checkin/history/detail`（eapi，`signDayTime=当前毫秒时间,type=1`）。本工具将后者也作为写链路的一部分，不能在只读 query 中调用。
- [对应修复讨论 #197](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/issues/197)：2026-06-19 至 06-30，首步单独 200 不证明完成。此为上游他人报告，不是本工具用户实测。
- [vip_sign_history.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/vip_sign_history.js)：`minidesk/music/sign/pc,type=1` 为乐签状态查询。
- [crypto.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/crypto.js)、[request.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/request.js)、[config.json](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/util/config.json)：EAPI MD5 包络和 AES/ECB/PKCS7，当前 eapi 域名 interfacepc.music.163.com；header 同时放加密正文和百分号编码 Cookie，requestId 尾缀四位；本工具显式 `e_r=false`，匿名官方请求证实普通 JSON 返回。
- [独立 Go 协议测试](https://github.com/chaunsin/netease-cloud-music/blob/master/api/vip_sign_test.go)：MIT，交叉核对 today/sign 字段与解密请求；其中 fixture 仅为模拟数据。

Enhanced 在核查日未归档，最近 push 为 2026-10-14T17:42:28Z。协议来源 MIT；版权许可保留于 `integration-music-tieba.md`。仅参考协议代码，不使用公共代理。协议常量是公开兼容参数，不是用户秘密。EAPI deviceId 为本进程生成的随机协议标识，不冒充手机硬件 ID；重启会变化，其真实账号兼容性仍待验收。

## 授权、请求和判定

在本工具内完成网易官网登录，从自己的 WebView 只提取 MUSIC_U、可选 __csrf；备用手动凭证也仅保留这两项。先用 music.163.com 的 weapi/nuser/account/get 得到真实 UID。目标 `netease:vip:music-sign` / `vip-music-sign` 保存 ownerUserId，每次查询或写入前核对身份。平台 Cookie 只发送至这两个核实过的网易官方域名，保留 TLS 校验、443 和不跟随 HTTP 重定向的边界。

| 结果依据 | 状态 |
| --- | --- |
| 唯一一项严格布尔 today=true，且 sign=true | 服务端今日已打卡 |
| 唯一今日项，严格布尔 sign=false | 尚未打卡，可开始两步签到 |
| 两必要请求均 code=200，随后只读查询明确今日已打卡 | 签到成功；不虚构成长值或奖励到账 |
| 明细返回未知，但随后只读查询明确今日已打卡 | 今日已签到；不宣称本次未知请求成功 |
| 业务301 / 身份丢失 / HTTP401 | 登录失效 |
| 明确验证码、安全验证或操作频繁 | 需要手动验证，停止重试 |
| 今日项缺失、重复、字符串 / 数字代替布尔值、未知 code、字段不完整 | 结果待确认，停止写入 |

普通 dailyTask 的 -2 不映射为乐签已签到，未知资格码也不编造成资格不足。写超时由 Runner 先只读查询，只有明确 sign=false 才有限重试；无法确认不重复写。query 只读 minidesk，不调用 history/detail。服务端今日字段优先；本地暂按北京时间00:00分日，精确重置时刻未做真实跨日验证。

旧账号升级会停用并移除旧普通签到目标，保留凭证和历史。在账号页点「校验 / 刷新」载入新任务，再明确勾选黑胶乐签；不会把旧完成记录自动复制到新任务。

## 官方 App 授权边界与验收

本次发现第三方文章指向 [官方开发文档入口](https://developer.music.163.com/st/developer/document?docId=2bb12a93e71a4be0842243b930c2f33c)，但未成功取得其授权正文，未确认可用于黑胶乐签的第三方 Android 回调 / 凭证范围。不能仅打开网易云 App 就宣称会回传 MUSIC_U，也不使用猜测 clientId。当前使用官网本工具会话，不读取原 App 私有数据。

最短验收：登录并核对昵称 / UID → 只勾选黑胶乐签 → 一键签到 → 到官方黑胶乐签核对今日打卡 → 再执行应仅查询到已签到。分别验证无效凭证、验证码、网络中断和未知状态；不购买、兑换、自动听歌、评论或领取其他任务奖励。JVM 流程与加密测试、构建检查见 `verification.md`，仍需本人手机验收。
