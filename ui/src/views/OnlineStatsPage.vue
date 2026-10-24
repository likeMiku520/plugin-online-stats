<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import axios from 'axios'

interface UaInfo {
  deviceType?: string
  os?: string
  osVersion?: string
  browser?: string
  browserVersion?: string
  engine?: string
  app?: string
  bot?: string
}

interface RecordView {
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
}

interface LocationView {
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

interface Stats {
  online: number
  total: number
  todayNew: number
  loaded: boolean
  humans?: number
  bots?: number
  records?: number
  flushRuns?: number
  lastFlushAt?: number
  saved?: number
  failed?: number
  lastError?: string | null
}

const api = '/apis/online-stats.halo.run/v1alpha1'

const items = ref<RecordView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(true)
const keyword = ref('')
const status = ref<'all' | 'online' | 'offline'>('all')
const visitorType = ref<'human' | 'bot' | 'all'>('human')
const sort = ref<'lastSeenAt' | 'firstSeenAt' | 'visits'>('lastSeenAt')
const stats = ref<Stats>({
  online: 0,
  total: 0,
  todayNew: 0,
  loaded: false,
  flushRuns: 0,
  saved: 0,
  failed: 0,
  lastError: null,
})
const detail = ref<RecordView | null>(null)
const copied = ref('')
const geoLoading = ref(false)
const geoError = ref('')
const geoEndpoint = ref('')
const rowBusy = reactive<Record<string, boolean>>({})
const rowError = reactive<Record<string, string>>({})

let pollTimer: number | undefined
let searchTimer: number | undefined

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const canPrev = computed(() => page.value > 1)
const canNext = computed(() => page.value < totalPages.value)

async function load() {
  loading.value = true
  try {
    const { data } = await axios.get(`${api}/records`, {
      params: {
        page: page.value,
        size: size.value,
        keyword: keyword.value,
        status: status.value,
        type: visitorType.value,
        sort: sort.value,
      },
    })
    items.value = data.items || []
    total.value = data.total || 0
    stats.value = data.stats || {
      online: 0,
      total: 0,
      todayNew: 0,
      loaded: true,
      flushRuns: 0,
      saved: 0,
      failed: 0,
      lastError: null,
    }
    if (detail.value) {
      const fresh = items.value.find((item) => item.name === detail.value?.name)
      if (fresh) {
        detail.value = fresh
      }
    }
  } catch (e) {
    /* ignore network errors, the next poll retries */
  } finally {
    loading.value = false
  }
}

async function lookupLocation(force = false) {
  if (!detail.value || geoLoading.value) {
    return
  }
  if (!force && detail.value.geoResolvedAt) {
    return
  }
  const name = detail.value.name
  geoLoading.value = true
  geoError.value = ''
  try {
    const { data } = await axios.get<LocationView>(`${api}/location`, {
      params: { name },
    })
    geoEndpoint.value = data.endpoint || ''
    if (!data.resolved) {
      geoError.value = data.error || '接口没有返回有效属地'
    }
    await load()
  } catch (e: any) {
    const status = e?.response?.status
    geoError.value = status === 403 ? '没有权限调用查询接口' : '查询失败，请稍后重试'
  } finally {
    geoLoading.value = false
  }
}

function applyLocation(item: RecordView, data: LocationView) {
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

async function lookupRow(item: RecordView) {
  if (rowBusy[item.name]) {
    return
  }
  rowBusy[item.name] = true
  rowError[item.name] = ''
  try {
    const { data } = await axios.get<LocationView>(`${api}/location`, {
      params: { name: item.name },
    })
    if (data.resolved) {
      applyLocation(item, data)
    } else {
      rowError[item.name] = data.error || '查询失败'
    }
  } catch (e: any) {
    rowError[item.name] = e?.response?.status === 403 ? '没有权限' : '查询失败'
  } finally {
    rowBusy[item.name] = false
  }
}

function onKeyword() {
  if (searchTimer) {
    window.clearTimeout(searchTimer)
  }
  searchTimer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 400)
}

function setStatus(value: 'all' | 'online' | 'offline') {
  status.value = value
  page.value = 1
  load()
}

function setVisitorType(value: 'human' | 'bot' | 'all') {
  visitorType.value = value
  page.value = 1
  load()
}

function setSort(value: 'lastSeenAt' | 'firstSeenAt' | 'visits') {
  sort.value = value
  page.value = 1
  load()
}

function goPage(delta: number) {
  const next = page.value + delta
  if (next < 1 || next > totalPages.value) {
    return
  }
  page.value = next
  load()
}

function openDetail(item: RecordView) {
  geoError.value = ''
  detail.value = item
}

function closeDetail() {
  detail.value = null
  geoError.value = ''
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function fmtTime(ms: number | null): string {
  if (!ms) {
    return '—'
  }
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`
}

function fmtRelative(ms: number | null): string {
  if (!ms) {
    return '—'
  }
  const diff = Date.now() - ms
  if (diff < 60_000) {
    return '刚刚'
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`
  }
  return `${Math.floor(diff / 86_400_000)} 天前`
}

function fmtDuration(ms: number | null): string {
  if (!ms || ms <= 0) {
    return '—'
  }
  const seconds = Math.floor(ms / 1000)
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) {
    return `${h} 小时 ${m} 分`
  }
  if (m > 0) {
    return `${m} 分 ${s} 秒`
  }
  return `${s} 秒`
}

