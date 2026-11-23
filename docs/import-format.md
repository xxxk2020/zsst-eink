# 本地资料格式

应用接受 Doc v1 JSON 和 `zsst-resource-pack` v1 ZIP。推荐 UTF-8、稳定的资料 `id`、每份资料内唯一且稳定的 `no`。记录与收藏以资料 ID 和题号关联；更换它们会影响续接。

## 选择题 JSON

```json
{
  "v": 1,
  "id": "my-paper-001",
  "kind": "drill",
  "subject": "行测",
  "title": "我的练习卷",
  "questions": [
    {
      "no": 1,
      "type": "数量关系",
      "stem": "1 + 1 等于多少？",
      "options": ["1", "2", "3", "4"],
      "answer": "B",
      "explain": "1 + 1 = 2。"
    }
  ]
}
```

`answer` 推荐使用 A–H 字母；兼容从 0 开始的数字索引。没有答案时省略，不要为缺答案的题填 A。解析字段为 `explain`。`kind: "course"` 代表按 `course` 分组的本地练习集，不涉及视频或课程服务。

## 共用材料与申论

根对象的 `materials` 是 `{ "no": 1, "text": "…", "imgs": [] }` 数组。题目的 `mats: [1]` 引用材料编号；多个选择题引用相同材料时可使用上下分区。

申论设置 `subject: "申论"`、`shape: "shenlun"`，题目保留 `stem`、`mats`，不设置选择题 `options`。参考答案用 `ref`，解析用 `explain`；可附 `req`（要求）、`limit`（字数）、`score`。应用用于阅读和标记练习，不自动评分主观题。

## 图片、公式和表格

- `imgs`、`oimgs`、`eimgs` 分别是题干、各选项、解析的图片引用数组。
- 根对象 `figs` 把引用名映射到图片路径或 `data:image/...;base64,...`。PNG、JPEG 等位图公式可配 `formulaImages: ["引用名"]`，以较小的行内尺寸展示。
- `oimgs` 与 `options` 按位置一一对应。整张题图已含选项时，可放入题干 `imgs`，再给出 `options: ["", "", "", ""]`，保留四个可选入口。
- 简单 HTML 的上下标、段落、表格和 Markdown 竖线表格可原生呈现。此工具不是浏览器，不执行脚本，也不是完整的 LaTeX 排版引擎。
- 应用只读取本地图片；远程 URL 不会下载。

独立图片请与 JSON 一起装入 ZIP。单独导入 JSON 无法授权访问旁边的文件。推荐使用 `figs` 与 JSON 所在文件夹内的相对路径，禁止 `..`、绝对路径、外部链接。

## 资源包

ZIP 根目录包含 `resource-pack.json`，其他文件放在 `data/papers/` 或 `data/courses/` 内：

```text
resource-pack.json
data/papers/my-paper.json
data/papers/images/chart.png
```

清单字段：`format: "zsst-resource-pack"`、`version: 1`、`packId`、`title`、`items` 与 `files`。`items` 的每项包含资料的 `id`、`kind`、`title`、`file`；`files` 列出每个 JSON 和图片的 `path`、字节 `size`、`sha256`。同 ID 再导入会更新资料；原记录依赖题号与内容是否仍对应，请先备份。

无需手写哈希，可以仿照 `examples/source/data/items.json` 建立目录后执行：

```sh
python tools/make_resource_packs.py --source examples/source --out build/example-packs --single-pack
python tools/validate_resource_pack.py build/example-packs/zsst-all-resources.zip
```

工具会收集本地独立图片并计算清单校验值；输出目录非空时会停止，不删除已有资料。不加 `--single-pack` 会按资料分组生成多个可分别导入的 ZIP；`--bundle` 生成的外层集合包需要先解压。

应用限制单文件 64 MiB、解压总量 350 MiB，并检查路径、校验值与存储余量。这些上限不代表低内存设备适合一次加载大图，建议按合理课次拆包。

## 进度备份

进度备份是单独的 JSON，包含作答、收藏、位置和历史，不包含题干、图片与字体。通过“设置 → 资料管理”导出、导入。分享源码或报告前请勿附带个人备份。
