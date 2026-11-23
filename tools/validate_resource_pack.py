#!/usr/bin/env python3
"""Validate a zsst-resource-pack v1 ZIP without installing it."""
import argparse
import hashlib
import json
import zipfile
from pathlib import PurePosixPath


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    args = ap.parse_args()
    with zipfile.ZipFile(args.pack) as zf:
        names = zf.namelist()
        if len(names) != len(set(names)):
            raise SystemExit("FAIL: ZIP 内有重复路径")
        if "resource-pack.json" not in names:
            raise SystemExit("FAIL: 缺少 resource-pack.json")
        manifest = json.loads(zf.read("resource-pack.json"))
        if manifest.get("format") != "zsst-resource-pack" or manifest.get("version") != 1:
            raise SystemExit("FAIL: 不支持的格式/版本")
        files = {row["path"]: row for row in manifest.get("files", [])}
        items = manifest.get("items", [])
        ids = [str(i.get("id", "")) for i in items]
        if not ids or len(ids) != len(set(ids)) or "" in ids:
            raise SystemExit("FAIL: 资料 ID 为空或重复")
        for path, row in files.items():
            p = PurePosixPath(path)
            if p.is_absolute() or ".." in p.parts or not path.startswith(("data/courses/", "data/papers/")):
                raise SystemExit(f"FAIL: 不安全路径 {path}")
            blob = zf.read(path)
            if len(blob) != row["size"] or hashlib.sha256(blob).hexdigest() != row["sha256"]:
                raise SystemExit(f"FAIL: 哈希/大小不符 {path}")
        if set(files) != {n for n in names if n.startswith("data/") and not n.endswith("/")}:
            raise SystemExit("FAIL: 清单文件数与 ZIP 不符")
        for item in items:
            path = item.get("file", "")
            if path not in files:
                raise SystemExit(f"FAIL: 资料 {item.get('id')} 引用了未列出的文件 {path}")
            doc = json.loads(zf.read(path))
            if doc.get("id") != item.get("id"):
                raise SystemExit(f"FAIL: JSON 本体 ID 不一致 {path}")
            for ref in doc.get("figs", {}).values():
                if ref.startswith("data:image/"):
                    continue
                image = str(PurePosixPath(path).parent / ref)
                if ".." in PurePosixPath(ref).parts or image not in files or PurePosixPath(image).suffix not in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"):
                    raise SystemExit(f"FAIL: 图片引用缺失或不安全 {ref}")
        print(f"OK: {manifest.get('title')} / {len(items)} items / {len(files)} files")


if __name__ == "__main__":
    main()

