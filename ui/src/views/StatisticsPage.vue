<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import axios from 'axios'
import StatsChart, { type Point } from '../components/StatsChart.vue'
import VisitorDetailDrawer from '../components/VisitorDetailDrawer.vue'
import type { RecordView } from '../utils/visitor'

interface Overview {
  views: number
  visitors: number
  uniqueVisitors: number
  officialViews: number
  officialViewsRaw: number
  officialViewsAdjustment: number
  adjustmentApplied: number
  adjustmentError: string | null
  todayViews: number
  todayVisitors: number
  online: number
  articles: number
  trackedArticles: number
  loaded: boolean
  posts: number
  unresolvedHeartbeats: number
  lastHeartbeatUrl: string | null
}

interface SourceItem {
  name: string
  views: number
  visitors: number
}

interface ArticleRow {
  postName: string
  title: string | null
  slug: string | null
  permalink: string | null
  views: number
  officialViews: number
  visitors: number
  todayViews: number
  todayVisitors: number
  totalOnlineMs: number
  avgOnlineMs: number
  lastSeenAt: number | null
  tracked: boolean
}

const api = '/apis/online-stats.halo.run/v1alpha1'

const overview = ref<Overview | null>(null)
const series = ref<Point[]>([])
const articles = ref<ArticleRow[]>([])
const browsers = ref<SourceItem[]>([])
const systems = ref<SourceItem[]>([])
const referers = ref<SourceItem[]>([])
const trendDays = ref(90)
const articleSort = ref<'views' | 'visitors' | 'today' | 'duration'>('views')
const loading = ref(true)

const drill = ref<{
  open: boolean
  title: string
  subtitle: string
  loading: boolean
  kind: 'article' | 'source' | 'visitor' | ''
  rows: {
    label: string
    value: string
    extra?: string
    ip?: string | null
    visitorKey?: string | null
  }[]
}>({ open: false, title: '', subtitle: '', loading: false, kind: '', rows: [] })

const visitorDetail = ref<RecordView | null>(null)
const visitorLoading = ref('')

/**
 * Opens the same visitor drawer the visitor list uses, from a bare visitor key, so a
 * row of any list here can show everything known about that address.
 */
async function openVisitor(visitorKey: string | null | undefined) {
  if (!visitorKey || visitorLoading.value) {
    return
  }
  visitorLoading.value = visitorKey
  try {
    const { data } = await axios.get<RecordView>(`${api}/records/${visitorKey}`)
    visitorDetail.value = data
  } catch (e) {
    /* the record may have been cleaned up already */
  } finally {
    visitorLoading.value = ''
  }
}

const geoBusy = ref('')
const geoCache = ref<Record<string, string>>({})
let timer: number | undefined

async function lookupGeo(ip: string | null | undefined) {
  if (!ip || geoBusy.value) {
    return
  }
  geoBusy.value = ip
  try {
    const { data } = await axios.get(`${api}/location`, { params: { ip } })
    geoCache.value[ip] = data.resolved ? data.location || '未知' : data.error || '查询失败'
  } catch (e) {
    geoCache.value[ip] = '查询失败'
  } finally {
    geoBusy.value = ''
  }
}

async function loadAll() {
  loading.value = true
  try {
    const [ov, tr, ar, br, sy, rf] = await Promise.all([
      axios.get(`${api}/statistics/overview`),
      axios.get(`${api}/statistics/trend`, { params: { days: trendDays.value } }),
      axios.get(`${api}/statistics/articles`, { params: { sort: articleSort.value, limit: 50 } }),
      axios.get(`${api}/statistics/sources`, { params: { dimension: 'browser', days: 30 } }),
      axios.get(`${api}/statistics/sources`, { params: { dimension: 'system', days: 30 } }),
      axios.get(`${api}/statistics/sources`, { params: { dimension: 'referer', days: 30 } }),
    ])
    overview.value = ov.data
    series.value = tr.data.series || []
    articles.value = ar.data.items || []
    browsers.value = br.data.items || []
    systems.value = sy.data.items || []
    referers.value = rf.data.items || []
  } catch (e) {
    /* keep the previous data, the next poll retries */
  } finally {
    loading.value = false
  }
}

