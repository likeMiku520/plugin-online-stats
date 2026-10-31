<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import axios from 'axios'
import VisitorDetailDrawer from '../components/VisitorDetailDrawer.vue'
import {
  applyLocation,
  type LocationView,
  type RecordView,
  type UaInfo,
} from '../utils/visitor'

interface UnreadItem {
  visitorKey: string
  ip: string
  unread: number
  total: number
  lastText: string
  lastAt: number
}

interface Stats {
  online: number
  onlineHumans?: number
  onlineBots?: number
  total: number
  historyTotal?: number
  humans?: number
  bots?: number
  todayNew: number
  todayHumans?: number
  todayBots?: number
  loaded: boolean
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
const announceNote = ref('')
const announceEnabled = ref(false)
const testing = ref(false)
const keyword = ref('')
const status = ref<'all' | 'online' | 'offline'>('all')
const visitorType = ref<'human' | 'bot' | 'all'>('human')
const sort = ref<'lastSeenAt' | 'firstSeenAt' | 'visits'>('lastSeenAt')
const jumpTo = ref<string>('')
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
const rowBusy = reactive<Record<string, boolean>>({})
const rowError = reactive<Record<string, string>>({})
const unreadTotal = ref(0)
const unreadByKey = ref<Record<string, number>>({})
const unreadItems = ref<UnreadItem[]>([])
const unreadOpen = ref(false)
const unreadBusy = ref('')

let pollTimer: number | undefined
let searchTimer: number | undefined

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const canPrev = computed(() => page.value > 1)
const canNext = computed(() => page.value < totalPages.value)
const historyTotal = computed(() => stats.value.historyTotal ?? (stats.value.humans ?? 0) + (stats.value.bots ?? 0))
/** A conversation belongs to the device behind the record, not to the address. */
function unreadFor(item: RecordView): number {
  return unreadByKey.value[item.name] || 0
}

/** Addresses of the records with unread replies, for the reminder line. */
const unreadIps = computed(() =>
  items.value.filter((item) => unreadFor(item) > 0).map((item) => item.ip || item.name),
)

/**
 * Unread counts per device, so a reply from a visitor shows up on the row it belongs
 * to instead of only inside the conversation panel. The rows themselves are kept too,
 * which is what the detail list under the reminder shows.
 */
async function loadUnread() {
  try {
    const { data } = await axios.get(`${api}/chat-conversations`)
    unreadTotal.value = data.unreadTotal || 0
    const counts: Record<string, number> = {}
    const pending: UnreadItem[] = []
    for (const item of data.items || []) {
      if (item.unread > 0 && item.visitorKey) {
        counts[item.visitorKey] = item.unread
        pending.push({
          visitorKey: item.visitorKey,
          ip: item.ip || '',
          unread: item.unread,
          total: item.total || 0,
          lastText: item.lastText || '',
          lastAt: item.lastAt || 0,
        })
      }
    }
    unreadByKey.value = counts
    unreadItems.value = pending
    if (!pending.length) {
      unreadOpen.value = false
    }
  } catch (e) {
    /* the endpoint is admin only; keep the previous state on any failure */
  }
}

/**
 * Clears every unread reply at once. Reading a conversation marks it read on its own,
 * so this is for the reminder that is left over after going through the list.
 */
async function markAllRead() {
  if (unreadBusy.value) {
    return
  }
  unreadBusy.value = 'all'
  try {
    await axios.post(`${api}/chat-read-all`)
    await loadUnread()
  } catch (e) {
    /* keep the reminder; the next poll retries */
  } finally {
    unreadBusy.value = ''
  }
}

/**
 * Opens the conversation of an unread row. Records outside the current page are not in
 * the list, so they are fetched by their device key first.
 */
async function openUnread(entry: UnreadItem) {
  const known = items.value.find((item) => item.name === entry.visitorKey)
  if (known) {
    openDetail(known)
    unreadOpen.value = false
    return
  }
  if (unreadBusy.value) {
    return
  }
  unreadBusy.value = entry.visitorKey
  try {
    const { data } = await axios.get<RecordView>(`${api}/records/${entry.visitorKey}`)
    if (data && data.name) {
      openDetail(data)
      unreadOpen.value = false
    }
  } catch (e) {
    /* the row may have been cleaned up meanwhile; the next poll drops it */
  } finally {
    unreadBusy.value = ''
  }
}

/** Sends the configured greeting to the first listed visitor, so the wording can be checked. */
async function testAnnounce() {
  const target = items.value[0]
  if (!target) {
    announceNote.value = '列表里还没有访客，等有人访问后再试。'
    return
  }
  testing.value = true
  announceNote.value = ''
  try {
    const { data } = await axios.post(`${api}/maintenance/announce-test`, {
      key: target.name,
    })
    announceNote.value = `已发给 ${target.ip ?? '这位访客'}，内容为：${data.text}`
  } catch (e: any) {
    announceNote.value = e?.response?.data?.error ?? '发送失败，请稍后再试。'
  } finally {
    testing.value = false
  }
}

/** Tells the page whether the greeting is switched on, so the test button can hide. */
async function loadAnnounceState() {
  try {
    const { data } = await axios.get(`${api}/maintenance/announce-enabled`)
    announceEnabled.value = Boolean(data.chatAnnounceEnabled)
  } catch (e) {
    announceEnabled.value = false
  }
}

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
      // Keeps the online badge and the timestamps of an open drawer live; the drawer
      // keeps its own copy of the fields it can change, such as the resolved location.
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

/** Moves by one page, or straight to the first or last page when an edge is asked for. */
function goPage(delta: number, edge = false) {
  const next = edge ? (delta > 0 ? totalPages.value : 1) : page.value + delta
  if (next < 1 || next > totalPages.value || next === page.value) {
    return
  }
  page.value = next
  load()
}

/** Jumps to a typed page number, clamped to the range that actually exists. */
function goToPage() {
  const raw = typeof jumpTo.value === 'string' ? jumpTo.value.trim() : jumpTo.value
  if (raw === '' || raw === null || raw === undefined) {
    return
  }
  const target = Number(raw)
  if (!Number.isFinite(target)) {
    return
  }
  const next = Math.min(Math.max(Math.trunc(target), 1), totalPages.value)
  jumpTo.value = ''
  if (next === page.value) {
    return
  }
  page.value = next
  load()
}

function openDetail(item: RecordView) {
  detail.value = item
}

function closeDetail() {
  detail.value = null
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

function refresh() {
  load()
  loadUnread()
}

watch([page, size], () => load())

onMounted(() => {
  refresh()
  loadAnnounceState()
  pollTimer = window.setInterval(refresh, 3_000)
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
      <div class="os-page__actions">
        <button
          v-if="announceEnabled"
          class="os-toolbar__refresh"
          type="button"
          :disabled="testing"
          @click="testAnnounce"
        >
          {{ testing ? '发送中…' : '自动访客消息测试' }}
        </button>
      </div>
    </div>

    <div v-if="announceNote" class="os-page__note">{{ announceNote }}</div>

    <div class="os-page__stats">
      <div class="os-stat">
        <span class="os-stat__label">当前在线</span>
        <span class="os-stat__value os-stat__value--online">{{ stats.online }}</span>
        <span class="os-stat__split">
          <span class="os-stat__part">真人 {{ stats.onlineHumans ?? 0 }}</span>
          <span class="os-stat__part os-stat__part--bot">机器人 {{ stats.onlineBots ?? 0 }}</span>
        </span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">历史访客</span>
        <span class="os-stat__value">{{ historyTotal }}</span>
        <span class="os-stat__split">
          <span class="os-stat__part">真人 {{ stats.humans ?? 0 }}</span>
          <span class="os-stat__part os-stat__part--bot">机器人 {{ stats.bots ?? 0 }}</span>
        </span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">今日新增</span>
        <span class="os-stat__value">{{ stats.todayNew }}</span>
        <span class="os-stat__split">
          <span class="os-stat__part">真人 {{ stats.todayHumans ?? 0 }}</span>
          <span class="os-stat__part os-stat__part--bot">机器人 {{ stats.todayBots ?? 0 }}</span>
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

    <div v-if="unreadTotal > 0" class="os-unread">
      <div class="os-unread__row">
        <span class="os-unread__dot" />
        <span class="os-unread__text">
          有 {{ unreadTotal }} 条未读访客消息<template v-if="unreadIps.length">
            （{{ unreadIps.slice(0, 3).join('、') }}{{ unreadIps.length > 3 ? ' 等' : '' }}）</template
          >，点对应记录的「更多」查看。
        </span>
        <button
          class="os-unread__toggle"
          type="button"
          :aria-expanded="unreadOpen"
          @click="unreadOpen = !unreadOpen"
        >
          {{ unreadOpen ? '收起' : '详细' }}
        </button>
        <button
          class="os-unread__toggle os-unread__toggle--quiet"
          type="button"
          :disabled="unreadBusy === 'all'"
          @click="markAllRead()"
        >
          全部已读
        </button>
      </div>

      <ul v-if="unreadOpen" class="os-unread__list">
        <li v-for="entry in unreadItems" :key="entry.visitorKey">
          <button
            class="os-unread__item"
            type="button"
            :disabled="unreadBusy === entry.visitorKey"
            @click="openUnread(entry)"
          >
            <span class="os-unread__who">
              {{ entry.ip || '未知地址' }}
              <span class="os-unread__count">{{ entry.unread }} 条未读</span>
            </span>
            <span class="os-unread__last">{{ entry.lastText }}</span>
            <span class="os-unread__time">{{ fmtRelative(entry.lastAt) }}</span>
          </button>
        </li>
      </ul>
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
              <button
                class="os-more"
                :class="{ 'os-more--unread': unreadFor(item) > 0 }"
                type="button"
                @click="openDetail(item)"
              >
                更多<span class="os-more__arrow">›</span>
                <span v-if="unreadFor(item) > 0" class="os-more__badge">
                  {{ unreadFor(item) }}
                </span>
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="total > 0" class="os-pager">
        <span class="os-pager__info">共 {{ total }} 条 · 第 {{ page }}/{{ totalPages }} 页</span>
        <div class="os-pager__actions">
          <button type="button" :disabled="!canPrev" @click="goPage(-1, true)">首页</button>
          <button type="button" :disabled="!canPrev" @click="goPage(-1)">上一页</button>
          <button type="button" :disabled="!canNext" @click="goPage(1)">下一页</button>
          <button type="button" :disabled="!canNext" @click="goPage(1, true)">末页</button>
          <span class="os-pager__jump">
            <span>跳至</span>
            <input
              v-model="jumpTo"
              class="os-pager__input"
              type="number"
              inputmode="numeric"
              min="1"
              :max="totalPages"
              :placeholder="String(page)"
              :disabled="totalPages <= 1"
              aria-label="跳转到指定页码"
              @keyup.enter="goToPage()"
            />
            <span>页</span>
            <button type="button" :disabled="totalPages <= 1" @click="goToPage()">跳转</button>
          </span>
        </div>
      </div>
    </div>

    <VisitorDetailDrawer v-if="detail" :record="detail" @close="closeDetail" />
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
  }

  &__split {
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem 0.375rem;
  }

  &__part {
    padding: 0 0.375rem;
    font-size: 0.6875rem;
    line-height: 1.5;
    color: #167A72;
    background: #D8F4F1;
    border-radius: 0.25rem;

    &--bot {
      color: #4b5563;
      background: #f3f4f6;
    }
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

  &--unread {
    color: #b91c1c;
    background: #fef2f2;
    border-color: #fecaca;

    &:hover {
      background: #fee2e2;
    }
  }

  &__arrow {
    margin-left: 0.125rem;
  }

  &__badge {
    display: inline-block;
    min-width: 1rem;
    padding: 0 0.25rem;
    margin-left: 0.25rem;
    font-size: 0.625rem;
    line-height: 1rem;
    color: #fff;
    text-align: center;
    background: #ef4444;
    border-radius: 9999px;
  }
}

.os-unread {
  padding: 0.625rem 0.875rem;
  margin-bottom: 0.75rem;
  font-size: 0.8125rem;
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 0.5rem;

  &__row {
    display: flex;
    gap: 0.5rem;
    align-items: center;
  }

  &__dot {
    flex: none;
    width: 0.5rem;
    height: 0.5rem;
    background: #ef4444;
    border-radius: 9999px;
    box-shadow: 0 0 0 3px rgb(239 68 68 / 0.18);
  }

  &__text {
    flex: 1;
    min-width: 0;
    line-height: 1.6;
  }

  &__toggle {
    flex: none;
    padding: 0.1875rem 0.5rem;
    font-size: 0.75rem;
    color: #b91c1c;
    cursor: pointer;
    background: #fff;
    border: 1px solid #fecaca;
    border-radius: 0.375rem;

    &:hover {
      background: #fee2e2;
    }

    &--quiet {
      color: #991b1b;

      &:disabled {
        cursor: progress;
        opacity: 0.6;
      }
    }
  }

  &__list {
    padding: 0;
    margin: 0.625rem 0 0;
    list-style: none;
    border-top: 1px solid #fecaca;
  }

  &__item {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    width: 100%;
    padding: 0.5rem 0.125rem;
    font-size: 0.75rem;
    color: #7f1d1d;
    text-align: left;
    cursor: pointer;
    background: none;
    border: 0;
    border-bottom: 1px solid rgb(254 202 202 / 0.7);

    &:hover {
      background: rgb(254 226 226 / 0.6);
    }

    &:disabled {
      cursor: progress;
      opacity: 0.6;
    }
  }

  &__who {
    flex: none;
    max-width: 12rem;
    overflow: hidden;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__count {
    margin-left: 0.375rem;
    font-weight: 400;
    color: #dc2626;
  }

  &__last {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    color: #991b1b;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__time {
    flex: none;
    color: #b91c1c;
    opacity: 0.8;
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
    flex-wrap: wrap;
    gap: 0.5rem;
    align-items: center;

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

  &__jump {
    display: flex;
    gap: 0.3125rem;
    align-items: center;
    font-size: 0.75rem;
    color: #6b7280;
  }

  &__input {
    width: 3.25rem;
    padding: 0.25rem 0.375rem;
    font-size: 0.75rem;
    color: #374151;
    text-align: center;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;
    outline: none;

    &:focus {
      border-color: #8ADDD6;
    }

    &:disabled {
      background: #f9fafb;
      cursor: not-allowed;
    }
  }
}

/* Narrow screens: stack the toolbar, let the table scroll instead of squashing. */
@media (max-width: 768px) {
  .os-page {
    padding: 0.75rem 0.75rem 2rem;

    &__stats {
      grid-template-columns: repeat(auto-fit, minmax(7.5rem, 1fr));
      gap: 0.5rem;
    }

    &__card {
      overflow-x: auto;
    }
  }

  .os-stat__value {
    font-size: 1.25rem;
  }

  .os-toolbar__row {
    gap: 0.375rem;
  }

  .os-toolbar__search {
    flex: 1 1 100%;
  }

  .os-toolbar__sort {
    margin-left: 0;
  }

  .os-table {
    min-width: 34rem;

    th,
    td {
      padding: 0.375rem 0.5rem;
      font-size: 0.75rem;
    }
  }
}
</style>
