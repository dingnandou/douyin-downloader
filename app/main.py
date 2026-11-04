"""抖音作品解析下载后端。

支持两类作品：
- 视频：/video/<id>，用 yt-dlp 解析直链
- 图文：/note/<id>，用搜索引擎 UA 拿 SSR 页面里的结构化数据

个人存档用途：仅解析单个作品、不批量爬取、不做二次分发。
"""

from __future__ import annotations

import io
import json
import re
import zipfile
from pathlib import Path
from urllib.parse import quote, urlparse

import requests
import yt_dlp
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, Response, StreamingResponse
from PIL import Image
from pydantic import BaseModel

try:
    from playwright.sync_api import sync_playwright
except ImportError:  # playwright 未安装时仍可解析图文
    sync_playwright = None

BASE_DIR = Path(__file__).resolve().parent.parent
WEB_DIR = BASE_DIR / "web"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0 Safari/537.36"
)
# 抖音对搜索引擎爬虫会直出 SSR 页面，图文作品的结构化数据只在这里能拿到
GOOGLEBOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

ALLOWED_HOST_SUFFIXES = (
    "douyin.com",
    "douyinpic.com",
    "douyinvod.com",
    "douyinstatic.com",
    "bytedance.com",
    "byteimg.com",
    "bytecdn.cn",
    "snssdk.com",
    "amemv.com",
    "pstatp.com",
    "ixigua.com",
)

URL_RE = re.compile(r"https?://[^\s　，,。；;）)】】]+", re.I)
NOTE_OR_VIDEO_RE = re.compile(
    r"(?:douyin\.com|iesdouyin\.com)/(?:share/)?(note|video)/(\d+)", re.I
)

app = FastAPI(title="抖音下载器", docs_url=None, redoc_url=None)


class ParseRequest(BaseModel):
    text: str


class BundleRequest(BaseModel):
    images: list[str]
    title: str = "douyin"


def _safe_name(name: str, ext: str) -> str:
    safe = re.sub(r'[\\/:*?"<>|\r\n]+', "_", name or "").strip()[:80] or "douyin"
    if not safe.lower().endswith(ext):
        safe += ext
    return safe


def _host_allowed(raw_url: str) -> bool:
    try:
        host = (urlparse(raw_url).hostname or "").lower()
    except ValueError:
        return False
    return bool(host) and any(host == s or host.endswith("." + s) for s in ALLOWED_HOST_SUFFIXES)


# 本机常开系统代理（Clash 等），requests 会从注册表读到它。
# 抖音是国内服务，走代理会绕路甚至超时，这里统一强制直连。
_NO_PROXY = {"http": None, "https": None}


def _get(url: str, **kwargs) -> requests.Response:
    kwargs.setdefault("proxies", _NO_PROXY)
    return requests.get(url, **kwargs)


def extract_url(text: str) -> str:
    m = URL_RE.search(text or "")
    if not m:
        raise HTTPException(status_code=400, detail="没有找到链接，请粘贴完整的抖音分享内容")
    return m.group(0).rstrip("，,。；;）)】].")


def expand_short_url(url: str) -> str:
    if "v.douyin.com" in url:
        try:
            r = _get(url, headers={"User-Agent": UA}, allow_redirects=True, timeout=15)
            url = r.url
        except requests.RequestException:
            pass
    return url


def _detail_to_result(detail: dict, video_id: str, url: str) -> dict:
    desc = (detail.get("desc") or "").strip()
    author = ((detail.get("author") or {}).get("nickname") or "").strip()

    images = detail.get("images") or []
    if images:
        urls = []
        for img in images:
            url_list = (img or {}).get("url_list") or []
            if url_list:
                urls.append(url_list[0])
        if urls:
            return {
                "type": "images",
                "id": video_id,
                "title": desc or "抖音图文",
                "author": author,
                "cover": urls[0],
                "duration": None,
                "video_url": "",
                "images": urls,
                "webpage_url": url,
            }

    video = detail.get("video") or {}
    play_url = ""
    for key in ("play_addr_h264", "play_addr", "download_addr"):
        url_list = (video.get(key) or {}).get("url_list") or []
        if url_list:
            play_url = url_list[0]
            break
    if not play_url:
        raise HTTPException(status_code=502, detail="视频解析失败：没有拿到视频地址")

    cover_list = (video.get("cover") or {}).get("url_list") or []
    duration = video.get("duration")
    return {
        "type": "video",
        "id": video_id,
        "title": desc or "抖音视频",
        "author": author,
        "cover": cover_list[0] if cover_list else "",
        "duration": round(duration / 1000, 1) if isinstance(duration, (int, float)) and duration else None,
        "video_url": play_url,
        "images": [],
        "webpage_url": url,
    }


