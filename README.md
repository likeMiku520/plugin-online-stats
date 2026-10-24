# 在线访客统计

Halo 2.x 插件：实时统计当前有多少访客正在浏览你的博客，并把访客信息保存为历史记录，在后台查看每位访客的属地、设备与访问行为。

- 作者：fufu大王
- 网站：https://blog.fufu520.cn
- 仓库：https://github.com/likeMiku520/plugin-online-stats

## 功能

- **实时在线人数**：通过公开接口提供在线人数与正在浏览的页面，博客前台不渲染任何组件
- **访客历史记录**：访客信息写入 Halo 数据库，重启不丢失，支持在线状态筛选、关键词搜索、分页和排序，后台每 3 秒自动刷新
- **真人 / 机器人区分**：UA 命中爬虫特征、或没有上报浏览器指纹的访问会被标记为机器人，默认只在「真人」分组里显示
- **访客详情**：列表每行的「更多」按钮打开详情抽屉，展示 IP、属地、UA 解析、屏幕与窗口尺寸、语言、时区、CPU 核心数、设备内存、网络类型、来源与访问行为统计
- **按需查询 IP 属地**：详情里的「查询属地」按钮才会请求一次接口，插件不会在后台自动查询；结果写入记录并缓存 7 天
- **配置项**：心跳间隔、离线判定超时、IP 显示开关、属地查询接口地址、历史保留天数、历史最大条数

## 工作原理

1. 前台页面加载插件提供的 `online-stats.js`，每 15 秒上报一次心跳（页面 URL、标题、来源、屏幕尺寸、语言、时区等），脚本不渲染任何 UI
2. 插件在内存中维护在线访客注册表（Key 为 `IP + User-Agent` 设备指纹），超过设定时长（默认 60 秒）没有心跳即视为离线
3. 后台每 20 秒把有变化的在线访客合并写入 `VisitorRecord` 自定义模型，落库到 Halo 数据库
4. 后台页面读取内存索引（插件启动时全量加载），按设置清理过期记录
5. 用户点击「查询属地」时，插件才用设置里的接口查询一次该访客 IP 的中文属地

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
