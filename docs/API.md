# 接口文档

所有接口前缀：`/apis/online-stats.halo.run/v1alpha1`

权限说明：

- **公开**：不需要登录，匿名可调用
- **管理员**：需要 Halo 超级管理员身份（浏览器携带登录态即可）

## 心跳上报

`POST /heartbeat`

请求体（`application/json`，字段都可选，服务端会截断超长内容）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| visitorId | string | 访客会话 ID，最长 64 |
| url | string | 当前页面路径，最长 500 |
| title | string | 页面标题，最长 200 |
| referer | string | 来源页面 |
| screen | string | 屏幕分辨率，如 `2560x1440` |
| viewport | string | 窗口尺寸，如 `1280x800` |
| timezone | string | 时区，如 `Asia/Shanghai` |
| language | string | 语言列表 |
| platform | string | `navigator.platform` |
| cores | string | CPU 核心数 |
| memory | string | 设备内存 |
| connection | string | 网络类型与下行速率 |

响应：`{"ok": true}`。同一 IP 每分钟超过 60 次返回 `429`。

## 在线人数

`GET /online-count`（公开）

```json
{ "count": 3 }
```

## 在线人数 + 页面标题

`GET /online-pages`（公开）

```json
{
  "total": 2,
  "pages": [
    { "title": "关于我", "count": 1 },
    { "title": "Halo 使用笔记", "count": 1 }
  ]
}
```

## 跟踪脚本

`GET /online-stats`（公开）

返回注入到主题 `<head>` 的 JavaScript，响应头带 `Cache-Control: no-store`。

脚本开头注入的配置：

```js
window.__ONLINE_STATS__ = {
  heartbeatInterval: 15,  // 心跳间隔（秒）
  chatEnabled: true,      // 是否允许后台给访客发消息
  chatPollSeconds: 5      // 对话轮询间隔（秒）
};
```

## 访客轮询消息

`GET /chat-inbox?since=<seq>`（公开）

只能读到自己地址的对话：IP 由请求本身决定，不接受参数。页面不可见时脚本不会请求。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| since | 0 | 已经收到的最大 `seq`，只返回比它新的消息 |

`since=0` 表示这个浏览器第一次轮询，此时返回最近一批消息（最多 50 条）作为历史，脚本不会因此弹窗。

```json
{
  "enabled": true,
  "latestSeq": 12,
  "messages": [
    {
      "seq": 12,
      "direction": "admin",
      "text": "你好，看到你在看这篇博客～",
      "sentAt": 1760000000000,
      "adminName": "fufu大王",
      "adminAvatar": "https://blog.fufu520.cn/upload/avatar.png"
    }
  ]
}
```

`direction` 为 `admin`（后台发出）或 `visitor`（访客回复）。同一地址每分钟超过 60 次返回 `429`。

## 访客回复

`POST /chat-reply`（公开）

只接受纯文本，服务端会去掉控制字符、把连续空行压成一行、截断到 1000 字。

| 字段 | 说明 |
| --- | --- |
| text | 回复内容，必填 |
| url | 回复时所在页面，可选 |
| title | 回复时页面标题，可选 |

```json
{ "ok": true, "seq": 13 }
```

关闭「允许在后台给访客发消息」后返回 `403`。

## 对话列表

`GET /chat-conversations`（管理员）

```json
{
  "loaded": true,
  "unreadTotal": 2,
  "items": [
    {
      "ip": "1.2.3.4",
      "unread": 2,
      "total": 5,
      "lastText": "在的",
      "lastDirection": "visitor",
      "lastAt": 1760000000000
    }
  ]
}
```

关闭「后台显示访客 IP 地址」时，`ip` 只保留网段（`1.2.3.*`）。

## 单个对话

`GET /chat-messages?ip=<ip>`（管理员）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| ip | 必填 | 对话对应的访客地址 |
| markRead | true | 是否把访客的回复标记为已读 |