function deviceIcon(ua: UaInfo): string {
  if (ua.deviceType === '手机') {
    return '📱'
  }
  if (ua.deviceType === '平板') {
    return '💻'
  }
  if (ua.deviceType === '爬虫/机器人') {
    return '🤖'
  }
  return '🖥️'
}

function deviceSummary(item: RecordView): string {
  const ua = item.ua || {}
  const parts = [ua.os, ua.browser].filter(Boolean) as string[]
  if (parts.length === 0) {
    return '未知设备'
  }
  return parts.join(' · ')
}

async function copy(text: string | null, tag: string) {
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    copied.value = tag
    window.setTimeout(() => {
      copied.value = ''
    }, 1500)
  } catch (e) {
    /* clipboard unavailable */
  }
}

watch([page, size], () => load())

onMounted(() => {
  load()
  pollTimer = window.setInterval(load, 3_000)
})

onUnmounted(() => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
  }
  if (searchTimer) {
    window.clearTimeout(searchTimer)
  }
})
</script>

<template>
  <div class="os-page">
    <div class="os-page__header">
      <div>
        <h2 class="os-page__title">在线访客统计</h2>
        <p class="os-page__desc">
          实时在线人数与访客历史记录，每 3 秒自动刷新。在线人数可通过公开接口
          <code>/apis/online-stats.halo.run/v1alpha1/online-count</code> 获取。
        </p>
      </div>
    </div>

    <div class="os-page__stats">
      <div class="os-stat">
        <span class="os-stat__label">当前在线</span>
        <span class="os-stat__value os-stat__value--online">{{ stats.online }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">历史访客</span>
        <span class="os-stat__value">{{ stats.humans ?? stats.total }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">今日新增</span>
        <span class="os-stat__value">{{ stats.todayNew }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">记录状态</span>
        <span class="os-stat__value os-stat__value--small">
          {{ stats.loaded ? '已加载' : '加载中' }}
        </span>
        <span class="os-stat__hint">
          落库 {{ stats.saved ?? 0 }} · 失败 {{ stats.failed ?? 0 }}
        </span>
      </div>
    </div>

    <div v-if="stats.lastError" class="os-alert">
      <strong>历史记录写入失败</strong>
      <span>{{ stats.lastError }}</span>
      <span class="os-alert__hint">请检查 Halo 日志中 online-stats 相关记录</span>
    </div>
    <div
      v-else-if="stats.loaded && total === 0 && (stats.flushRuns ?? 0) === 0"
      class="os-alert os-alert--info"
    >
      持久化任务每 20 秒执行一次，尚未产生第一条记录，稍候会自动出现。
    </div>

    <div class="os-page__card">
      <div class="os-toolbar">
        <div class="os-toolbar__row">
          <input
            v-model="keyword"
            class="os-toolbar__search"
            type="search"
            placeholder="搜索 IP、属地、页面标题或 UA"
            @input="onKeyword"
          />
          <button class="os-toolbar__refresh" type="button" @click="load()">刷新</button>
        </div>
        <div class="os-toolbar__row">
          <div class="os-toolbar__segment">
            <button
              v-for="option in [
                { value: 'human', label: `真人 ${stats.humans ?? 0}` },
                { value: 'bot', label: `机器人 ${stats.bots ?? 0}` },
                { value: 'all', label: '全部' },
              ]"
              :key="option.value"
              type="button"
              :class="{ 'is-active': visitorType === option.value }"
              @click="setVisitorType(option.value as 'human' | 'bot' | 'all')"
            >
              {{ option.label }}
            </button>
          </div>
          <div class="os-toolbar__segment">
            <button
              v-for="option in [
                { value: 'all', label: '全部' },
                { value: 'online', label: '在线' },
                { value: 'offline', label: '离线' },
              ]"
              :key="option.value"
              type="button"
              :class="{ 'is-active': status === option.value }"
              @click="setStatus(option.value as 'all' | 'online' | 'offline')"
            >
              {{ option.label }}
            </button>
          </div>
          <div class="os-toolbar__sort">
            <span class="os-toolbar__label">排序</span>
            <div class="os-toolbar__segment">
              <button
                v-for="option in [
                  { value: 'lastSeenAt', label: '最近活跃' },
                  { value: 'firstSeenAt', label: '首次访问' },
                  { value: 'visits', label: '访问次数' },
                ]"
                :key="option.value"
                type="button"
                :class="{ 'is-active': sort === option.value }"
                @click="setSort(option.value as 'lastSeenAt' | 'firstSeenAt' | 'visits')"
              >
                {{ option.label }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="loading && !items.length" class="os-page__loading">加载中…</div>
      <div v-else-if="!items.length" class="os-page__empty">
        <template v-if="stats.online > 0">
          当前有 {{ stats.online }} 位访客在线，但没有符合当前筛选条件的记录。
          <button
            v-if="visitorType === 'human' && (stats.bots ?? 0) > 0"
            class="os-empty__btn"
            type="button"
            @click="setVisitorType('bot')"
          >
            查看机器人（{{ stats.bots }}）
          </button>
        </template>
        <template v-else>暂无访客记录</template>
      </div>
      <table v-else class="os-table">
        <thead>
          <tr>
            <th>访客</th>
            <th>设备</th>
            <th>当前页面</th>
            <th class="os-table__right">访问次数</th>
            <th>最后活跃</th>
            <th class="os-table__right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.name">
            <td>
              <div class="os-visitor">
                <span class="os-dot" :class="{ 'os-dot--online': item.online }" />
                <div>
                  <div class="os-visitor__ip">{{ item.ip || '已隐藏' }}</div>
                  <div class="os-visitor__loc">
                    <span v-if="item.bot" class="os-badge os-badge--bot">机器人</span>
                    <template v-if="item.location">{{ item.location }}</template>
                    <button
                      v-else
                      class="os-loc-btn"
                      type="button"
                      :disabled="rowBusy[item.name]"
                      @click="lookupRow(item)"
                    >
                      {{ rowBusy[item.name] ? '查询中…' : '查询属地' }}
                    </button>
                    <span v-if="rowError[item.name]" class="os-loc-error">
                      {{ rowError[item.name] }}
                    </span>
                    <span v-if="item.idc" class="os-badge os-badge--warn">{{ item.idc }}</span>
                  </div>
                </div>
              </div>
            </td>
            <td>
              <div class="os-device">
                <span class="os-device__icon">{{ deviceIcon(item.ua) }}</span>
                <span>{{ deviceSummary(item) }}</span>
              </div>
            </td>
            <td>
              <div class="os-page-title">{{ item.pageTitle || '未知页面' }}</div>
              <div class="os-page-url">{{ item.pageUrl || '—' }}</div>
            </td>
            <td class="os-table__right">{{ item.visitCount || 0 }}</td>
            <td>
              <div>{{ fmtRelative(item.lastSeenAt) }}</div>
              <div class="os-muted">{{ fmtTime(item.lastSeenAt) }}</div>
            </td>
            <td class="os-table__right">
              <button class="os-more" type="button" @click="openDetail(item)">
                更多<span class="os-more__arrow">›</span>
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="total > 0" class="os-pager">
        <span class="os-pager__info">共 {{ total }} 条 · 第 {{ page }}/{{ totalPages }} 页</span>
        <div class="os-pager__actions">
          <button type="button" :disabled="!canPrev" @click="goPage(-1)">上一页</button>
          <button type="button" :disabled="!canNext" @click="goPage(1)">下一页</button>
        </div>
      </div>
    </div>

    <div v-if="detail" class="os-drawer-mask" @click.self="closeDetail">
      <aside class="os-drawer">
        <header class="os-drawer__head">
          <div>
            <h3 class="os-drawer__title">
              访客详情
              <span class="os-badge" :class="detail.online ? 'os-badge--online' : ''">
                {{ detail.online ? '在线' : '离线' }}
              </span>
            </h3>
            <p class="os-drawer__sub">
              {{ detail.ip || '已隐藏' }} · {{ detail.location || '未查询属地' }}
            </p>
          </div>
          <button class="os-drawer__close" type="button" @click="closeDetail">×</button>
        </header>

        <div class="os-drawer__body">
          <section class="os-section">
            <h4>
              IP 属地
              <button
                v-if="detail.geoResolvedAt"
                class="os-copy"
                type="button"
                :disabled="geoLoading"
                @click="lookupLocation(true)"
              >
                {{ geoLoading ? '查询中…' : '重新查询' }}
              </button>
            </h4>

            <div v-if="geoError" class="os-geo-error">
              {{ geoError }}
              <div v-if="geoEndpoint" class="os-geo-endpoint">接口：{{ geoEndpoint }}</div>
            </div>

            <dl v-if="detail.geoResolvedAt" class="os-kv">
              <dt>整体属地</dt>
              <dd>{{ detail.location || '—' }}</dd>
              <dt>国家 / 地区</dt>
              <dd>
                {{ detail.country || '—' }}
                <span v-if="detail.countryCode" class="os-muted">({{ detail.countryCode }})</span>
              </dd>
              <dt>省份</dt>
              <dd>{{ detail.region || '—' }}</dd>
              <dt>城市</dt>
              <dd>{{ detail.city || '—' }}</dd>
              <dt>区县</dt>
              <dd>{{ detail.districts || '—' }}</dd>
              <dt>运营商</dt>
              <dd>{{ detail.isp || '—' }}</dd>
              <dt>网络类型</dt>
              <dd>{{ detail.net || '—' }}</dd>
              <dt>IDC / 机房</dt>
              <dd>{{ detail.idc || '—' }}</dd>
              <dt>邮编</dt>
              <dd>{{ detail.zipcode || '—' }}</dd>
              <dt>区号</dt>
              <dd>{{ detail.areacode || '—' }}</dd>
              <dt>IP 协议</dt>
              <dd>{{ detail.protocol || '—' }}</dd>
              <dt>十进制 IP</dt>
              <dd>{{ detail.dec || '—' }}</dd>
              <dt>查询时间</dt>
              <dd>{{ fmtTime(detail.geoResolvedAt) }}</dd>
            </dl>

            <div v-else class="os-geo-empty">
              <p>该访客还没有查询过 IP 属地。</p>
              <button class="os-geo-btn" type="button" :disabled="geoLoading" @click="lookupLocation()">
                {{ geoLoading ? '查询中…' : '查询属地' }}
              </button>
              <p class="os-geo-hint">插件不会自动查询，点击后才会请求一次接口并保存结果。</p>
            </div>
          </section>

          <section class="os-section">
            <h4>IP 与网络</h4>
            <dl class="os-kv">
              <dt>IP 地址</dt>
              <dd>
                {{ detail.ip || '已隐藏' }}
                <button v-if="detail.ip" class="os-copy" type="button" @click="copy(detail.ip, 'ip')">
                  {{ copied === 'ip' ? '已复制' : '复制' }}
                </button>
              </dd>
            </dl>
          </section>

          <section class="os-section">
            <h4>设备信息</h4>
            <dl class="os-kv">
              <dt>访客类型</dt>
              <dd>
                <span class="os-badge" :class="detail.bot ? 'os-badge--bot' : 'os-badge--online'">
                  {{ detail.bot ? '机器人' : '真人' }}
                </span>
                <span v-if="detail.botReason" class="os-muted">{{ detail.botReason }}</span>
              </dd>
              <dt>设备类型</dt>
              <dd>{{ detail.ua.deviceType || '—' }}</dd>
              <dt>操作系统</dt>
              <dd>
                {{ detail.ua.os || '—' }}{{ detail.ua.osVersion ? ' ' + detail.ua.osVersion : '' }}
              </dd>
              <dt>浏览器</dt>
              <dd>
                {{ detail.ua.browser || '—'
                }}{{ detail.ua.browserVersion ? ' ' + detail.ua.browserVersion : '' }}
              </dd>
              <dt>内核</dt>
              <dd>{{ detail.ua.engine || '—' }}</dd>
              <dt>内置应用</dt>
              <dd>{{ detail.ua.app || '—' }}</dd>
              <dt>平台</dt>
              <dd>{{ detail.platform || '—' }}</dd>
              <dt>屏幕分辨率</dt>
              <dd>{{ detail.screen || '—' }}</dd>
              <dt>窗口尺寸</dt>
              <dd>{{ detail.viewport || '—' }}</dd>
              <dt>语言</dt>
              <dd>{{ detail.language || '—' }}</dd>
              <dt>时区</dt>
              <dd>{{ detail.timezone || '—' }}</dd>
              <dt>CPU 核心</dt>
              <dd>{{ detail.cpuCores || '—' }}</dd>
              <dt>设备内存</dt>
              <dd>{{ detail.deviceMemory || '—' }}</dd>
              <dt>网络类型</dt>
              <dd>{{ detail.connection || '—' }}</dd>
            </dl>
          </section>

          <section class="os-section">
            <h4>访问行为</h4>
            <dl class="os-kv">
              <dt>首次访问</dt>
              <dd>{{ fmtTime(detail.firstSeenAt) }}</dd>
              <dt>最后活跃</dt>
              <dd>{{ fmtTime(detail.lastSeenAt) }}</dd>
              <dt>累计在线</dt>
              <dd>{{ fmtDuration(detail.totalOnlineMs) }}</dd>
              <dt>访问次数</dt>
              <dd>{{ detail.visitCount || 0 }} 次</dd>
              <dt>浏览页面数</dt>
              <dd>{{ detail.pageViewCount || 0 }} 个</dd>
              <dt>入口页面</dt>
              <dd>{{ detail.firstPageTitle || '—' }}</dd>
              <dt>入口地址</dt>
              <dd class="os-break">{{ detail.firstPageUrl || '—' }}</dd>
              <dt>当前页面</dt>
              <dd>{{ detail.pageTitle || '—' }}</dd>
              <dt>当前地址</dt>
              <dd class="os-break">{{ detail.pageUrl || '—' }}</dd>
              <dt>来源页面</dt>
              <dd class="os-break">{{ detail.referer || '直接访问' }}</dd>
              <dt>首次来源</dt>
              <dd class="os-break">{{ detail.firstReferer || '直接访问' }}</dd>
            </dl>
          </section>

          <section class="os-section">
            <h4>
              原始 User-Agent
              <button class="os-copy" type="button" @click="copy(detail.userAgent, 'ua')">
                {{ copied === 'ua' ? '已复制' : '复制' }}
              </button>
            </h4>
            <code class="os-ua">{{ detail.userAgent || '—' }}</code>
          </section>
        </div>
      </aside>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.os-page {
  padding: 1rem 1.5rem 2rem;

  &__header {
    margin-bottom: 1rem;
  }

  &__title {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 600;
    color: #111827;
  }

  &__desc {
    max-width: 60rem;
    margin: 0.25rem 0 0;
    font-size: 0.8125rem;
    line-height: 1.6;
    color: #6b7280;

    code {
      padding: 0.0625rem 0.25rem;
      background: #f3f4f6;
      border-radius: 0.25rem;
    }
  }

  &__stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
    gap: 0.75rem;
    margin-bottom: 1rem;
  }

  &__card {
    padding: 0.75rem 1rem 1rem;
    background: #fff;
    border-radius: 0.5rem;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);
  }

  &__loading,
  &__empty {
    padding: 2.5rem 0;
    font-size: 0.875rem;
    color: #9ca3af;
    text-align: center;
  }
}

