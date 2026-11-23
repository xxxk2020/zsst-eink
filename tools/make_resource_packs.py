#!/usr/bin/env python3
"""Split 纸上刷题 Doc v1 data into independently importable .zip resource packs.

Usage:
  python tools/make_resource_packs.py --source ./examples/source --out build/resource-packs
  python tools/make_resource_packs.py --source ./examples/source --out build/resource-packs --kind course
  python tools/make_resource_packs.py --source ./examples/source --out build/resource-packs --bundle

The source's HTML/assets are not copied into resource packs. Each entry in data/items.json
becomes one importable course/paper record and its JSON remains an individual data file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

FORMAT = "zsst-resource-pack"
VERSION = 1


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_relative(value: str) -> str:
    p = PurePosixPath(value)
    if p.is_absolute() or ".." in p.parts or "\\" in value or not value.startswith(("data/courses/", "data/papers/")):
        raise ValueError(f"不安全或不受支持的数据路径：{value!r}")
    if p.suffix.lower() != ".json":
        raise ValueError(f"资源文件必须是 JSON：{value!r}")
    return p.as_posix()


def group_slug(group: str, ordinal: int) -> str:
    short = re.sub(r"[^A-Za-z0-9._-]+", "-", group).strip("-.")[:32] or "group"
    return f"group-{ordinal:02d}-{short}-{hashlib.sha1(group.encode('utf-8')).hexdigest()[:6]}"


def load_source(source: Path, kind: str):
    catalog_file = source / "data" / "items.json"
    if not catalog_file.is_file():
        raise FileNotFoundError(f"找不到 {catalog_file}")
    items = json.loads(catalog_file.read_text(encoding="utf-8-sig"))
    if not isinstance(items, list):
        raise ValueError("data/items.json 必须是数组")
    seen_ids = set()
    by_group: OrderedDict[str, list[dict]] = OrderedDict()
    file_owners: dict[str, str] = {}
    for i, item in enumerate(items):
        if not isinstance(item, dict):
            raise ValueError(f"清单第 {i + 1} 项不是对象")
        item_kind = item.get("kind", "drill")
        if kind != "all" and item_kind != kind:
            continue
        if item_kind not in ("course", "drill"):
            raise ValueError(f"不支持的 kind：{item_kind!r}")
        item_id = str(item.get("id", ""))
        if not item_id or item_id in seen_ids:
            raise ValueError(f"资料 ID 为空或重复：{item_id!r}")
        seen_ids.add(item_id)
        rel = safe_relative(str(item.get("file", "")))
        if rel in file_owners and file_owners[rel] != item_id:
            raise ValueError(f"一个数据文件对应多个资料 ID：{rel}")
        file_owners[rel] = item_id
        data_file = source / rel
        if not data_file.resolve().is_relative_to(source.resolve()):
            raise ValueError(f"资料不能链接到来源目录之外：{rel}")
        if not data_file.is_file():
            raise FileNotFoundError(f"清单引用的文件不存在：{rel}")
        doc = json.loads(data_file.read_text(encoding="utf-8-sig"))
        if not isinstance(doc, dict) or doc.get("id") != item_id:
            raise ValueError(f"资料清单 ID 与本体不一致：{rel}")
        if str(doc.get("title", "")) != str(item.get("title", "")):
            raise ValueError(f"资料标题与清单不一致：{rel}")
        group = str(item.get("group") or ("未分组" if item_kind == "course" else "本地题库"))
        by_group.setdefault(group, []).append((item, rel, data_file))
    return by_group


def build_one(out: Path, pack_id: str, title: str, rows: list[tuple[dict, str, Path]]) -> dict:
    manifest_items = []
    manifest_files = []
    payload: list[tuple[str, bytes]] = []
    seen_file = set()
    for item, rel, disk in rows:
        blob = disk.read_bytes()
        if rel in seen_file:
            raise ValueError(f"资源包内重复数据路径：{rel}")
        seen_file.add(rel)
        payload.append((rel, blob))
        manifest_files.append({"path": rel, "size": len(blob), "sha256": sha256_bytes(blob)})
        manifest_items.append(item)
        doc = json.loads(blob.decode("utf-8-sig"))
        root = disk.parents[len(PurePosixPath(rel).parts) - 1].resolve()
        refs = set()
        for value in doc.get("figs", {}).values():
            if not isinstance(value, str):
                raise ValueError("figs 的值必须是图片路径或 data URI")
            if not value.startswith("data:image/"):
                refs.add(value)
        def collect(value):
            if isinstance(value, dict):
                for child in value.values(): collect(child)
            elif isinstance(value, list):
                for child in value: collect(child)
            elif isinstance(value, str) and not value.startswith("data:image/"):
                refs.update(re.findall(r"<img[^>]*\bsrc\s*=\s*['\"]([^'\"]+)['\"]", value, re.I))
                if re.fullmatch(r"[^<>\r\n]+\.(?:jpg|jpeg|png|webp|gif|bmp)", value, re.I):
                    refs.add(value)
        collect(doc)
        for ref in sorted(refs):
            if ref.startswith("data:image/"):
                continue
            path = PurePosixPath(ref)
            if path.is_absolute() or ".." in path.parts or ":" in ref or "\\" in ref:
                raise ValueError(f"只支持安全的本地图片引用：{ref!r}")
            image = path if ref.startswith("data/") else PurePosixPath(rel).parent / path
            image_rel = image.as_posix()
            if not image_rel.startswith(("data/courses/", "data/papers/")) or image.suffix.lower() not in (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"):
                raise ValueError(f"不支持的图片路径：{ref!r}")
            image_disk = (root / image_rel).resolve()
            if not image_disk.is_relative_to(root) or not image_disk.is_file():
                raise ValueError(f"图片缺失或位于来源目录之外：{ref!r}")
            if image_rel in seen_file:
                continue
            seen_file.add(image_rel)
            image_bytes = image_disk.read_bytes()
            payload.append((image_rel, image_bytes))
            manifest_files.append({"path": image_rel, "size": len(image_bytes), "sha256": sha256_bytes(image_bytes)})

    manifest = {
        "format": FORMAT,
        "version": VERSION,
        "packId": pack_id,
        "title": title,
        "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "items": manifest_items,
        "files": manifest_files,
    }
    out_file = out / f"{pack_id}.zip"
    with zipfile.ZipFile(out_file, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as zf:
        zf.writestr("resource-pack.json", json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        for rel, blob in payload:
            zf.writestr(rel, blob)
    return {
        "packId": pack_id,
        "title": title,
        "file": out_file.name,
        "items": len(manifest_items),
        "bytes": out_file.stat().st_size,
        "rawBytes": sum(len(b) for _, b in payload),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source", required=True, type=Path, help="资料目录（含 data/items.json）")
    ap.add_argument("--out", required=True, type=Path, help="资源包输出目录")
    ap.add_argument("--kind", choices=("all", "course", "drill"), default="all", help="选择全部、课程或刷题/试卷类资料")
    ap.add_argument("--single-pack", action="store_true", help="把筛选出的所有 JSON 放进一个可直接导入的 ZIP（每条仍是独立资料）")
    ap.add_argument("--bundle", action="store_true", help="额外将输出目录中的资源包和 index.json 打进外层 zsst-resource-packs.zip；该外层包需先解压")
    args = ap.parse_args()
    source = args.source.resolve()
    out = args.out.resolve()
    groups = load_source(source, args.kind)
    if not groups:
        print("没有符合条件的资料", file=sys.stderr)
        return 2
    if out.exists() and any(out.iterdir()):
        raise ValueError("输出目录非空，请指定新的目录；本工具不会删除已有文件")
    out.mkdir(parents=True, exist_ok=True)
    summaries = []
    if args.single_pack:
        all_rows = [row for group_rows in groups.values() for row in group_rows]
        if not all_rows:
            print("没有符合条件的资料", file=sys.stderr)
            return 2
        if args.kind == "course":
            pack_id, title = "zsst-all-courses", "纸上刷题全量课程"
        elif args.kind == "drill":
            pack_id, title = "zsst-all-drills", "纸上刷题全量刷题与试卷"
        else:
            pack_id, title = "zsst-all-resources", "纸上刷题全量课程与题库"
        summaries.append(build_one(out, pack_id, title, all_rows))
    else:
        for ordinal, (group, rows) in enumerate(groups.items(), start=1):
            # A group can contain mixed kinds in old catalogs. Split per kind so packs stay predictable.
            kind_groups: OrderedDict[str, list] = OrderedDict()
            for row in rows:
                kind_groups.setdefault(str(row[0].get("kind", "drill")), []).append(row)
            for kind_name, kind_rows in kind_groups.items():
                base = group_slug(group, ordinal)
                pack_id = base if len(kind_groups) == 1 else f"{base}-{kind_name}"
                title = group if len(kind_groups) == 1 else f"{group}（{'课程' if kind_name == 'course' else '题库'}）"
                summaries.append(build_one(out, pack_id, title, kind_rows))
    index = {"format": FORMAT, "version": VERSION, "packs": summaries}
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    readme = [
        "# 纸上刷题资源包", "",
        "每个 ZIP 都可独立导入离线应用；题库内容与 APK 分离。",
        "导入某一包后，包内每条 `items` 记录会成为一门课程或一份试卷。重复导入同一资料 ID 会更新，不会重复堆卡片。", "",
        "| 资源包 | 资料数量 | ZIP 大小 |", "|---|---:|---:|",
    ]
    for row in summaries:
        readme.append(f"| {row['title']} (`{row['file']}`) | {row['items']} | {row['bytes'] / 1024 / 1024:.2f} MiB |")
    (out / "资源包说明.md").write_text("\n".join(readme) + "\n", encoding="utf-8")
    if args.bundle:
        bundle = out.parent / "zsst-resource-packs.zip"
        with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as zf:
            for file in sorted(out.iterdir()):
                if file.is_file(): zf.write(file, arcname=file.name)
        print(f"bundle={bundle} size={bundle.stat().st_size}")
    print(f"source={source}")
    print(f"output={out}")
    for row in summaries:
        print(f"{row['file']}\t{row['items']} items\t{row['bytes'] / 1024 / 1024:.2f} MiB\t{row['title']}")
    print(f"TOTAL: {sum(x['items'] for x in summaries)} items / {len(summaries)} packs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