```json
{
  "ip": "1.2.3.4",
  "messages": [
    {
      "seq": 12,
      "direction": "admin",
      "text": "你好",
      "sentAt": 1760000000000,
      "adminName": "fufu大王",
      "adminAvatar": "https://blog.fufu520.cn/upload/avatar.png",
      "visitorKey": null,
      "pageUrl": null,
      "pageTitle": null,
      "readByAdmin": true
    }
  ]
}
```

## 后台发送消息

`POST /chat-send`（管理员）

| 字段 | 说明 |
| --- | --- |
| ip | 目标访客地址，必填 |
| text | 消息内容，必填，纯文本，最长 1000 字 |

```json
{ "ok": true, "seq": 12 }
```

消息上记录的作者名字与头像来自当前登录账号（`uc.api.halo.run` 的当前用户），不由请求体决定，因此访客看到的身份无法被伪造。

## 当前在线访客

`GET /visitors`（管理员）

```json
{
  "total": 1,
  "items": [
    {
      "visitorId": "v-xxxx",
      "ip": "1.2.3.4",
      "userAgent": "Mozilla/5.0 ...",
      "pageUrl": "/archives/1",
      "pageTitle": "示例文章",
      "firstSeenAt": 1760000000000,
      "lastSeenAt": 1760000015000
    }
  ]
}
```

## 历史访客

`GET /records`（管理员）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| page | 1 | 页码 |
| size | 20 | 每页条数，最大 100 |
| keyword | 空 | 匹配 IP、属地、城市、ISP、页面标题、URL、UA |
| status | all | `all` / `online` / `offline` |
| type | all | `all` / `human` / `bot` |
| sort | lastSeenAt | `lastSeenAt` / `firstSeenAt` / `visits` |

```json
{
  "total": 12,
  "page": 1,
  "size": 20,
  "stats": {
    "online": 2,
    "onlineHumans": 1,
    "onlineBots": 1,
    "total": 12,
    "historyTotal": 12,
    "todayNew": 5,
    "todayHumans": 4,
    "todayBots": 1,
    "bots": 3,
    "humans": 9,
    "loaded": true,
    "records": 12,
    "flushRuns": 30,
    "lastFlushAt": 1760000000000,
    "saved": 12,
    "failed": 0,
    "lastError": null
  },
  "items": [
    {
      "name": "v-0123456789abcdef",
      "ip": "1.2.3.4",
      "location": "中国[CN] 江苏省 南京市",
      "country": "中国",
      "countryCode": "CN",
      "region": "江苏省",
      "city": "南京市",
      "districts": "",
      "isp": "中国电信",
      "net": "电信网络",
      "idc": "",
      "zipcode": "210000",
      "areacode": "025",
      "protocol": "IPv4",
      "dec": "16909060",
      "geoResolvedAt": 1760000000000,
      "bot": false,
      "botReason": null,
      "userAgent": "Mozilla/5.0 ...",
      "ua": {
        "deviceType": "桌面",
        "os": "Windows 10/11",
        "browser": "Chrome",
        "browserVersion": "124.0",
        "engine": "Blink/WebKit"
      },
      "pageTitle": "示例文章",
      "pageUrl": "/archives/1",
      "visitCount": 3,
      "pageViewCount": 7,
      "totalOnlineMs": 186000,
      "firstSeenAt": 1759900000000,
      "lastSeenAt": 1760000000000,
      "online": true
    }
  ]
}
```

`stats.lastError` 不为空表示最近一次落库失败，内容为异常摘要；`stats.saved` / `stats.failed` 是累计成功与失败次数。

## 单个访客详情

`GET /records/{name}`（管理员）

返回结构同 `items` 中的单个对象，不存在时返回 `404`。

## 按需查询 IP 属地

`GET /location`（管理员）

| 参数 | 说明 |
| --- | --- |
| name | 记录名，优先使用该记录保存的 IP |
| ip | 直接指定 IP（与 name 二选一） |

调用会请求设置中的属地接口，成功后写入该记录并缓存 7 天。