.os-stat {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0.875rem 1rem;
  background: #fff;
  border-radius: 0.5rem;
  box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);

  &__label {
    font-size: 0.75rem;
    color: #6b7280;
  }

  &__value {
    font-size: 1.5rem;
    font-weight: 700;
    line-height: 1.2;
    color: #111827;

    &--online {
      color: #1B9C93;
    }

    &--small {
      font-size: 0.9375rem;
      font-weight: 600;
      color: #4b5563;
    }
  }

  &__hint {
    font-size: 0.6875rem;
    color: #9ca3af;
  }
}

.os-alert {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem 0.5rem;
  align-items: baseline;
  padding: 0.625rem 0.875rem;
  margin-bottom: 0.75rem;
  font-size: 0.8125rem;
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 0.5rem;

  &__hint {
    font-size: 0.75rem;
    color: #9ca3af;
  }

  &--info {
    color: #4b5563;
    background: #f9fafb;
    border-color: #e5e7eb;
  }
}

.os-toolbar {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid #f3f4f6;

  &__row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    align-items: center;
  }

  &__sort {
    display: flex;
    gap: 0.375rem;
    align-items: center;
    margin-left: auto;
  }

  &__label {
    font-size: 0.75rem;
    color: #9ca3af;
  }

  &__search {
    flex: 1 1 16rem;
    min-width: 12rem;
    padding: 0.375rem 0.625rem;
    font-size: 0.8125rem;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;
    outline: none;

    &:focus {
      border-color: #8ADDD6;
    }
  }

  &__segment {
    display: flex;
    overflow: hidden;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;

    button {
      padding: 0.375rem 0.75rem;
      font-size: 0.8125rem;
      color: #4b5563;
      cursor: pointer;
      background: #fff;
      border: none;
      border-right: 1px solid #e5e7eb;

      &:last-child {
        border-right: none;
      }

      &.is-active {
        color: #fff;
        background: #39C5BB;
      }
    }
  }

  &__select {
    padding: 0.375rem 0.5rem;
    font-size: 0.8125rem;
    color: #4b5563;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;
    outline: none;
  }

  &__refresh {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
    color: #39C5BB;
    cursor: pointer;
    background: #E8F9F7;
    border: 1px solid #B9E7E3;
    border-radius: 0.375rem;

    &:hover {
      background: #B9E7E3;
    }
  }
}

