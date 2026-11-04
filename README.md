# 抖音下载器

粘贴抖音分享链接，解析并保存视频或图文作品。

提供两种用法：

- **Android App**（推荐）：装 APK，手机独立使用，不需要电脑
- **电脑网页版**：电脑上跑服务，手机浏览器访问

> 仅供个人学习与存档使用。请尊重作者版权，不要二次分发或商用。

## Android App

安装 [`releases/douyin-android-v1.0.apk`](releases/douyin-android-v1.0.apk)，打开后：

1. 在抖音点「分享 → 复制链接」
2. 回到 App 粘贴，点「解析作品」
3. 视频点「保存视频」；图文点单张「保存」或「保存全部图片」

也可以直接在抖音的分享面板里选「抖音下载」把内容发过去。

- 视频保存到相册 `Movies/Douyin`
- 图片保存到相册 `Pictures/Douyin`
- 支持 Android 10 及以上

从源码构建：

```bash
cd android
# 需要 JDK 17、Android SDK (platform 36)
# 在 android/local.properties 里写 sdk.dir=你的SDK路径
gradle assembleRelease
```

APK 输出在 `android/app/build/outputs/apk/release/`。

---

## 电脑网页版

在电脑上跑一个小服务，手机浏览器打开它，粘贴抖音分享链接即可解析并保存作品。

- **视频作品**：yt-dlp 解析无水印直链，后端代理下载
- **图文作品**：解析出全部原图，可单张保存或打包成 zip
- 后端：FastAPI + yt-dlp
- 前端：单页 PWA，可「添加到主屏幕」当 App 用
- 无需 root、无需越狱，Android / iOS 都能用

> 仅供个人学习与存档使用。请尊重作者版权，不要二次分发或商用。

## 快速开始（Windows）

1. 双击 `run.bat`（首次会自动装依赖，需几分钟）
2. 看到 `Uvicorn running on http://0.0.0.0:8000` 表示启动成功
3. 电脑浏览器打开 <http://localhost:8000> 试一下

## 手机怎么用

1. 手机和电脑连**同一个 WiFi**
2. 手机浏览器访问：

   ```
   http://<电脑局域网IP>:8000
   ```

   例如 `http://10.61.118.156:8000`（在电脑上运行 `ipconfig` 查 IPv4 地址）
3. 在抖音里点「分享 → 复制链接」
4. 把整段分享内容粘贴进网页，点「解析作品」
5. 视频点「保存视频」；图文点「保存」（单张）或「打包下载全部图片」（zip）

> 第一次启动时 Windows 防火墙可能弹窗，选择「允许访问」。
> 如果手机打不开，多半是防火墙挡住了 8000 端口，或两台设备不在同一 WiFi。

### 装到手机桌面（可选）

- Android Chrome：右上角菜单 → 「添加到主屏幕」
- iOS Safari：分享按钮 → 「添加到主屏幕」

之后从桌面图标打开，全屏显示，像一个 App。

## 手动启动（命令行）

```bash
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 实现原理

- **视频**：用 Playwright 驱动本机已装的 Edge / Chrome（无头模式）打开作品页，让抖音自己完成签名和 cookie，再拦截它请求的 `aweme/v1/web/aweme/detail` 响应，取出 `play_addr` 无水印直链。yt-dlp 直接调用该接口不带签名，会被风控拦下，只作为兜底。
- **图文**：抖音对搜索引擎爬虫 UA 会直出服务端渲染页面，页面里的 `application/ld+json` 含完整图片列表，直接解析即可。
- **图片格式**：抖音原图是 webp，手机相册兼容性差，后端统一转成 JPEG 再返回。
- 直链有时效，所以下载统一走后端代理（带 `Referer` 和浏览器 UA，绕过 CDN 防盗链）。

## 常见问题

**解析失败：Fresh cookies (not necessarily logged in) are needed**
视频解析依赖浏览器。程序会自动使用本机的 Edge 或 Chrome；两者都没有时才需要下载 Chromium：

```
.venv\Scripts\python -m playwright install chromium
```

图文不依赖浏览器，通常不受影响。

**解析视频比较慢（10~20 秒）**
正常现象：每次解析都要启动一次无头浏览器。

**下载视频要等很久才「开始」**
本项目已用流式代理，正常应在 1 秒内开始传输。若明显卡顿，检查是否有安全软件拦截了本程序的出站连接。

**图文解析失败：页面里没有找到图片数据**
说明这个作品不是图文，或抖音改了 SSR 策略。把链接发出来排查。

**下载下来打不开**
视频按「最佳 mp4」选择，正常应为一个完整 mp4。图片统一转 JPEG。仍有问题请附上链接。

**能批量下载某个作者的全部作品吗**
本项目只做单个作品解析，不做批量爬取。

## 目录结构

```
app/main.py               后端：解析接口 + 下载代理 + 图片打包
web/index.html            前端页面
web/manifest.webmanifest  PWA 配置
web/sw.js                 Service Worker
run.bat                   Windows 一键启动
scripts/e2e_test.py       接口自测脚本
```
