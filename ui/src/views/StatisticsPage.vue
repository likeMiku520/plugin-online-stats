<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import axios from 'axios'
import StatsChart, { type Point } from '../components/StatsChart.vue'

interface Overview {
  views: number
  visitors: number
  uniqueVisitors: number
  officialViews: number
  totalOnlineMs: number
  todayViews: number
  todayVisitors: number
  online: number
  articles: number
  launchedays?: number
  onlineSince: string | null
  loaded: boolean
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
}

const api = '/apis/online-stats.halo.run/v1alpha1'

const overview = ref<Overview | null>(null)
const series = ref<Point[]>([])
const daily7 = ref<Point[]>([])
const articles = ref<ArticleRow[]>([])
const browsers = ref<SourceItem[]>([])
const systems = ref<SourceItem[]>([])
const referers = ref<SourceItem[]>([])
const trendDays = ref(30)
const articleSort = ref<'views' | 'visitors' | 'today' | 'duration'>('views')
const loading = ref(true)

const drill = ref<{
  open: boolean
  title: string
  subtitle: string
  loading: boolean
  kind: 'article' | 'source' | 'visitor' | ''
  rows: { label: string; value: string; extra?: string }[]
} >({ open: false, title: '', subtitle: '', loading: false, kind: '', rows: [] })

let timer: number | undefined

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
    daily7.value = tr.data.daily7 || []
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
        value: `${v.views} 次`,
        extra: `停留 ${fmtDuration(v.durationMs)} · ${fmtTime(v.lastSeenAt)}`,
      }),
    )
    const a = data.article
    drill.value = {
      open: true,
      title: title || '文章详情',
      subtitle: `本插件统计 ${a.views} 次浏览 · ${a.visitors} 位访客 · 平均停留 ${fmtDuration(
        a.avgOnlineMs,
      )} · Halo 官方计数 ${a.officialViews}`,
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
        value: `${v.views} 次`,
        extra: v.ip ? v.visitorKey : undefined,
      }),
    )
    drill.value = {
      open: true,
      title: value,
      subtitle: `近 30 天 ${data.views} 次浏览 · ${data.visitors} 位访客`,
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
        <p>
          数据来自插件自己的访问记录，浏览量同时对照 Halo 官方计数。
          <template v-if="overview?.onlineSince">
            统计起点：{{ overview.onlineSince }}。
          </template>
        </p>
      </div>
      <button class="os-btn os-btn--ghost" type="button" @click="loadAll">刷新</button>
    </header>

    <div v-if="overview" class="os-cards">
      <div class="os-card os-card--hero">
        <span class="os-card__label">当前在线</span>
        <span class="os-card__value">{{ overview.online }}</span>
        <span class="os-card__hint">人正在浏览</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">总浏览量</span>
        <span class="os-card__value">{{ overview.views }}</span>
        <span class="os-card__hint">Halo 官方计数 {{ overview.officialViews }}</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">总访客</span>
        <span class="os-card__value">{{ overview.uniqueVisitors }}</span>
        <span class="os-card__hint">去重设备指纹</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">今日浏览</span>
        <span class="os-card__value">{{ overview.todayViews }}</span>
        <span class="os-card__hint">今日访客 {{ overview.todayVisitors }}</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">文章数</span>
        <span class="os-card__value">{{ overview.articles }}</span>
        <span class="os-card__hint">被访问过的文章</span>
      </div>
      <div class="os-card">
        <span class="os-card__label">累计阅读时长</span>
        <span class="os-card__value os-card__value--sm">{{ fmtDuration(overview.totalOnlineMs) }}</span>
        <span class="os-card__hint">按心跳间隔累计</span>
      </div>
    </div>

    <section class="os-panel">
      <div class="os-panel__head">
        <h3>访问趋势</h3>
        <div class="os-seg">
          <button
            v-for="option in [
              { value: 7, label: '近 7 天' },
              { value: 30, label: '近 30 天' },
              { value: 90, label: '近 90 天' },
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
      <div class="os-mini">
        <div>
          <h4>近 7 天</h4>
          <StatsChart :series="daily7" metric="views" :height="140" />
        </div>
        <div>
          <h4>近 30 天</h4>
          <StatsChart :series="series.slice(-30)" metric="visitors" :height="140" />
        </div>
      </div>
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
              { value: 'visitors', label: '总访客' },
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

      <table class="os-table">
        <thead>
          <tr>
            <th>文章</th>
            <th class="os-right">浏览量</th>
            <th class="os-right">官方</th>
            <th class="os-right">访客</th>
            <th class="os-right">今日</th>
            <th class="os-right">平均停留</th>
            <th class="os-right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in articles" :key="item.postName">
            <td>
              <div class="os-article__title">{{ item.title || item.slug || item.postName }}</div>
              <div class="os-article__slug">{{ item.permalink || item.slug }}</div>
            </td>
            <td class="os-right">{{ item.views }}</td>
            <td class="os-right os-muted">{{ item.officialViews }}</td>
            <td class="os-right">{{ item.visitors }}</td>
            <td class="os-right">{{ item.todayViews }}</td>
            <td class="os-right">{{ fmtDuration(item.avgOnlineMs) }}</td>
            <td class="os-right">
              <button class="os-btn os-btn--tiny" type="button" @click="openArticle(item.postName, item.title)">
                详情
              </button>
            </td>
          </tr>
          <tr v-if="!articles.length">
            <td colspan="7" class="os-empty">暂无文章访问记录</td>
          </tr>
        </tbody>
      </table>
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
              <span class="os-detail__label">{{ row.label }}</span>
              <span class="os-detail__value">{{ row.value }}</span>
              <span v-if="row.extra" class="os-detail__extra">{{ row.extra }}</span>
            </li>
          </ul>
          <div v-else class="os-empty">没有更细的数据</div>
        </div>
      </aside>
    </div>
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

.os-table {
  width: 100%;
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
  }
}

.os-right {
  text-align: right;
}

.os-muted {
  color: #9ca3af;
}

.os-article__title {
  font-weight: 500;
  color: #111827;
}

.os-article__slug {
  margin-top: 0.125rem;
  font-size: 0.6875rem;
  color: #9ca3af;
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
    grid-template-columns: 1fr auto;
    gap: 0.25rem 0.5rem;
    padding: 0.5rem 0;
    font-size: 0.8125rem;
    border-bottom: 1px solid #f8fafc;
  }

  &__label {
    color: #0f172a;
    font-variant-numeric: tabular-nums;
  }

  &__value {
    color: #1b9c93;
    font-weight: 600;
  }

  &__extra {
    grid-column: 1 / -1;
    font-size: 0.6875rem;
    color: #94a3b8;
  }
}
</style>