```json
{
  "ip": "1.2.3.4",
  "resolved": true,
  "error": null,
  "endpoint": "https://api.mir6.com/api/ip?ip=%s&type=json",
  "dec": "16909060",
  "country": "中国",
  "countryCode": "CN",
  "province": "江苏省",
  "city": "南京市",
  "districts": "",
  "isp": "中国电信",
  "net": "电信网络",
  "idc": "",
  "zipcode": "210000",
  "areacode": "025",
  "protocol": "IPv4",
  "location": "中国[CN] 江苏省 南京市",
  "resolvedAt": 1760000000000
}
```

失败时 `resolved` 为 `false`，`error` 说明原因（HTTP 状态、接口返回非 JSON、接口业务错误等），`endpoint` 是本次实际使用的地址，便于排查配置问题。

## 数据模型

自定义模型：

- `VisitorRecord`（`visitorrecords`）：单个访客的历史记录，名称是 `v-` + `sha256(IP + "|" + UA)` 前 16 位
- `ArticleStat`（`articlestats`）：单篇文章的聚合统计，名称与 Halo 的 Post 资源名一致
- `DailyStat`（`dailystats`）：按天聚合的统计，名称是 `yyyy-MM-dd`

## 统计接口

以下接口都属于 `statistics` 资源，需要管理员权限。

### 总览

`GET /statistics/overview`

```json
{
  "views": 1280,
  "visitors": 356,
  "uniqueVisitors": 356,
  "officialViews": 1310,
  "totalOnlineMs": 86400000,
  "todayViews": 42,
  "todayVisitors": 18,
  "online": 3,
  "articles": 27,
  "onlineSince": "2024-06-01",
  "loaded": true
}
```

- `views` / `visitors`：插件统计的文章浏览量与去重访客
- `officialViews`：Halo 官方计数器（`counters.visit`）的总和
- `officialViews`：Halo 官方计数器（`counters.visit`）的总和，加上设置里的「官方总浏览量修正值」

### 访问趋势

`GET /statistics/trend?days=30`

```json
{
  "days": 30,
  "launchDate": "2024-06-01",
  "series": [
    { "date": "2026-10-01", "views": 12, "visitors": 9, "cumulative": 1180 }
  ],
  "daily30": [{ "date": "2026-10-01", "views": 12, "visitors": 9 }],
  "daily7": [{ "date": "2026-10-24", "views": 42, "visitors": 18 }]
}
```

`series` 是最近 `days` 天、补齐空缺日期的连续序列，可直接用于画曲线图。

### 文章列表

`GET /statistics/articles?sort=views&limit=50`

`sort` 支持 `views`、`visitors`、`today`、`duration`。

```json
{
  "sort": "views",
  "items": [
    {
      "postName": "post-abc",
      "title": "示例文章",
      "slug": "hello",
      "permalink": "/archives/hello",
      "views": 120,
      "officialViews": 131,
      "visitors": 88,
      "todayViews": 5,
      "todayVisitors": 4,
      "totalOnlineMs": 3600000,
      "avgOnlineMs": 40909,
      "browsers": [{ "name": "Chrome", "views": 70 }],
      "systems": [{ "name": "Windows 10/11", "views": 60 }],
      "referrers": [{ "name": "直接访问", "views": 40 }]
    }
  ]
}
```

### 文章下钻

`GET /statistics/articles/{postName}`

返回该文章的完整统计，外加 `visitors` 数组：每个访客看了几次、累计停留多久、最后访问时间、IP（受 IP 显示开关控制）。

### 来源统计

`GET /statistics/sources?dimension=browser&days=30`

`dimension` 支持 `browser`、`system`、`referer`。也支持用 `from`、`to` 指定日期区间。

```json
{
  "dimension": "browser",
  "totalViews": 1280,
  "items": [{ "name": "Chrome", "views": 700, "visitors": 210 }]
}
```

### 来源下钻

`GET /statistics/sources/detail?dimension=browser&value=Chrome&days=30`

返回该来源下的访客清单：`views`、`visitors` 与 `items`（每个访客看了几次、对应 IP）。

### 单个访客的全部行为

`GET /statistics/visitors/{visitorKey}`

返回这个访客看过哪些文章、各看了几次、停留多久，以及总浏览量与总停留时长。
