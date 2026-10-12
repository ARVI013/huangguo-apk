# 黄果短剧 APK（huangguoai.com 封装）

成品：[dist/huangguo-shorts-1.1.apk](dist/huangguo-shorts-1.1.apk) — 58 KB，已签名（自签 V1/V2/V3），`minSdk 24`（Android 7.0+）/ `targetSdk 35`，包名 `com.huangguo.shortdrama`，versionCode 2 / versionName 1.1。

## 安装

1. 把 APK 传到手机，点击安装（需允许「安装未知应用」）。
2. 首次打开会出现站点自身的年龄确认（18+），确认一次后不再出现。
3. 站点域名经常变，App 内置回退链：`huangguoai.com → hgai2.com → l4nliu.pqhybuxo.cc → huangguoai.pages.dev`。某个地址打不开时会自动切到下一个并提示；全部失败时显示深色重试页。

## 去除了什么

| 目标 | 做法 |
| --- | --- |
| 站内推广位 `.hg-ssp-slot`（首页 3 个 banner） | CSS `display:none` + JS 删除节点（含 JS 后加载的广告） |
| Hero 轮播里的广告片（`data-hero-slides` 中 `isAd:true`，实测 9 条里 4 条是广告） | 在页面脚本读取前重写该 JSON，只留正片；广告角标 `[data-hero-adbadge]` 隐藏；已烘焙进 HTML 的广告封面链接就地中和（`href="#"` + `pointer-events:none`），不删节点以免打断轮播脚本 |
| 「黄果短剧APP」悬浮下载按钮、导航栏 APP 链接 | `.hg-fab__btn--download`、`a[data-menu-id="1"]` 隐藏 |
| 公告栏里的第三方推广（`zsharex.com` 等） | 只删带站外链接的 `.hg-announce__item`，官方公告保留 |
| 页脚镜像地址块 | 隐藏（App 自己做了域名回退） |
| 第三方统计/追踪 | `shouldInterceptRequest` 直接返回空响应：Google Tag Manager、GA、AdSense、DoubleClick、Ahrefs Analytics、Yandex Metrika、Cloudflare Insights，以及 `ff5yf8n.top`、`bmop45.com`、`fyxybiblx.cc`、`kke8jyk.top`、`zsharex.com`、`ikorimlt.cc` |
| 其它站外链接 | 解包成纯文本，保留文字、去掉广告跳转 |

图片 CDN `pic.wirqed.cn`（带 `auth_key` 签名）不屏蔽，否则封面全黑。

## 移动端适配

- 深色外壳与站点一致（`#080808` 背景、`#ffd21c` 强调色），状态栏/导航栏同色，无 ActionBar。
- 刘海/手势条安全区：`env(safe-area-inset-*)` 注入到顶栏内边距和悬浮按钮位置。
- 视频：`setMediaPlaybackRequiresUserGesture(false)` 可直接播放；`onShowCustomView/onHideCustomView` 实现网页视频全屏（返回键先退出全屏）；全屏时保持屏幕常亮。
- 关闭 WebView 强制深色（站点自带深色主题，避免二次压暗）、关闭过度滚动回弹、禁止横向溢出与文本自动放大。
- 小屏（≤520px）收紧分区与卡片间距；站点自身的响应式规则保持不变，注入 CSS 只做增强。
- 下载链接走系统 DownloadManager（公共 Downloads 失败则退回应用目录）。

## 重新构建

```powershell
# 工具放在 tools/ 下（JDK 17 + build-tools r35 + platform-35），无需 Gradle / Android Studio
# 下载地址见 .gitignore 注释；解压到 tools/jdk-*、tools/android-15、tools/android-35
iex (Get-Content -Raw ".\build.ps1")
```

构建链：`aapt2 compile/link` → `javac`（`-source/-target 8`，纯 `android.*` 框架，无 androidx）→ `d8` → 自建 `Assemble` 工具合并 `classes.dex` + `assets/`（`resources.arsc` 保持 STORED）→ `zipalign` → `apksigner`。

改广告规则只需改 `app/assets/hg-inject.css` / `hg-inject.js` 再重跑构建。校验脚本：[test-inject.js](test-inject.js)（检查注入脚本语法、ES5 兼容性，并用抓取的 `probe/home.html` 验证广告选择器与 hero JSON 清洗结果；该 HTML 属于站点内容，未随仓库分发，需自行抓取首页存为 `probe/home.html`）。

## 已知限制

- 视频播放器类型未做真机确认（抓取站点子页面时网络不稳定）。若某站点用 hls.js，WebView 一般可播；若用外部 iframe 播放器，可能需要放宽域名白名单。
- 签名是自签（`keystore/release.keystore`，口令 `android`）。该文件已被 `.gitignore` 排除，请自行备份；以后要覆盖安装必须沿用同一 keystore，否则需先卸载。
- 站点若换到全新域名，需要更新 `MainActivity.java` 里的 `ENTRY_URLS` / `SITE_HOSTS`。

## 免责声明

本项目只是一个 WebView 外壳，不包含、不托管任何内容，所有内容由目标站点提供。仅供个人学习与自用；请自行确认在当地使用该站点合法。
