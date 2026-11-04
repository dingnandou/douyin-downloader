import json
import sys
import zipfile
import io

import requests

BASE = "http://127.0.0.1:8000"
SHARE_TEXT = sys.argv[1] if len(sys.argv) > 1 else (
    "1.23 复制打开抖音，看看【教父(薄肌版)的图文作品】乃真英雄👏👏。卡尔·布什比（Karl Bushby"
    "... https://v.douyin.com/oc1C2hM4zH8/ sRx:/ :0pm g@O.xf 07/03"
)

print("=" * 60)
print("[1] POST /api/parse")
r = requests.post(f"{BASE}/api/parse", json={"text": SHARE_TEXT}, timeout=120)
print("HTTP", r.status_code)
data = r.json()
print(json.dumps(data, ensure_ascii=False, indent=2)[:800])
assert r.status_code == 200, data

if data.get("type") == "images":
    imgs = data["images"]
    print(f"\n[2] 图文：{len(imgs)} 张图片")

    print("\n[3] GET /api/image 第一张")
    ri = requests.get(f"{BASE}/api/image", params={"u": imgs[0], "name": data["title"] + "_1"}, timeout=60)
    print("HTTP", ri.status_code, "type:", ri.headers.get("content-type"), "len:", len(ri.content),
          "magic:", ri.content[:3], "disposition:", ri.headers.get("content-disposition", "")[:80])

    print("\n[4] POST /api/bundle")
    rb = requests.post(f"{BASE}/api/bundle", json={"images": imgs, "title": data["title"]}, timeout=120)
    print("HTTP", rb.status_code, "type:", rb.headers.get("content-type"), "len:", len(rb.content))
    if rb.status_code == 200:
        zf = zipfile.ZipFile(io.BytesIO(rb.content))
        print("zip 内文件:", zf.namelist())
        for n in zf.namelist()[:3]:
            info = zf.getinfo(n)
            first = zf.read(n)[:3]
            print(f"  {n}: {info.file_size} bytes, magic={first}")

elif data.get("type") == "video":
    print("\n[2] 视频直链:", data["video_url"][:120])
    print("\n[3] GET /api/download 下载前 64KB")
    rd = requests.get(f"{BASE}/api/download", params={"u": data["video_url"], "name": data["title"]}, stream=True, timeout=60)
    print("HTTP", rd.status_code, "type:", rd.headers.get("content-type"))
    chunk = next(rd.iter_content(65536), b"")
    print("bytes:", len(chunk), "is_mp4(ftyp):", b"ftyp" in chunk[:32], "magic:", chunk[:12])
    rd.close()

print("\n完成")
