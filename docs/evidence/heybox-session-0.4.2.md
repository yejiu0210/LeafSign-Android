# 小黑盒会话补充核查

核查日期：2026-10-17。用户报告 0.4.1 官方登录页面上方显示授权失效，提供脱敏前截图；个人联系信息未复制进源码、记录或本证据。开发侧没有个人凭证，也没有操作真实登录或签到。

再次对照已下载的[官方登录中心脚本](https://static.max-c.com/static/heybox/webapp/@heybox-spa/login/app/index-CscaK6Wd.js)，SHA256 `bedb5bb2b786c8d87b576a6eb54ba61c26846a4da0b6d223676128656693c63f`：

- Cookie 写入函数 `Ve()` 使用 `escape(value)`，读取函数 `Hi()` 使用 `decodeURIComponent`。本地保存服务端刷新返回的新 pkey 时原来直接使用原始值，已改为同一 ASCII escape 规则；已导入 Cookie 原样保留，避免双重编码。
- 普通恢复会话路径 `v() → om({}, config) → EH → $get('/account/restore_login', {})` 使用官方公共参数/签名构建器，并依赖 Cookie。未发现普通路径要求在 URL 查询中放入 pkey 的证据；CLI 的凭证回调不能移用于普通网页登录。
- 官方成功处理先写共享 Cookie 再跳转首页。本工具此前在 shouldOverrideUrlLoading 中立刻合并多个域 CookieManager 的首个同名值，且登录表单上也定时检测，存在尚未同步/游客/旧别名候选被提前送检的问题。仅凭截图不能证明这是唯一根因。

0.4.2 改为从当前受信 login/apex/www 页面读取五个名称的 document.cookie 快照，检查正整数账号 ID、完整会话、重复/别名一致；没有密码、表单、正文、localStorage 或原生 JS bridge。登录表单填写期间不自动校验，官方返回时有限短读，手动检测作为恢复入口。空缺或冲突不冒充服务端凭证失效；服务端拒绝原因使用固定脱敏提示。

新增单页解析/读取域边界测试与刷新编码测试；统一执行证据见 `unit-test-summary.json`。这些离线测试证明逻辑分支，不能证明官网对个人账号已接受登录。真机最短回归步骤见 `../integration-heybox.md`。