def _parse_with_browser(url: str, video_id: str) -> dict:
    """让无头浏览器打开作品页，拦截抖音自己的 detail 接口响应。

    yt-dlp 裸调该接口不带签名，现在必被拦；浏览器会自己完成签名和 cookie。
    """
    if sync_playwright is None:
        raise RuntimeError("未安装 playwright")

    detail = None
    fallback_src = None
    with sync_playwright() as p:
        browser = None
        last_error = None
        # 优先用系统已装的 Edge / Chrome，避免再下载一份 Chromium
        for channel in ("msedge", "chrome", None):
            try:
                kwargs = {"channel": channel} if channel else {}
                browser = p.chromium.launch(
                    headless=True,
                    args=[
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--no-proxy-server",
                    ],
                    **kwargs,
                )
                break
            except Exception as e:  # 该浏览器不可用，换下一个
                last_error = e
        if browser is None:
            raise RuntimeError(f"没有可用的浏览器：{last_error}")
        try:
            context = browser.new_context(
                user_agent=UA,
                locale="zh-CN",
                viewport={"width": 1280, "height": 800},
            )
            page = context.new_page()
            page.add_init_script(
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"
            )

            def on_response(resp):
                nonlocal detail
                if "/aweme/v1/web/aweme/detail/" in resp.url and resp.ok:
                    try:
                        data = resp.json()
                    except Exception:
                        return
                    if isinstance(data, dict) and data.get("aweme_detail"):
                        detail = data["aweme_detail"]

            page.on("response", on_response)
            page.goto(url, wait_until="domcontentloaded", timeout=30000)

            def wait_detail(seconds: int) -> None:
                for _ in range(seconds):
                    if detail is not None:
                        return
                    page.wait_for_timeout(1000)

            wait_detail(12)
            if detail is None:
                # 偶发风控/首屏未触发接口，重载一次再等
                page.reload(wait_until="domcontentloaded", timeout=30000)
                wait_detail(12)
            if detail is None:
                el = page.query_selector("video")
                if el:
                    fallback_src = el.evaluate("e => e.currentSrc || e.src")
        finally:
            browser.close()

    if detail:
        return _detail_to_result(detail, video_id, url)
    if fallback_src:
        return {
            "type": "video",
            "id": video_id,
            "title": "抖音视频",
            "author": "",
            "cover": "",
            "duration": None,
            "video_url": fallback_src,
            "images": [],
            "webpage_url": url,
        }
    raise HTTPException(status_code=502, detail="视频解析失败：页面没有返回视频数据")


def _parse_with_ytdlp(video_id: str) -> dict:
    url = f"https://www.douyin.com/video/{video_id}"
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "format": "best[ext=mp4]/best",
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    if info.get("entries"):
        info = info["entries"][0]

    video_url = info.get("url")
    if not video_url:
        for f in info.get("formats") or []:
            if f.get("url") and f.get("vcodec") != "none":
                video_url = f["url"]
                break
    if not video_url:
        raise HTTPException(status_code=502, detail="视频解析失败：没有拿到视频地址")

    return {
        "type": "video",
        "id": video_id,
        "title": info.get("title") or "抖音视频",
        "author": info.get("uploader") or info.get("creator") or "",
        "cover": info.get("thumbnail") or "",
        "duration": info.get("duration"),
        "video_url": video_url,
        "images": [],
        "webpage_url": info.get("webpage_url") or url,
    }


def parse_video(video_id: str) -> dict:
    url = f"https://www.douyin.com/video/{video_id}"
    errors = []
    try:
        return _parse_with_browser(url, video_id)
    except Exception as e:
        errors.append(f"浏览器解析：{e}")
    try:
        return _parse_with_ytdlp(video_id)
    except Exception as e:
        errors.append(f"yt-dlp：{e}")
    raise HTTPException(status_code=502, detail="视频解析失败（" + "；".join(errors) + "）")


