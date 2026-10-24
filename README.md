# 在线访客统计

Halo 2.x 插件：实时统计当前有多少访客正在浏览你的博客，并把访客信息保存为历史记录，在后台查看每位访客的属地、设备与访问行为。

- 作者：fufu大王
- 网站：https://blog.fufu520.cn
- 仓库：https://github.com/likeMiku520/plugin-online-stats

## 功能

菜单在后台「系统」分类下，分两个页面：

### 访问统计（仪表盘）

- **总览卡片**：当前在线、总浏览量、总访客、今日浏览、今日访客、文章数
- **访问趋势**：按天曲线图 + 直方图，固定显示最近 90 天，可切到近 30 / 7 天
- **来源分析**：浏览器来源、操作系统来源、来源站点，均可点击某一行下钻，看到是哪些访客（IP）贡献的
- **文章排行**：按总浏览量 / 总访客 / 今日浏览 / 停留时长排序，每篇文章点「详情」可看到具体是哪些访客、各看了多少次、停留多久
- **官方数据对照**：文章浏览量同时显示插件统计值与 Halo 官方计数器（`counters.visit`）的值

### 访客明细

- 实时在线访客与历史访客记录，支持真人 / 机器人分组、在线状态筛选、搜索、排序、分页
- 列表内可直接点「查询属地」，不用打开详情
- 详情抽屉展示 IP 属地（按需查询）、UA 解析、屏幕尺寸、语言、时区、CPU 核心数、设备内存、网络类型、来源与访问行为

### 其他

- **只统计文章页**：首页、归档、标签页等不会计入文章浏览量与访客
- **按需查询 IP 属地**：只有点击按钮才请求接口，不会在后台自动查询
- **配置项**：心跳间隔、离线判定超时、本站域名、官方总浏览量修正值、IP 显示开关、属地接口地址、访客记录保留天数与条数

## 工作原理

1. 前台页面加载插件提供的 `online-stats.js`，每 15 秒上报一次心跳（文章名、页面 URL、标题、来源、屏幕尺寸、语言、时区等），脚本不渲染任何 UI
2. 插件按 `IP + User-Agent` 设备指纹维护在线访客；超过设定时长（默认 60 秒）没有心跳即视为离线
3. 心跳被聚合成两种统计：每篇文章的 `ArticleStat` 与每天的 `DailyStat`（浏览量、访客、浏览器、系统、来源、停留时长）
4. 浏览量按「进入一次页面」计一次，停留不动不会重复计数；停留时长按心跳间隔累计
5. 在线访客每 20 秒落库为 `VisitorRecord`，后台列表会实时合并内存中的在线访客
6. 插件启动后每小时同步一次 Halo 官方文章计数，供仪表盘对照

## 数据与隐私

收集的信息：

- **IP 地址**（可在设置中关闭后台展示，仍用于区分访客）
- **浏览器 User-Agent**（用于解析设备与浏览器）
- **当前浏览页面的 URL 和标题**、来源页面
- **浏览器上报的屏幕尺寸、语言、时区、CPU 核心数、设备内存、网络类型**

存储位置：

- 在线状态：Halo 进程内存，重启清空
- 历史记录：Halo 数据库中的 `VisitorRecord` 资源（`online-stats.halo.run/v1alpha1`），按保留天数与条数自动清理
- IP 属地：只有点击查询时才把该访客 IP 发送到设置的接口（默认 `api.mir6.com`），结果缓存在内存 7 天并写入记录

## 构建

需要 JDK 21、Node 20+、pnpm 9。

```
# macOS / Linux
./gradlew build

# Windows
gradlew.bat build
```

产物：`build/libs/plugin-online-stats-<version>.jar`。安装方式：Halo 后台 → 插件 → 安装 → 上传 jar，启用即可。

## 文档

- [接口文档](docs/API.md)
- [常见问题](docs/FAQ.md)
- [更新日志](CHANGELOG.md)

## 接口

| 路径 | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `/apis/online-stats.halo.run/v1alpha1/heartbeat` | POST | 公开 | 前台心跳上报 |
| `/apis/online-stats.halo.run/v1alpha1/online-count` | GET | 公开 | 查询在线人数 |
| `/apis/online-stats.halo.run/v1alpha1/online-pages` | GET | 公开 | 在线人数 + 正在浏览的页面标题 |
| `/apis/online-stats.halo.run/v1alpha1/online-stats` | GET | 公开 | 返回跟踪脚本 |
| `/apis/online-stats.halo.run/v1alpha1/visitors` | GET | 管理员 | 当前在线访客 |
| `/apis/online-stats.halo.run/v1alpha1/records` | GET | 管理员 | 历史访客，支持 `page`、`size`、`keyword`、`status`、`sort` |
| `/apis/online-stats.halo.run/v1alpha1/records/{name}` | GET | 管理员 | 单个访客详情 |
| `/apis/online-stats.halo.run/v1alpha1/location` | GET | 管理员 | 按需查询 IP 属地，参数 `name` 或 `ip` |

## 许可

本项目以 GPL-3.0 发布，可自由使用、修改和分发；再分发时需保留同样的开源许可。

本项目最初基于 weimu12 的开源项目 plugin-online-stats 演进而来，代码后续经过大幅重写。
