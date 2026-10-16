# -*- coding: utf-8 -*-
"""文档：专用包目录 packs/。"""
import io

# ------------------------------------------------------------------ 开发日志
P = r"C:\Users\98687\Documents\py脚本\重力感应壁纸\开发日志.md"
s = io.open(P, encoding="utf-8", newline="").read().replace("\r\n", "\n")

old = """21. **＋ 改用法**：项目页右上角 ＋ 不再直接新建，改为菜单「新建空白项目 / 导入作品包·海报包 …」；导入拉起**系统文件管理器**，选中的 `.gwp` / `.tendies` 拷进 app 缓存后按扩展名分派——`.gwp` 从此可以从任意位置（下载目录、U 盘、聊天软件目录）导回本 app。
"""
new = """21. **＋ 改用法**：项目页右上角 ＋ 不再直接新建，改为菜单「新建空白项目 / 导入作品包·海报包 …」；导入拉起**系统文件管理器**，选中的 `.gwp` / `.tendies` 拷进 app 缓存后按扩展名分派——`.gwp` 从此可以从任意位置（下载目录、U 盘、聊天软件目录）导回本 app。
22. **包专用目录**：第二个标签页改名「海报包 / 作品包」，**固定扫描 `/sdcard/Download/gravitywall/packs/`**（页面上直接标出这个路径），`.tendies` 与 `.gwp` 混排在同一个列表里；**两个导出器（导出海报包 / 导出作品包）也都写进这个目录**。早期直接丢在 `gravitywall/` 根下的包会在扫描时**自动挪进 `packs/`**（只动这两种后缀，同名不覆盖，状态栏会提示"已把 N 个包移入 packs/"）。这样根目录只剩 config.json、壁纸备份、墙面图与调试报告，不再和包混在一起。
"""
assert s.count(old) == 1, "log item anchor"
s = s.replace(old, new, 1)

# 关键结论：第 2 节（开发循环）后补一条"共享目录分工"
old = "### 3. 锁屏层级（真机核对，2026-10-15）"
new = """### 2.5 共享目录分工（`/sdcard/Download/gravitywall/`）

- `projects/<项目名>/`：项目本体（`project.json` + `images/`）。
- `packs/`：**海报包 / 作品包专用目录**——列表页只扫这里，两个导出器也只写这里（`.tendies` 与 `.gwp` 同列）。
- 根目录：`config.json`（模块的配置后备）、`wallpaper_backup.png` / `wallpaper_src.png`（锁屏壁纸备份与源图）、`pack_*.png`（墙面图）。调试报告 `gravitywall_report_*.txt` / `gravitywall_live_*.txt` 写在 `/sdcard/Download/` 根目录下，不在这个文件夹里。
- 迁移策略：根目录里历史遗留的 `.tendies` / `.gwp` 在扫描时被挪进 `packs/`；只识别这两种后缀，同名冲突时保留原文件不覆盖。

### 3. 锁屏层级（真机核对，2026-10-15）"""
assert s.count(old) == 1, "section 3 anchor"
s = s.replace(old, new, 1)
io.open(P, "w", encoding="utf-8", newline="\n").write(s)

# ------------------------------------------------------------------ 设计方案
P = r"C:\Users\98687\Documents\py脚本\重力感应壁纸\设计方案.md"
s = io.open(P, encoding="utf-8", newline="").read().replace("\r\n", "\n")

old = "落在 `/sdcard/Download/gravitywall/`；导入有两条路——「海报包」标签页里点它"
new = "落在专用目录 `/sdcard/Download/gravitywall/packs/`；导入有两条路——「海报包 / 作品包」标签页里点它"
assert s.count(old) == 1, "gwp path anchor"
s = s.replace(old, new, 1)

old = "页内分**两个标签页**（项目 / 海报包）。"
new = "页内分**两个标签页**（项目 / 海报包 / 作品包——第二页固扫 `packs/` 目录，`.tendies` 与 `.gwp` 同列，导出的包也全落在这里）。"
assert s.count(old) == 1, "tab anchor"
s = s.replace(old, new, 1)
io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("docs ok")