.os-table {
  width: 100%;
  margin-top: 0.5rem;
  border-collapse: collapse;
  font-size: 0.8125rem;

  th,
  td {
    padding: 0.625rem 0.75rem;
    text-align: left;
    vertical-align: top;
    border-bottom: 1px solid #f3f4f6;
  }

  th {
    font-weight: 600;
    color: #4b5563;
    background: #f9fafb;
  }

  tr:last-child td {
    border-bottom: none;
  }

  &__right {
    text-align: right;
  }
}

.os-visitor {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;

  &__ip {
    font-weight: 600;
    color: #111827;
    font-variant-numeric: tabular-nums;
  }

  &__loc {
    margin-top: 0.125rem;
    font-size: 0.75rem;
    color: #6b7280;
  }
}

.os-dot {
  flex: none;
  width: 0.5rem;
  height: 0.5rem;
  margin-top: 0.375rem;
  background: #d1d5db;
  border-radius: 9999px;

  &--online {
    background: #39C5BB;
    box-shadow: 0 0 0 3px rgb(57 197 187 / 0.18);
  }
}

.os-device {
  display: flex;
  gap: 0.375rem;
  align-items: center;
  color: #374151;

  &__icon {
    font-size: 0.875rem;
  }
}

.os-page-title {
  font-weight: 500;
  color: #111827;
}

