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
      "authorName": "fufu大王",
      "authorAvatar": "https://blog.fufu520.cn/upload/fufu_touming.webp"
    }
  ]
}
```

`direction` 为 `admin`（后台发出）或 `visitor`（访客回复）。`authorName` / `authorAvatar` 只在后台发出的消息上出现。

会话按**设备指纹**（客户端地址 + User-Agent，与访客明细同一套 key）归属，不由参数决定：同一地址下的另一台设备、或伪造转发头的人，拿到的是另一个 key，读不到这段对话。

非加密连接（既不是 https，也没有 `X-Forwarded-Proto: https`）返回 `403 insecure transport`；可在设置里关闭「仅允许 HTTPS」。同一地址每分钟超过 60 次返回 `429`。

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

作者身份不接受请求体传入：如果访客当前已登录 Halo，服务端从会话里读出账号的显示名与头像存下来，后台就会显示这个人；没有登录则记为「访客」。

关闭「允许在后台给访客发消息」后返回 `403`。

## 对话列表

`GET /chat-conversations`（管理员）

```json
{
  "loaded": true,
  "enabled": true,
  "unreadTotal": 2,
  "items": [
    {
      "visitorKey": "v-0123456789abcdef",
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

`visitorKey` 就是访客明细里那条记录的 name，后台按它定位对话；关闭「后台显示访客 IP 地址」时 `ip` 只保留网段（`1.2.3.*`），不影响对话。

## 单个对话

`GET /chat-messages?key=<visitorKey>`（管理员）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| key | 必填 | 对话所属设备的 visitorKey（记录 name） |
| markRead | true | 是否把访客的回复标记为已读 |

```json
{
  "visitorKey": "v-0123456789abcdef",
  "ip": "1.2.3.4",
  "enabled": true,
  "avatar": "https://blog.fufu520.cn/upload/fufu_touming.webp",
  "messages": [
    {
      "seq": 12,
      "direction": "admin",
      "text": "你好",
      "sentAt": 1760000000000,
      "authorName": "fufu大王",
      "authorAvatar": "https://blog.fufu520.cn/upload/fufu_touming.webp",
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
| key | 目标设备的 visitorKey，必填 |
| text | 消息内容，必填，纯文本，最长 1000 字 |

```json
{ "ok": true, "seq": 12 }
```

消息上记录的作者名字来自当前登录账号（`uc.api.halo.run` 的当前用户），不由请求体决定，因此访客看到的身份无法被伪造；头像则固定取设置里的「对话卡片里显示的头像」（默认 `https://blog.fufu520.cn/upload/fufu_touming.webp`），与登录的是哪个账号无关。`GET /chat-messages` 返回的 `avatar` 就是这张头像。

访客侧脚本用最新一条后台消息的头像渲染整条对话，所以同一条对话里不会出现两种头像。

后台读到的访客消息会带上 `authorName` / `authorAvatar`：访客当时已登录 Halo 就有值，没登录就是 `null`，界面显示「访客」。

设置里的「即时通讯（后台给访客发消息）」是整个功能的总开关。关掉之后，`/chat-send`、`/chat-reply` 都会返回 `403`，`/chat-inbox` 返回空消息，两个后台读接口的 `enabled` 会变成 `false`。这个功能仅为学习测试使用。

对话记录按设置里的「访客记录保留天数」清理：插件每 5 分钟检查一次，超过天数的消息会从内存和存储里一起删除；单个对话另外最多保留 100 条、最多 200 个对话。访客侧不做任何本地存储，刷新页面后光标从零开始，也不会留下任何痕迹。

## 权限边界

对话功能不接受任何登录凭据，也从不签发凭据，因此**不可能通过它取得后台权限**。具体的边界：

| 调用方 | 能做什么 | 依据 |
| --- | --- | --- |
| 匿名访客 | 只能 `GET /chat-inbox`、`POST /chat-reply` | 角色模板只把这两个资源授予匿名角色 |
| 匿名访客 | 调 `/chat-send`、`/chat-conversations`、`/chat-messages` 一律 403 | 这三个资源只授予超级管理员角色；Halo 按 URL 的第一段判定资源名，`/chat-reply` 与 `/chat-send` 是两个不同资源，前者放行不会连带放行后者 |
| 匿名访客 | 只能读写**自己来源地址**的对话 | 两个公开接口的 IP 都取自请求本身（`X-Forwarded-For` / `X-Real-IP` / 连接地址，与访客记录同一套解析），不接受任何 IP 参数 |
| 匿名访客 | 回复只能作为访客留言 | 端点把 `direction` 写死为 `visitor`、作者名与头像写死为 `null`，请求体只提供正文、页面地址与标题，且都经过截断 |

另外：访客回复最多 1000 字、每分钟 60 次，单个对话最多保留 100 条、最多 200 个对话；后台页面用 Vue 插值（转义）渲染消息，前台脚本用文本节点渲染，插件里没有任何 `v-html` / `innerHTML` / `eval`，所以留言内容不会变成可执行的代码，也就没有借 XSS 偷取后台会话的路径。`GET /chat-inbox` 只返回正文、方向、时间和管理员对外显示的名字与头像，不含其它访客的 IP、设备指纹或访问记录。

一个平台层面的说明：如果站点前面有反向代理，Halo 以 `server.forward-headers-strategy: native` 处理 `X-Forwarded-For`，插件沿用同一套解析以便后台看到的 IP 与访客明细一致。能否伪造这个头取决于部署（代理是否会覆盖它），而且即使伪造成功也只能落到另一个**设备 key** 上——对话归属设备，不归属地址，所以冒充别人的地址并不等于能读到别人的对话。

## 关于「加密」的边界

需要说清楚这个功能做到了什么、没做到什么：

- **传输**：HTTPS 由站点自己提供，这是防止被中途截获的关键。插件另外提供了一个可选的「仅允许 HTTPS」开关（默认关闭），打开后服务端判断为非加密的请求会被 403 拒掉；但它只能看到代理转发的那一跳，代理没把 `X-Forwarded-Proto` 传对时会对正常的 HTTPS 站点误判，因此默认不启用。
- **访问**：对话归属一台设备（地址 + User-Agent），读写都不接受参数指定；后台三个写/读接口只授予超级管理员角色；插件不签发任何凭据，因此不存在通过它提权的路径。
- **存储**：消息只存在你自己的 Halo 数据库里，不经过任何第三方；按保留天数自动清理。
- **没有做到的**：这不是端到端加密。服务端必须持有明文，才能在你不在线时把消息暂存、并在后台展示给你，所以任何能直接读取数据库或服务器内存的人都能看到内容。要真正做到端到端，需要双方交换密钥、且后台无法解密，那样你也读不到访客的回复。

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