def parse_note(note_id: str) -> dict:
    url = f"https://www.douyin.com/note/{note_id}"
    try:
        r = _get(url, headers={"User-Agent": GOOGLEBOT_UA}, timeout=20)
        r.raise_for_status()
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"图文解析失败：{e}") from e

    blocks = re.findall(
        r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>', r.text, re.S
    )
    for block in blocks:
        try:
            obj = json.loads(block)
        except json.JSONDecodeError:
            continue
        if obj.get("@type") == "article" and obj.get("image"):
            images = [u for u in obj["image"] if isinstance(u, str)]
            author = obj.get("author")
            author_name = author.get("name", "") if isinstance(author, dict) else ""
            return {
                "type": "images",
                "id": note_id,
                "title": (obj.get("headline") or "抖音图文").strip(),
                "author": author_name,
                "cover": images[0] if images else "",
                "duration": None,
                "video_url": "",
                "images": images,
                "webpage_url": obj.get("url") or url,
            }
    raise HTTPException(status_code=502, detail="图文解析失败：页面里没有找到图片数据")


def parse_any(url: str) -> dict:
    m = NOTE_OR_VIDEO_RE.search(url)
    if m:
        kind, item_id = m.group(1).lower(), m.group(2)
        return parse_note(item_id) if kind == "note" else parse_video(item_id)
    raise HTTPException(status_code=400, detail="无法识别这个链接，请粘贴抖音作品分享内容")


def _fetch(u: str, timeout: int = 30) -> requests.Response:
    if not _host_allowed(u):
        raise HTTPException(status_code=400, detail="非法的资源地址")
    try:
        r = _get(
            u,
            headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"},
            timeout=timeout,
            stream=True,
        )
        r.raise_for_status()
        return r
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"资源获取失败：{e}") from e


def _to_jpeg(data: bytes) -> bytes:
    img = Image.open(io.BytesIO(data)).convert("RGB")
    out = io.BytesIO()
    img.save(out, "JPEG", quality=92)
    return out.getvalue()


@app.post("/api/parse")
def api_parse(req: ParseRequest) -> dict:
    url = extract_url(req.text)
    host = (urlparse(url).hostname or "").lower()
    if "douyin.com" not in host:
        raise HTTPException(status_code=400, detail="请粘贴抖音作品链接（分享内容里应包含 douyin.com）")
    data = parse_any(expand_short_url(url))

    if data["type"] == "video" and not _host_allowed(data["video_url"]):
        raise HTTPException(status_code=502, detail="解析到的视频地址不在允许的域名内")
    return data


@app.get("/api/download")
def api_download(u: str, name: str = "douyin") -> StreamingResponse:
    r = _fetch(u)
    filename = _safe_name(name, ".mp4")
    return StreamingResponse(
        r.iter_content(chunk_size=64 * 1024),
        media_type="video/mp4",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )


@app.get("/api/image")
def api_image(u: str, name: str = "image") -> Response:
    r = _fetch(u)
    try:
        jpeg = _to_jpeg(r.content)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"图片转换失败：{e}") from e
    filename = _safe_name(name, ".jpg")
    return Response(
        content=jpeg,
        media_type="image/jpeg",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )


@app.post("/api/bundle")
def api_bundle(req: BundleRequest) -> StreamingResponse:
    images = [u for u in req.images if _host_allowed(u)][:60]
    if not images:
        raise HTTPException(status_code=400, detail="没有可打包的图片")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for i, u in enumerate(images, 1):
            try:
                r = _fetch(u)
                zf.writestr(f"{i:02d}.jpg", _to_jpeg(r.content))
            except HTTPException:
                continue
    buf.seek(0)

    filename = _safe_name(req.title, "") + "_图片.zip"
    return StreamingResponse(
        buf,
        media_type="application/zip",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )


@app.get("/")
def index() -> FileResponse:
    return FileResponse(WEB_DIR / "index.html")


@app.get("/manifest.webmanifest")
def manifest() -> FileResponse:
    return FileResponse(WEB_DIR / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker() -> FileResponse:
    return FileResponse(WEB_DIR / "sw.js", media_type="application/javascript")


@app.get("/icon.svg")
def icon() -> FileResponse:
    return FileResponse(WEB_DIR / "icon.svg", media_type="image/svg+xml")