.os-page-url {
  max-width: 22rem;
  margin-top: 0.125rem;
  overflow: hidden;
  color: #9ca3af;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.os-muted {
  margin-top: 0.125rem;
  font-size: 0.6875rem;
  color: #9ca3af;
}

.os-more {
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  color: #39C5BB;
  cursor: pointer;
  background: #E8F9F7;
  border: 1px solid #B9E7E3;
  border-radius: 0.375rem;

  &:hover {
    background: #B9E7E3;
  }

  &__arrow {
    margin-left: 0.125rem;
  }
}

.os-badge {
  display: inline-block;
  padding: 0 0.375rem;
  margin-left: 0.25rem;
  font-size: 0.6875rem;
  line-height: 1.4;
  color: #4b5563;
  background: #f3f4f6;
  border-radius: 0.25rem;

  &--online {
    color: #167A72;
    background: #D8F4F1;
  }

  &--warn {
    color: #b45309;
    background: #fef3c7;
  }

  &--bot {
    color: #6b7280;
    background: #e5e7eb;
  }
}

.os-loc-btn {
  padding: 0 0.375rem;
  font-size: 0.6875rem;
  line-height: 1.5;
  color: #1B9C93;
  cursor: pointer;
  background: #E8F9F7;
  border: 1px solid #B9E7E3;
  border-radius: 0.25rem;
  transition: background 0.15s ease;

  &:hover {
    background: #D5F3F0;
  }

  &:disabled {
    color: #9ca3af;
    cursor: wait;
    background: #f3f4f6;
    border-color: #e5e7eb;
  }
}

