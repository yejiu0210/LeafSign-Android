<div align="center">

<img src="docs/assets/icon.png" alt="一叶签图标" width="120">

# 一叶签 LeafSign

**扫码登录，一个按钮完成每日签到。**

网易云音乐 · 百度贴吧 · 森空岛 · 哔哩哔哩 · 小黑盒

[![Release](https://img.shields.io/github/v/release/yejiu0210/LeafSign-Android?include_prereleases&style=flat-square&color=7C6CF0&label=下载)](https://github.com/yejiu0210/LeafSign-Android/releases)
![Android](https://img.shields.io/badge/Android-8.0+-34A853?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-F59E0B?style=flat-square)
[![Stars](https://img.shields.io/github/stars/yejiu0210/LeafSign-Android?style=flat-square&color=5468EE)](https://github.com/yejiu0210/LeafSign-Android/stargazers)

<img src="docs/assets/banner.svg" alt="一叶签：扫码登录，一个按钮完成每日签到" width="100%">

[功能](#功能) · [支持的平台](#支持的平台) · [快速上手](#快速上手) · [常见问题](#常见问题) · [隐私与安全](#隐私与安全) · [自行构建](#自行构建) · [更新记录](docs/CHANGELOG.md)

</div>

## 功能

| 特点 | 说明 |
| :--- | :--- |
| 📱 **扫码登录** | 用官方 App 扫一下就能添加账号，支持系统双开的分身 App；不用抓包，也不用复制 Cookie |
| ⏰ **准点定时签到** | 精确闹钟 + 前台服务 + 每小时兜底，国产 ROM 可以再配合 Shizuku 保活；错过了会自动补跑当天 |
| 🔁 **失败自动重试** | 零点高峰被限流时，5 分钟后只重跑失败的项，每天最多 3 次 |
| 📈 **等级进度一目了然** | 贴吧各吧等级经验、B 站等级经验、网易云黑胶成长值都有进度条，森空岛显示今天领到了什么 |
| ✅ **结果可信** | 签完会再查一次平台的真实状态，确认了才显示「签到成功」；查不清就老实说「结果未确认」 |
| 🔒 **完全本地** | 没有任何服务器，凭证加密存在手机里，只发往各平台自己的官方域名 |

## 支持的平台

| 平台 | 每天自动做的事 | 需要什么 |
| --- | --- | --- |
| **网易云音乐** | 黑胶乐签、云贝签到 | 黑胶乐签需要黑胶会员；云贝签到所有账号都能用 |
| **百度贴吧** | 关注的吧逐个签到；可选每个吧随机赞一个帖子 | 无。点赞默认关闭，按吧单独勾选，只赞你还没赞过的帖子 |
| **森空岛** | 明日方舟、明日方舟：终末地 每日签到 | 已在森空岛绑定游戏角色；支持一个森空岛账号下的多个角色，也支持主号 + 分身多账号 |
| **哔哩哔哩** | 大会员等级加速包、大积分签到 | 有效大会员；领等级加速包前要先在 B 站看 1 分钟视频（官方规则） |
| **小黑盒** | 每日签到 | 用小黑盒 App 扫码登录 |

> [!NOTE]
> 一叶签只做各平台**官方提供的每日签到**。不刷播放、不刷浏览、不自动评论或发帖，也不会购买、兑换或使用任何付费资源。

## 快速上手

1. **安装**：到 [Releases](https://github.com/yejiu0210/LeafSign-Android/releases) 下载最新的 APK 安装（Android 8.0 及以上）。
2. **添加账号**：「账号 → 添加账号」，选平台，点「用官方 App 扫码登录」，在官方 App 里确认就好。跳转不过去时，二维码会存进相册，在官方 App 的「扫一扫 → 相册」里选这张图即可。
3. **选择要签的项**：添加后核对一下昵称和角色，勾上要签到的项目。
4. **一键签到**：回到「今日」点「一键签到」，每一项都会显示结果，以及等级、经验这些数据。
5. **打开每日自动签到**（可选）：「设置 → 每日自动签到」，选个时间。建议按设置页里的「准点运行检查」把权限都打开。

<details>
<summary><b>备用：手动导入凭证</b></summary>

扫码不方便时，可以在「添加账号 → 手动导入凭证」里填。凭证等于登录授权，请自己保管好，不要发给别人。

| 平台 | 在电脑浏览器登录后，从 Cookies 里复制 |
| --- | --- |
| 网易云音乐 | `MUSIC_U` |
| 百度贴吧 | `BDUSS`（有 `STOKEN` 可以一起填） |
| 森空岛 | 鹰角通行证 token，或包含 `cred` 的官方 JSON |
| 哔哩哔哩 | `SESSDATA` 和 `bili_jct` |
| 小黑盒 | `heybox_id` 和 `pkey` |

</details>

## 常见问题

<details>
<summary><b>定时签到时贴吧大面积失败？</b></summary>

零点刚过是贴吧签到的高峰，百度会限流，请求会被 302 跳转。打开「设置 → 失败后自动重试」（默认已开），5 分钟后会自动补签；也可以把签到时间设在 00:30 之后避开高峰。

</details>

<details>
<summary><b>定时签到没有按时运行？</b></summary>

多半是被系统省电策略拦了。到「设置 → 准点运行检查」把精确闹钟、忽略电池优化、通知、厂商自启动都打开，再点「1 分钟后测试一次」看看。小米、OPPO、vivo、华为等系统推荐配合 [Shizuku](https://shizuku.rikka.app/zh-hans/)，在设置里一键保活，并开启「Shizuku 守护唤醒」。

</details>

<details>
<summary><b>显示「结果未确认」是什么意思？</b></summary>

请求发出去了，但再查平台状态时没能确认已经完成，比如网络超时、服务端同步慢。为了不重复操作，一叶签不会盲目重发，稍后点「仅重试失败项」，或者等自动重试就行。

</details>

<details>
<summary><b>显示「需要验证」或「登录已失效」？</b></summary>

「需要验证」：去对应的官方 App 里完成一次验证码或安全验证，再回来重试。
「登录已失效」：在账号页点「备注 / 更新凭证」，重新扫码登录。

</details>

<details>
<summary><b>从 0.4.x 升级装不上？</b></summary>

0.5.0 起更换了签名证书，需要先卸载旧版再安装（本地账号需要重新登录）。0.5.x 之间可以直接覆盖安装。

</details>

## 隐私与安全

- **没有服务器**：所有请求都从你的手机直接发往各平台的官方域名，开发者收不到你的任何数据。
- **凭证加密保存**：用 Android Keystore 密钥加密存在本地，禁用了系统备份和明文 HTTP。
- **扫码和网页登录都走官方**：密码、短信、验证码都由官方页面或官方 App 处理；一叶签不读取原版或分身 App 的私有文件。
- **退出和删除**：账号页可以随时「退出本地授权」（清除凭证、保留记录）或「删除账号和记录」。

## 自行构建

需要 JDK 17（Temurin 等标准 OpenJDK；GraalVM 的 jlink 与 AGP 不兼容）和 Android SDK 36、Build Tools 35.0.0。

```powershell
.\Build.ps1 -JavaHome 'C:\path\to\jdk-17' -SdkPath 'C:\path\to\android-sdk'
```

或者直接执行：

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。仓库里没有签名私钥，自行构建的包签名和 Releases 里的不同，不能覆盖安装官方发布的版本。

各平台的接口说明和维护笔记在 [docs/](docs/) 目录：[网易云](docs/integration-netease-vip.md) · [贴吧签到](docs/integration-music-tieba.md) · [贴吧点赞](docs/integration-tieba-like.md) · [森空岛](docs/integration-skland.md) · [B 站](docs/integration-bilibili.md) · [小黑盒](docs/integration-heybox.md) · [维护说明](docs/maintenance.md)

## 免责声明

本项目仅供学习交流，与网易云音乐、百度贴吧、鹰角网络、哔哩哔哩、小黑盒等平台没有任何关系。各平台的接口可能随时变化或加强风控，使用自动签到存在账号被限制的风险，请自行判断、自担风险。

## 许可证

[MIT](LICENSE)。参考的上游项目和许可证保留在各接入文档中。应用图标的插画由 NovelAI 生成。

<div align="center">
<br>
<sub>如果一叶签帮到了你，点个 ⭐ Star 支持一下吧～</sub>
</div>