function fmtDuration(ms: number): string {
  if (!ms || ms <= 0) {
    return '0 秒'
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

function fmtTime(ms: number | null): string {
  if (!ms) {
    return '—'
  }
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`
}

async function openArticle(postName: string, title: string | null) {
  drill.value = {
    open: true,
    title: title || '文章详情',
    subtitle: '正在加载…',
    loading: true,
    kind: 'article',
    rows: [],
  }
  try {
    const { data } = await axios.get(`${api}/statistics/articles/${postName}`)
    const rows = (data.visitors || []).map(
      (v: { visitorKey: string; views: number; durationMs: number; lastSeenAt: number | null; ip: string | null }) => ({
        label: v.ip || v.visitorKey,
        value: `看了 ${v.views} 次`,
        extra: `停留 ${fmtDuration(v.durationMs)} · 最后 ${fmtTime(v.lastSeenAt)}`,
        ip: v.ip,
        visitorKey: v.visitorKey,
      }),
    )
    const a = data.article
    drill.value = {
      open: true,
      title: title || '文章详情',
      subtitle: a.tracked
        ? `Halo 官方 ${a.officialViews} 次浏览 · 插件统计 ${a.visitors} 位访客 · 平均停留 ${fmtDuration(
            a.avgOnlineMs,
          )}`
        : `Halo 官方 ${a.officialViews} 次浏览 · 插件没有记录到这篇文章的访问明细`,
      loading: false,
      kind: 'article',
      rows,
    }
  } catch (e) {
    drill.value.subtitle = '加载失败'
    drill.value.loading = false
  }
}

async function openSource(dimension: string, value: string) {
  drill.value = {
    open: true,
    title: value,
    subtitle: '正在加载…',
    loading: true,
    kind: 'source',
    rows: [],
  }
  try {
    const { data } = await axios.get(`${api}/statistics/sources/detail`, {
      params: { dimension, value, days: 30 },
    })
    const rows = (data.items || []).map(
      (v: { visitorKey: string; views: number; ip: string | null }) => ({
        label: v.ip || v.visitorKey,
        value: `浏览 ${v.views} 次`,
        extra: v.ip ? undefined : v.visitorKey,
        ip: v.ip,
        visitorKey: v.visitorKey,
      }),
    )
    drill.value = {
      open: true,
      title: value,
      subtitle: `近 30 天共 ${data.views} 次浏览 · ${data.visitors} 位访客`,
      loading: false,
      kind: 'source',
      rows,
    }
  } catch (e) {
    drill.value.subtitle = '加载失败'
    drill.value.loading = false
  }
}

function closeDrill() {
  drill.value.open = false
}

const topArticles = computed(() => articles.value.slice(0, 10))

/** Explains an empty dashboard instead of leaving the user guessing. */
const emptyHint = computed(() => {
  const ov = overview.value
  if (!ov || ov.views > 0) {
    return ''
  }
  if (!ov.loaded) {
    return '插件正在加载数据，稍等几秒。'
  }
  if (ov.posts === 0) {
    return '没有读取到已发布的文章，插件无法把访问归到具体文章上。请确认博客里至少有已发布的文章，然后刷新。'
  }
  if ((ov.unresolvedHeartbeats ?? 0) > 0 && ov.articles === 0) {
    return `已收到 ${ov.unresolvedHeartbeats} 次心跳，但都没有匹配到文章。最近一次上报的地址是 ${
      ov.lastHeartbeatUrl || '未知'
    }。统计只记录文章详情页，首页和归档页不计入；如果这是文章页，说明文章链接与插件记录的对不上。`
  }
  return '还没有文章页的访问记录。打开任意一篇文章停留十几秒，再回来看这里。'
})
const maxSourceViews = computed(() =>
  Math.max(1, ...browsers.value.map((s) => s.views), ...systems.value.map((s) => s.views)),
)

function barWidth(views: number): string {
  return `${Math.round((views / maxSourceViews.value) * 100)}%`
}

async function setTrendDays(days: number) {
  trendDays.value = days
  const { data } = await axios.get(`${api}/statistics/trend`, { params: { days } })
  series.value = data.series || []
}

async function setArticleSort(sort: 'views' | 'visitors' | 'today' | 'duration') {
  articleSort.value = sort
  const { data } = await axios.get(`${api}/statistics/articles`, {
    params: { sort, limit: 50 },
  })
  articles.value = data.items || []
}

onMounted(() => {
  loadAll()
  timer = window.setInterval(loadAll, 10_000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})
</script>

<template>
  <div class="os-stat-page">
    <header class="os-stat-page__head">
      <div>
        <h2>访问统计</h2>
        <p>数据来自插件自己的访问记录，浏览量同时对照 Halo 官方计数。统计只记录文章详情页。</p>
      </div>
      <button class="os-btn os-btn--ghost" type="button" @click="loadAll">刷新</button>
    </header>

    <div v-if="emptyHint" class="os-notice">{{ emptyHint }}</div>
    <div v-if="overview?.adjustmentError" class="os-notice os-notice--error">
      修正值写入 Halo 官方计数失败：{{ overview.adjustmentError }}
    </div>
    <div
      v-else-if="(overview?.officialViewsAdjustment ?? 0) > 0 && !(overview?.adjustmentApplied ?? 0)"
      class="os-notice"
    >
      修正值 {{ overview?.officialViewsAdjustment }} 正在写入 Halo 官方计数，稍等几秒刷新即可。
    </div>

    <div v-if="overview" class="os-cards">
      <div class="os-card os-card--hero">
        <span class="os-card__label">当前在线</span>
        <span class="os-card__value">{{ overview.online }}</span>
        <span class="os-card__hint">人正在浏览</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">总浏览量</span>
        <span class="os-card__value">{{ overview.officialViews }}</span>
        <span class="os-card__hint">
          <template v-if="(overview.adjustmentApplied ?? 0) > 0">
            Halo 官方数据 · 已写入修正 {{ overview.adjustmentApplied }}
          </template>
          <template v-else>Halo 官方数据</template>
        </span>
      </div>
      <div class="os-card">
        <span class="os-card__label">总访客</span>
        <span class="os-card__value">{{ overview.uniqueVisitors }}</span>
        <span class="os-card__hint">插件统计的去重访客</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">今日浏览</span>
        <span class="os-card__value">{{ overview.todayViews }}</span>
        <span class="os-card__hint">今日访客 {{ overview.todayVisitors }}</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">文章数</span>
        <span class="os-card__value">{{ overview.articles }}</span>
        <span class="os-card__hint">官方有浏览记录的文章</span>
      </div>
    </div>

    <section class="os-panel">
      <div class="os-panel__head">
        <h3>访问趋势</h3>
        <div class="os-seg">
          <button
            v-for="option in [
              { value: 90, label: '近 90 天' },
              { value: 30, label: '近 30 天' },
              { value: 7, label: '近 7 天' },
            ]"
            :key="option.value"
            type="button"
            :class="{ 'is-active': trendDays === option.value }"
            @click="setTrendDays(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
      </div>
      <StatsChart :series="series" metric="views" />
    </section>

    <div class="os-grid">
      <section class="os-panel">
        <h3>浏览器来源</h3>
        <ul class="os-bars">
          <li v-for="item in browsers" :key="item.name" @click="openSource('browser', item.name)">
            <span class="os-bars__name">{{ item.name }}</span>
            <span class="os-bars__bar"><i :style="{ width: barWidth(item.views) }" /></span>
            <span class="os-bars__value">{{ item.views }}</span>
          </li>
          <li v-if="!browsers.length" class="os-bars__empty">暂无数据</li>
        </ul>
      </section>

      <section class="os-panel">
        <h3>操作系统来源</h3>
        <ul class="os-bars">
          <li v-for="item in systems" :key="item.name" @click="openSource('system', item.name)">
            <span class="os-bars__name">{{ item.name }}</span>
            <span class="os-bars__bar"><i :style="{ width: barWidth(item.views) }" /></span>
            <span class="os-bars__value">{{ item.views }}</span>
          </li>
          <li v-if="!systems.length" class="os-bars__empty">暂无数据</li>
        </ul>
      </section>

      <section class="os-panel">
        <h3>来源站点</h3>
        <ul class="os-bars">
          <li v-for="item in referers" :key="item.name" @click="openSource('referer', item.name)">
            <span class="os-bars__name">{{ item.name }}</span>
            <span class="os-bars__bar"><i :style="{ width: barWidth(item.views) }" /></span>
            <span class="os-bars__value">{{ item.views }}</span>
          </li>
          <li v-if="!referers.length" class="os-bars__empty">暂无数据</li>
        </ul>
      </section>
    </div>

    <section class="os-panel">
      <div class="os-panel__head">
        <h3>文章排行</h3>
        <div class="os-seg">
          <button
            v-for="option in [
              { value: 'views', label: '总浏览量' },
              { value: 'visitors', label: '访客数' },
              { value: 'today', label: '今日浏览' },
              { value: 'duration', label: '停留时长' },
            ]"
            :key="option.value"
            type="button"
            :class="{ 'is-active': articleSort === option.value }"
            @click="setArticleSort(option.value as 'views' | 'visitors' | 'today' | 'duration')"
          >
            {{ option.label }}
          </button>
        </div>
      </div>

      <div class="os-table-wrap">
        <table class="os-table">
          <thead>
            <tr>
              <th class="os-col-title">文章</th>
              <th class="os-right">浏览量</th>
              <th class="os-right">访客数</th>
              <th class="os-right">今日浏览</th>
              <th class="os-right">平均停留</th>
              <th class="os-right">明细</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in articles" :key="item.postName">
              <td class="os-col-title">
                <div class="os-article__title">{{ item.title || item.slug || item.postName }}</div>
                <div class="os-article__slug">{{ item.permalink || item.slug }}</div>
              </td>
              <td class="os-right os-strong">{{ item.officialViews }}</td>
              <td class="os-right">{{ item.tracked ? item.visitors : '—' }}</td>
              <td class="os-right">{{ item.tracked ? item.todayViews : '—' }}</td>
              <td class="os-right">{{ item.tracked ? fmtDuration(item.avgOnlineMs) : '—' }}</td>
              <td class="os-right">
                <button class="os-btn os-btn--tiny" type="button" @click="openArticle(item.postName, item.title)">
                  详情
                </button>
              </td>
            </tr>
            <tr v-if="!articles.length">
              <td colspan="6" class="os-empty">暂无文章访问记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="drill.open" class="os-drawer-mask" @click.self="closeDrill">
      <aside class="os-drawer">
        <header class="os-drawer__head">
          <div>
            <h3>{{ drill.title }}</h3>
            <p>{{ drill.subtitle }}</p>
          </div>
          <button class="os-drawer__close" type="button" @click="closeDrill">×</button>
        </header>
        <div class="os-drawer__body">
          <div v-if="drill.loading" class="os-empty">加载中…</div>
          <ul v-else-if="drill.rows.length" class="os-detail">
            <li v-for="(row, index) in drill.rows" :key="index">
              <span class="os-detail__label">
                {{ row.label }}
                <button
                  v-if="row.visitorKey"
                  class="os-more"
                  type="button"
                  :disabled="visitorLoading === row.visitorKey"
                  @click="openVisitor(row.visitorKey)"
                >
                  {{ visitorLoading === row.visitorKey ? '加载中' : '更多'
                  }}<span class="os-more__arrow">›</span>
                </button>
              </span>
              <span class="os-detail__value">{{ row.value }}</span>
              <span v-if="row.extra" class="os-detail__extra">{{ row.extra }}</span>
              <span v-if="row.ip" class="os-detail__geo">
                <button
                  v-if="!geoCache[row.ip]"
                  class="os-loc-btn"
                  type="button"
                  :disabled="geoBusy === row.ip"
                  @click="lookupGeo(row.ip)"
                >
                  {{ geoBusy === row.ip ? '查询中…' : '查属地' }}
                </button>
                <span v-else class="os-detail__loc">{{ geoCache[row.ip] }}</span>
              </span>
            </li>
          </ul>
          <div v-else class="os-empty">没有更细的数据</div>
        </div>
      </aside>
    </div>

    <VisitorDetailDrawer
      v-if="visitorDetail"
      :record="visitorDetail"
      @close="visitorDetail = null"
    />
  </div>
</template>

<style lang="scss" scoped>
.os-stat-page {
  padding: 1rem 1.5rem 2.5rem;

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 1rem;

    h2 {
      margin: 0;
      font-size: 1.25rem;
      font-weight: 600;
      color: #111827;
    }

    p {
      margin: 0.25rem 0 0;
      font-size: 0.8125rem;
      color: #6b7280;
    }
  }
}

.os-notice {
  padding: 0.625rem 0.875rem;
  margin-bottom: 1rem;
  font-size: 0.8125rem;
  line-height: 1.6;
  color: #92400e;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 0.5rem;

  &--error {
    color: #b91c1c;
    background: #fef2f2;
    border-color: #fecaca;
  }
}

.os-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9.5rem, 1fr));
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.os-card {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0.875rem 1rem;
  background: #fff;
  border-radius: 0.5rem;
  box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);

  &--hero {
    background: linear-gradient(135deg, #39c5bb, #2aa79f);

    .os-card__label,
    .os-card__hint {
      color: rgb(255 255 255 / 0.85);
    }

    .os-card__value {
      color: #fff;
    }
  }

  &__label {
    font-size: 0.75rem;
    color: #6b7280;
  }

  &__value {
    font-size: 1.625rem;
    font-weight: 700;
    line-height: 1.2;
    color: #111827;

    &--sm {
      font-size: 1.125rem;
    }
  }

  &__hint {
    font-size: 0.6875rem;
    color: #9ca3af;
  }
}

.os-panel {
  padding: 1rem 1.125rem 1.25rem;
  margin-bottom: 1rem;
  background: #fff;
  border-radius: 0.5rem;
  box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 0.75rem;
  }

  h3 {
    margin: 0 0 0.75rem;
    font-size: 0.9375rem;
    font-weight: 600;
    color: #111827;
  }

  &__head h3 {
    margin: 0;
  }
}

.os-mini {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr));
  gap: 1rem;
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid #f1f5f9;

  h4 {
    margin: 0 0 0.25rem;
    font-size: 0.8125rem;
    font-weight: 600;
    color: #475569;
  }
}

.os-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr));
  gap: 1rem;

  .os-panel {
    margin-bottom: 1rem;
  }
}

.os-bars {
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 7rem 1fr 3rem;
    gap: 0.5rem;
    align-items: center;
    padding: 0.25rem 0.25rem;
    font-size: 0.75rem;
    color: #334155;
    cursor: pointer;
    border-radius: 0.25rem;

    &:hover {
      background: #f8fafc;
    }
  }

  &__name {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__bar {
    display: block;
    height: 0.5rem;
    overflow: hidden;
    background: #f1f5f9;
    border-radius: 9999px;

    i {
      display: block;
      height: 100%;
      background: #39c5bb;
      border-radius: 9999px;
    }
  }

  &__value {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }

  &__empty {
    color: #9ca3af;
    cursor: default;
  }
}

.os-seg {
  display: flex;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;

  button {
    padding: 0.25rem 0.625rem;
    font-size: 0.75rem;
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
      background: #39c5bb;
    }
  }
}

.os-btn {
  padding: 0.375rem 0.75rem;
  font-size: 0.8125rem;
  color: #fff;
  cursor: pointer;
  background: #39c5bb;
  border: none;
  border-radius: 0.375rem;

  &--ghost {
    color: #1b9c93;
    background: #e8f9f7;
    border: 1px solid #b9e7e3;
  }

  &--tiny {
    padding: 0.125rem 0.5rem;
    font-size: 0.6875rem;
  }
}

.os-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.os-table {
  width: 100%;
  min-width: 40rem;
  border-collapse: collapse;
  font-size: 0.8125rem;

  th,
  td {
    padding: 0.5rem 0.625rem;
    text-align: left;
    border-bottom: 1px solid #f1f5f9;
  }

  th {
    font-weight: 600;
    color: #4b5563;
    background: #f9fafb;
    white-space: nowrap;
  }

  /* Numeric columns shrink to their content and never wrap, so the values and the
     action button stay readable instead of collapsing into each other. */
  th:not(.os-col-title),
  td:not(.os-col-title) {
    width: 1%;
    white-space: nowrap;
  }

  &__right {
    text-align: right;
  }
}

.os-col-title {
  width: auto;
  max-width: 24rem;
}

.os-article__title {
  font-weight: 500;
  color: #111827;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.os-article__slug {
  margin-top: 0.125rem;
  font-size: 0.6875rem;
  color: #9ca3af;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.os-muted {
  color: #9ca3af;
}

.os-strong {
  font-weight: 600;
  color: #0f172a;
}

.os-empty {
  padding: 1.5rem 0;
  font-size: 0.8125rem;
  color: #9ca3af;
  text-align: center;
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
  width: min(32rem, 92vw);
  background: #fff;
  box-shadow: -8px 0 30px rgb(0 0 0 / 0.12);

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 1rem 1.25rem;
    border-bottom: 1px solid #f1f5f9;

    h3 {
      margin: 0;
      font-size: 1rem;
      font-weight: 600;
      color: #111827;
    }

    p {
      margin: 0.25rem 0 0;
      font-size: 0.75rem;
      color: #6b7280;
    }
  }

  &__close {
    padding: 0 0.25rem;
    font-size: 1.25rem;
    line-height: 1;
    color: #9ca3af;
    cursor: pointer;
    background: transparent;
    border: none;
  }

  &__body {
    flex: 1;
    padding: 0.75rem 1.25rem 2rem;
    overflow-y: auto;
  }
}

.os-detail {
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: 1fr auto auto;
    gap: 0.25rem 0.5rem;
    align-items: baseline;
    padding: 0.5rem 0;
    font-size: 0.8125rem;
    border-bottom: 1px solid #f8fafc;
  }

  &__label {
    display: flex;
    gap: 0.375rem;
    align-items: baseline;
    color: #0f172a;
    font-variant-numeric: tabular-nums;
    overflow-wrap: anywhere;
  }

  &__value {
    color: #1b9c93;
    font-weight: 600;
    white-space: nowrap;
  }

  &__extra {
    grid-column: 1 / -1;
    font-size: 0.6875rem;
    color: #94a3b8;
  }

  &__geo {
    grid-column: 1 / -1;
  }

  &__loc {
    font-size: 0.6875rem;
    color: #1b9c93;
  }
}

/* Same affordance as the visitor list, so both pages open the detail the same way. */
.os-more {
  flex: none;
  padding: 0 0.375rem;
  font-size: 0.6875rem;
  line-height: 1.6;
  color: #39c5bb;
  cursor: pointer;
  background: #e8f9f7;
  border: 1px solid #b9e7e3;
  border-radius: 0.25rem;

  &:hover {
    background: #b9e7e3;
  }

  &:disabled {
    color: #8addd6;
    cursor: wait;
  }

  &__arrow {
    margin-left: 0.125rem;
  }
}

.os-loc-btn {
  padding: 0 0.375rem;
  font-size: 0.6875rem;
  line-height: 1.6;
  color: #1b9c93;
  cursor: pointer;
  background: #e8f9f7;
  border: 1px solid #b9e7e3;
  border-radius: 0.25rem;

  &:disabled {
    color: #9ca3af;
    cursor: wait;
    background: #f3f4f6;
    border-color: #e5e7eb;
  }
}

/* Narrow screens: cards and panels stack, the table scrolls sideways instead of
   squashing its columns. */
@media (max-width: 768px) {
  .os-stat-page {
    padding: 0.75rem 0.75rem 2rem;

    &__head {
      flex-direction: column;
      gap: 0.5rem;
    }
  }

  .os-cards {
    grid-template-columns: repeat(auto-fit, minmax(7.5rem, 1fr));
    gap: 0.5rem;
  }

  .os-card__value {
    font-size: 1.25rem;
  }

  .os-grid {
    grid-template-columns: 1fr;
  }

  .os-panel {
    padding: 0.75rem 0.75rem 1rem;
    overflow-x: auto;

    &__head {
      flex-wrap: wrap;
      gap: 0.5rem;
    }
  }

  .os-table {
    min-width: 34rem;

    th,
    td {
      padding: 0.375rem 0.5rem;
      font-size: 0.75rem;
    }
  }

  .os-bars li {
    grid-template-columns: 5.5rem 1fr 2.5rem;
    gap: 0.375rem;
  }

  .os-drawer {
    width: 100%;
  }
}
</style>