.os-loc-error {
  font-size: 0.6875rem;
  color: #b91c1c;
}

.os-empty__btn {
  margin-left: 0.5rem;
  padding: 0.125rem 0.5rem;
  font-size: 0.75rem;
  color: #1B9C93;
  cursor: pointer;
  background: #E8F9F7;
  border: 1px solid #B9E7E3;
  border-radius: 0.25rem;
}

.os-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 0.75rem;
  margin-top: 0.5rem;
  border-top: 1px solid #f3f4f6;

  &__info {
    font-size: 0.75rem;
    color: #6b7280;
  }

  &__actions {
    display: flex;
    gap: 0.5rem;

    button {
      padding: 0.25rem 0.625rem;
      font-size: 0.75rem;
      color: #4b5563;
      cursor: pointer;
      background: #fff;
      border: 1px solid #e5e7eb;
      border-radius: 0.375rem;

      &:disabled {
        color: #d1d5db;
        cursor: not-allowed;
      }
    }
  }
}

.os-drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgb(17 24 39 / 0.35);
}

.os-drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  width: min(30rem, 92vw);
  background: #fff;
  box-shadow: -8px 0 30px rgb(0 0 0 / 0.12);

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 1rem 1.25rem;
    border-bottom: 1px solid #f3f4f6;
  }

  &__title {
    display: flex;
    gap: 0.375rem;
    align-items: center;
    margin: 0;
    font-size: 1rem;
    font-weight: 600;
    color: #111827;
  }

  &__sub {
    margin: 0.25rem 0 0;
    font-size: 0.75rem;
    color: #6b7280;
  }

  &__close {
    padding: 0 0.25rem;
    font-size: 1.25rem;
    line-height: 1;
    color: #9ca3af;
    cursor: pointer;
    background: transparent;
    border: none;

    &:hover {
      color: #4b5563;
    }
  }

  &__body {
    flex: 1;
    padding: 0.75rem 1.25rem 2rem;
    overflow-y: auto;
  }
}

