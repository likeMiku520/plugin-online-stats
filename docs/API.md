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
    "total": 12,
    "todayNew": 5,
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

自定义模型 `VisitorRecord`：

- apiVersion：`online-stats.halo.run/v1alpha1`
- kind：`VisitorRecord`
- 名称：`v-` + `sha256(ip + "|" + userAgent)` 前 16 位，同一设备多次访问合并为一条
