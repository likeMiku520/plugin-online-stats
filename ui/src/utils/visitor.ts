export interface UaInfo {
  deviceType?: string
  os?: string
  osVersion?: string
  browser?: string
  browserVersion?: string
  engine?: string
  app?: string
  bot?: string
}

/** One page the visitor opened, with the time spent on it. */
export interface PageVisit {
  url: string | null
  title: string | null
  enteredAt: number | null
  /** Null while the page is still open. */
  leftAt: number | null
  durationMs: number | null
}

export interface RecordView {
  name: string
  ip: string | null
  location: string | null
  country: string | null
  countryCode: string | null
  region: string | null
  city: string | null
  districts: string | null
  isp: string | null
  net: string | null
  idc: string | null
  zipcode: string | null
  areacode: string | null
  protocol: string | null
  dec: string | null
  geoResolvedAt: number | null
  userAgent: string | null
  ua: UaInfo
  bot: boolean
  botReason: string | null
  firstPageUrl: string | null
  firstPageTitle: string | null
  pageUrl: string | null
  pageTitle: string | null
  referer: string | null
  firstReferer: string | null
  firstSeenAt: number | null
  lastSeenAt: number | null
  visitCount: number | null
  pageViewCount: number | null
  totalOnlineMs: number | null
  screen: string | null
  viewport: string | null
  timezone: string | null
  language: string | null
  platform: string | null
  cpuCores: string | null
  deviceMemory: string | null
  connection: string | null
  online: boolean
  /** Pages opened, oldest first; the last one is the page in front of them now. */
  pageVisits?: PageVisit[]
  /** How long they have been on the page they are looking at now. */
  currentPageMs?: number | null
}

export interface LocationView {
  ip: string
  dec: string | null
  country: string | null
  countryCode: string | null
  province: string | null
  city: string | null
  districts: string | null
  isp: string | null
  net: string | null
  idc: string | null
  zipcode: string | null
  areacode: string | null
  protocol: string | null
  location: string | null
  resolvedAt: number
  resolved: boolean
  error: string | null
  endpoint: string
}

/** Copies a resolved location onto a row so both visitor views show the same fields. */
export function applyLocation(item: RecordView, data: LocationView) {
  item.location = data.location
  item.country = data.country
  item.countryCode = data.countryCode
  item.region = data.province
  item.city = data.city
  item.districts = data.districts
  item.isp = data.isp
  item.net = data.net
  item.idc = data.idc
  item.zipcode = data.zipcode
  item.areacode = data.areacode
  item.protocol = data.protocol
  item.dec = data.dec
  item.geoResolvedAt = data.resolvedAt
}