.os-section {
  padding: 0.75rem 0;
  border-bottom: 1px solid #f3f4f6;

  &:last-child {
    border-bottom: none;
  }

  h4 {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    margin: 0 0 0.5rem;
    font-size: 0.8125rem;
    font-weight: 600;
    color: #111827;
  }
}

.os-kv {
  display: grid;
  grid-template-columns: 6rem 1fr;
  gap: 0.375rem 0.75rem;
  margin: 0;
  font-size: 0.75rem;

  dt {
    color: #6b7280;
  }

  dd {
    margin: 0;
    color: #111827;
    overflow-wrap: anywhere;
  }
}

.os-break {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.6875rem;
  color: #4b5563;
}

.os-ua {
  display: block;
  padding: 0.5rem 0.625rem;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.6875rem;
  line-height: 1.6;
  color: #374151;
  word-break: break-all;
  background: #f9fafb;
  border-radius: 0.375rem;
}

.os-copy {
  padding: 0 0.375rem;
  font-size: 0.6875rem;
  color: #39C5BB;
  cursor: pointer;
  background: #E8F9F7;
  border: 1px solid #B9E7E3;
  border-radius: 0.25rem;

  &:disabled {
    color: #8ADDD6;
    cursor: wait;
  }
}

.os-geo-empty {
  padding: 0.75rem;
  font-size: 0.75rem;
  color: #6b7280;
  text-align: center;
  background: #f9fafb;
  border: 1px dashed #e5e7eb;
  border-radius: 0.5rem;

  p {
    margin: 0 0 0.5rem;
  }
}

.os-geo-btn {
  padding: 0.375rem 0.875rem;
  font-size: 0.8125rem;
  color: #fff;
  cursor: pointer;
  background: #39C5BB;
  border: none;
  border-radius: 0.375rem;

  &:disabled {
    background: #8ADDD6;
    cursor: wait;
  }
}

.os-geo-hint {
  margin: 0.5rem 0 0;
  font-size: 0.6875rem;
  color: #9ca3af;
}

.os-geo-error {
  padding: 0.5rem 0.625rem;
  margin-bottom: 0.5rem;
  font-size: 0.75rem;
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 0.375rem;
}

.os-geo-endpoint {
  margin-top: 0.25rem;
  font-size: 0.6875rem;
  color: #9ca3af;
  word-break: break-all;
}
</style>
