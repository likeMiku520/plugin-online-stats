<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import axios from 'axios'
import VisitorDetailDrawer from '../components/VisitorDetailDrawer.vue'
import type { RecordView } from '../utils/visitor'

interface VisitorRow {
  visitorKey: string
  ip: string | null
  location: string | null
  pageTitle: string | null
  userAgent: string | null
  bot: boolean
  firstSeenAt: number
  lastSeenAt: number
  visitCount: number
  pageViewCount: number
}

interface ConversationRow {
  visitorKey: string
  ip: string | null
  location: string | null
  bot: boolean
  total: number
  lastText: string
  lastAt: number
  firstSeenAt: number
  lastSeenAt: number
  visitCount: number
}

interface Summary {
  visitors: number
  humans: number
  bots: number
  conversations: number
  messages: number
  version?: string
}

interface ThreadMessage {
  seq: number
  direction: string
  text: string
  sentAt: number
  authorName: string | null
}

type Scope = 'visitors' | 'chat'
type TypeFilter = 'all' | 'human' | 'bot'

const api = '/apis/online-stats.halo.run/v1alpha1'

const scope = ref<Scope>('visitors')
const type = ref<TypeFilter>('all')
const sort = ref<'lastSeenAt' | 'firstSeenAt' | 'visitCount'>('lastSeenAt')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const busy = ref(false)
const notice = ref('')
const noticeKind = ref<'ok' | 'warn'>('ok')

const summary = ref<Summary>({
  visitors: 0,
  humans: 0,
  bots: 0,
  conversations: 0,
  messages: 0,
  version: '',
})
const visitors = ref<VisitorRow[]>([])
const conversations = ref<ConversationRow[]>([])
const total = ref(0)
const totalPages = ref(1)

const multiSelect = ref(false)
const selected = ref<string[]>([])
const detail = ref<RecordView | null>(null)
const threadOpen = ref(false)
const threadKey = ref<string | null>(null)
const threadTitle = ref('')
const threadMessages = ref<ThreadMessage[]>([])
const jumpTo = ref('')

const rows = computed<Array<VisitorRow | ConversationRow>>(() =>
  scope.value === 'visitors' ? visitors.value : conversations.value,
)
const selectedCount = computed(() => selected.value.length)
const allOnPageSelected = computed(
  () => rows.value.length > 0 && rows.value.every((row) => selected.value.includes(row.visitorKey)),
)
const canPrev = computed(() => page.value > 1)
const canNext = computed(() => page.value < totalPages.value)

function say(message: string, kind: 'ok' | 'warn' = 'ok') {
  notice.value = message
  noticeKind.value = kind
}

function query() {
  return {
    type: type.value,
    sort: sort.value,
    keyword: keyword.value.trim(),
  }
}

async function loadSummary() {
  try {
    const { data } = await axios.get(`${api}/maintenance/summary`)
    summary.value = data
  } catch (e) {
    /* the table still works without the counters */
  }
}

async function load(resetSelection = false) {
  loading.value = true
  try {
    const url = scope.value === 'visitors' ? '/maintenance/visitors' : '/maintenance/conversations'
    const { data } = await axios.get(`${api}${url}`, {
      params: { ...query(), page: page.value, size: size.value },
    })
    if (scope.value === 'visitors') {
      visitors.value = data.items ?? []
    } else {
      conversations.value = data.items ?? []
    }
    total.value = data.total ?? 0
    totalPages.value = data.totalPages ?? 1
    if (resetSelection) {
      selected.value = []
    }
  } catch (e) {
    say('读取数据失败，请稍后重试。', 'warn')
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await Promise.all([load(), loadSummary()])
}

watch([scope, type, sort], () => {
  page.value = 1
  selected.value = []
  load(true)
})

let searchTimer: number | undefined
watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 1
    selected.value = []
    load(true)
  }, 300)
})

function goPage(next: number) {
  const target = Math.min(Math.max(1, next), totalPages.value)
  if (target === page.value) {
    return
  }
  page.value = target
  selected.value = []
  load()
}

function jump() {
  const target = Number.parseInt(jumpTo.value, 10)
  if (Number.isNaN(target)) {
    return
  }
  goPage(target)
  jumpTo.value = ''
}

function toggleMulti() {
  multiSelect.value = !multiSelect.value
  selected.value = []
}

function toggleRow(key: string) {
  const at = selected.value.indexOf(key)
  if (at >= 0) {
    selected.value.splice(at, 1)
  } else {
    selected.value.push(key)
  }
}

function toggleAll() {
  if (allOnPageSelected.value) {
    selected.value = selected.value.filter(
      (key) => !rows.value.some((row) => row.visitorKey === key),
    )
  } else {
    for (const row of rows.value) {
      if (!selected.value.includes(row.visitorKey)) {
        selected.value.push(row.visitorKey)
      }
    }
  }
}

/** In multi-select mode a row is a checkbox; otherwise it opens that visitor. */
async function onRow(row: VisitorRow | ConversationRow) {
  if (multiSelect.value) {
    toggleRow(row.visitorKey)
    return
  }
  if (scope.value === 'chat') {
    await openThread(row.visitorKey, row.ip)
    return
  }
  try {
    const { data } = await axios.get<RecordView>(`${api}/records/${row.visitorKey}`)
    detail.value = data
  } catch (e) {
    say('打不开这位访客的详情。', 'warn')
  }
}

async function openThread(key: string, ip: string | null) {
  threadKey.value = key
  threadTitle.value = ip || key
  threadOpen.value = true
  try {
    const { data } = await axios.get(`${api}/maintenance/thread`, { params: { key } })
    threadMessages.value = data.items ?? []
    if (data.ip) {
      threadTitle.value = data.ip
    }
  } catch (e) {
    say('读取聊天记录失败。', 'warn')
    threadMessages.value = []
  }
}

function closeThread() {
  threadOpen.value = false
  threadKey.value = null
  threadMessages.value = []
}

function download(url: string, params: Record<string, string>) {
  const search = new window.URLSearchParams(params).toString()
  window.open(`${api}${url}${search ? `?${search}` : ''}`, '_blank')
}

function exportVisitors() {
  download('/maintenance/visitors/export', query())
  say('正在导出当前筛选的访客数据。')
}

function exportChat(all: boolean) {
  const key = all ? '' : selected.value[0]
  if (!all && !key) {
    say('先选一位访客，或者直接导出全部。', 'warn')
    return
  }
  download('/maintenance/chat/export', key ? { key } : {})
  say(all ? '正在导出全部聊天记录。' : '正在导出这位访客的聊天记录。')
}

async function clear(what: 'visitors' | 'chat' | 'both', all: boolean) {
  const keys = all ? [] : [...selected.value]
  if (!all && !keys.length) {
    say('先勾选要清除的访客。', 'warn')
    return
  }
  const label =
    what === 'chat' ? '聊天记录' : what === 'visitors' ? '访客数据' : '访客数据和聊天记录'
  const who = all ? '全部访客' : `选中的 ${keys.length} 位访客`
  if (!window.confirm(`确定要清除${who}的${label}吗？删除后无法恢复。`)) {
    return
  }
  busy.value = true
  try {
    const { data } = await axios.post(`${api}/maintenance/clear`, { what, all, keys })
    say(`已清除 ${data.visitors} 条访客数据、${data.messages} 条聊天记录。`)
    selected.value = []
    if (page.value > 1 && rows.value.length <= keys.length) {
      page.value -= 1
    }
    await refresh()
  } catch (e: any) {
    say(e?.response?.data?.error ?? '清除失败，请稍后再试。', 'warn')
  } finally {
    busy.value = false
  }
}

async function sendTest() {
  const key = selected.value[0] ?? rows.value[0]?.visitorKey
  if (!key) {
    say('先勾选一位访客。', 'warn')
    return
  }
  busy.value = true
  try {
    const { data } = await axios.post(`${api}/maintenance/announce-test`, { key })
    say(`已发送自动消息：${data.text}`)
    if (threadOpen.value && threadKey.value === key) {
      await openThread(key, threadTitle.value)
    }
  } catch (e: any) {
    say(e?.response?.data?.error ?? '发送失败，请稍后再试。', 'warn')
  } finally {
    busy.value = false
  }
}

function fmtTime(value: number | null | undefined) {
  if (!value) {
    return '—'
  }
  const at = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(at.getHours())}:${pad(at.getMinutes())}`
}

/** A short "系统 · 浏览器" line, the same shape the visitor list shows. */
function deviceSummary(userAgent: string | null | undefined) {
  if (!userAgent) {
    return '未知设备'
  }
  const ua = userAgent
  let os = '其它'
  if (/Windows/i.test(ua)) {
    os = 'Windows'
  } else if (/Android/i.test(ua)) {
    os = 'Android'
  } else if (/iPhone|iPad|iPod/i.test(ua)) {
    os = 'iOS'
  } else if (/Macintosh|Mac OS X/i.test(ua)) {
    os = 'macOS'
  } else if (/Linux/i.test(ua)) {
    os = 'Linux'
  }
  let browser = '其它'
  if (/MicroMessenger/i.test(ua)) {
    browser = '微信'
  } else if (/Edg\//i.test(ua)) {
    browser = 'Edge'
  } else if (/Chrome\//i.test(ua) && !/OPR\//i.test(ua)) {
    browser = 'Chrome'
  } else if (/Firefox\//i.test(ua)) {
    browser = 'Firefox'
  } else if (/Safari\//i.test(ua)) {
    browser = 'Safari'
  } else if (/bot|crawler|spider/i.test(ua)) {
    browser = '爬虫'
  }
  return `${os} · ${browser}`
}

function closeDetail() {
  detail.value = null
}

let timer: number | undefined
onMounted(() => {
  refresh()
  timer = window.setInterval(() => {
    if (!detail.value && !window.document.hidden) {
      refresh()
    }
  }, 5_000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})
</script>

<template>
  <div class="os-md">
    <div class="os-md__head">
      <div>
        <h2 class="os-md__title">
          访客数据管理
          <span v-if="summary.version" class="os-md__version">v{{ summary.version }}</span>
        </h2>
        <p class="os-md__desc">
          导出或清除访客数据和聊天记录。每 5 秒自动刷新，点一行可以看详情，需要批量处理时再打开多选。
        </p>
      </div>
      <div class="os-md__actions">
        <button
          class="os-md__toggle"
          :class="{ 'is-on': multiSelect }"
          type="button"
          @click="toggleMulti"
        >
          {{ multiSelect ? `退出多选（已选 ${selectedCount}）` : '开启多选' }}
        </button>
      </div>
    </div>

    <div v-if="notice" class="os-alert" :class="{ 'os-alert--info': noticeKind === 'ok' }">
      {{ notice }}
    </div>

    <div class="os-md__stats">
      <div class="os-stat">
        <span class="os-stat__label">访客总数</span>
        <span class="os-stat__value">{{ summary.visitors }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">真人</span>
        <span class="os-stat__value">{{ summary.humans }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">机器人</span>
        <span class="os-stat__value">{{ summary.bots }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">会话数</span>
        <span class="os-stat__value">{{ summary.conversations }}</span>
      </div>
      <div class="os-stat">
        <span class="os-stat__label">消息数</span>
        <span class="os-stat__value">{{ summary.messages }}</span>
      </div>
    </div>

    <div class="os-md__card">
      <div class="os-toolbar">
        <div class="os-toolbar__row">
          <div class="os-toolbar__segment">
            <button
              type="button"
              :class="{ 'is-active': scope === 'visitors' }"
              @click="scope = 'visitors'"
            >
              访客数据
            </button>
            <button type="button" :class="{ 'is-active': scope === 'chat' }" @click="scope = 'chat'">
              聊天记录
            </button>
          </div>
          <div class="os-toolbar__segment">
            <button type="button" :class="{ 'is-active': type === 'all' }" @click="type = 'all'">
              全部
            </button>
            <button
              type="button"
              :class="{ 'is-active': type === 'human' }"
              @click="type = 'human'"
            >
              真人
            </button>
            <button type="button" :class="{ 'is-active': type === 'bot' }" @click="type = 'bot'">
              机器人
            </button>
          </div>
          <div class="os-toolbar__segment">
            <button
              type="button"
              :class="{ 'is-active': sort === 'lastSeenAt' }"
              @click="sort = 'lastSeenAt'"
            >
              最近活跃
            </button>
            <button
              type="button"
              :class="{ 'is-active': sort === 'firstSeenAt' }"
              @click="sort = 'firstSeenAt'"
            >
              首次访问
            </button>
            <button
              type="button"
              :class="{ 'is-active': sort === 'visitCount' }"
              @click="sort = 'visitCount'"
            >
              访问次数
            </button>
          </div>
          <input
            v-model="keyword"
            class="os-toolbar__search"
            type="search"
            placeholder="搜索 IP、属地或页面"
          />
        </div>

        <div class="os-toolbar__row">
          <template v-if="scope === 'visitors'">
            <button class="os-toolbar__refresh" type="button" @click="exportVisitors">
              导出当前筛选
            </button>
            <button
              class="os-toolbar__refresh"
              type="button"
              :disabled="busy || !selectedCount"
              @click="clear('visitors', false)"
            >
              清除选中的数据（{{ selectedCount }}）
            </button>
            <button
              class="os-toolbar__refresh os-toolbar__refresh--danger"
              type="button"
              :disabled="busy"
              @click="clear('visitors', true)"
            >
              清除全部数据
            </button>
          </template>
          <template v-else>
            <button
              class="os-toolbar__refresh"
              type="button"
              :disabled="busy || !selectedCount"
              @click="exportChat(false)"
            >
              导出选中的聊天
            </button>
            <button class="os-toolbar__refresh" type="button" @click="exportChat(true)">
              导出全部聊天
            </button>
            <button
              class="os-toolbar__refresh"
              type="button"
              :disabled="busy || !selectedCount"
              @click="clear('chat', false)"
            >
              清除选中的聊天（{{ selectedCount }}）
            </button>
            <button
              class="os-toolbar__refresh os-toolbar__refresh--danger"
              type="button"
              :disabled="busy"
              @click="clear('chat', true)"
            >
              清除全部聊天
            </button>
          </template>
          <button
            class="os-toolbar__refresh"
            type="button"
            :disabled="busy || !(selectedCount || rows.length)"
            @click="sendTest"
          >
            发测试消息
          </button>
        </div>
      </div>

      <table class="os-table">
        <thead>
          <tr>
            <th v-if="multiSelect" class="os-table__check">
              <input
                type="checkbox"
                :checked="allOnPageSelected"
                aria-label="全选本页"
                @change="toggleAll"
              />
            </th>
            <th>访客</th>
            <th>设备</th>
            <template v-if="scope === 'visitors'">
              <th>最后页面</th>
              <th class="os-table__right">访问次数</th>
              <th>最近活跃</th>
            </template>
            <template v-else>
              <th>最后一条消息</th>
              <th class="os-table__right">条数</th>
              <th>时间</th>
            </template>
          </tr>
        </thead>

        <tbody>
          <tr
            v-for="row in rows"
            :key="row.visitorKey"
            class="os-table__pick"
            :class="{ 'is-selected': selected.includes(row.visitorKey) }"
            @click="onRow(row)"
          >
            <td v-if="multiSelect" class="os-table__check" @click.stop>
              <input
                type="checkbox"
                :checked="selected.includes(row.visitorKey)"
                :aria-label="`选择 ${row.ip || row.visitorKey}`"
                @change="toggleRow(row.visitorKey)"
              />
            </td>
            <td>
              <div class="os-visitor">
                <span class="os-dot" :class="{ 'os-dot--bot': row.bot }" />
                <div>
                  <div class="os-visitor__ip">{{ row.ip || row.visitorKey }}</div>
                  <div class="os-visitor__loc">
                    <span v-if="row.bot" class="os-badge os-badge--bot">机器人</span>
                    <span v-else class="os-badge">真人</span>
                    <template v-if="row.location">{{ row.location }}</template>
                  </div>
                </div>
              </div>
            </td>
            <td>
              <div class="os-device">{{ deviceSummary((row as VisitorRow).userAgent) }}</div>
            </td>
            <template v-if="scope === 'visitors'">
              <td>
                <div class="os-page-title">{{ (row as VisitorRow).pageTitle || '—' }}</div>
              </td>
              <td class="os-table__right">{{ (row as VisitorRow).visitCount ?? 0 }}</td>
              <td>{{ fmtTime((row as VisitorRow).lastSeenAt) }}</td>
            </template>
            <template v-else>
              <td>
                <div class="os-thread-preview">{{ (row as ConversationRow).lastText }}</div>
              </td>
              <td class="os-table__right">{{ (row as ConversationRow).total }}</td>
              <td>{{ fmtTime((row as ConversationRow).lastAt) }}</td>
            </template>
          </tr>
        </tbody>
      </table>

      <p v-if="loading && !rows.length" class="os-md__empty">加载中…</p>
      <p v-else-if="!rows.length" class="os-md__empty">没有符合条件的数据。</p>

      <div v-if="totalPages > 1" class="os-pager">
        <span class="os-pager__info">共 {{ total }} 条</span>
        <button type="button" :disabled="!canPrev" @click="goPage(1)">首页</button>
        <button type="button" :disabled="!canPrev" @click="goPage(page - 1)">上一页</button>
        <span class="os-pager__now">{{ page }} / {{ totalPages }}</span>
        <button type="button" :disabled="!canNext" @click="goPage(page + 1)">下一页</button>
        <button type="button" :disabled="!canNext" @click="goPage(totalPages)">末页</button>
        <span class="os-pager__jump">
          跳至
          <input v-model="jumpTo" type="number" min="1" @keyup.enter="jump" />
          <button type="button" @click="jump">跳转</button>
        </span>
      </div>
    </div>

    <div v-if="threadOpen" class="os-thread" @click.self="closeThread">
      <div class="os-thread__panel">
        <div class="os-thread__head">
          <strong>{{ threadTitle }}</strong>
          <span class="os-thread__count">{{ threadMessages.length }} 条消息</span>
          <button class="os-thread__close" type="button" @click="closeThread">关闭</button>
        </div>
        <div class="os-thread__body">
          <p v-if="!threadMessages.length" class="os-md__empty">这段对话还没有消息。</p>
          <div
            v-for="message in threadMessages"
            :key="message.seq"
            class="os-thread__msg"
            :class="{ 'os-thread__msg--admin': message.direction === 'admin' }"
          >
            <div class="os-thread__meta">
              {{ message.direction === 'admin' ? message.authorName || '管理员' : '访客' }}
              <span class="os-muted">{{ fmtTime(message.sentAt) }}</span>
            </div>
            <div class="os-thread__text">{{ message.text }}</div>
          </div>
        </div>
      </div>
    </div>

    <VisitorDetailDrawer v-if="detail" :record="detail" @close="closeDetail" />
  </div>
</template>

<style lang="scss" scoped>
.os-md {
  padding: 1rem 1.5rem 2.5rem;

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 1rem;
    margin-bottom: 1rem;
  }

  .os-md__title {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 600;
    color: #111827;
  }

  &__version {
    margin-left: 0.5rem;
    padding: 0.0625rem 0.375rem;
    font-size: 0.75rem;
    font-weight: 500;
    color: #167a72;
    vertical-align: middle;
    background: #e8f9f7;
    border-radius: 0.25rem;
  }

  &__desc {
    max-width: 60rem;
    margin: 0.25rem 0 0;
    font-size: 0.8125rem;
    line-height: 1.6;
    color: #6b7280;
  }

  &__actions {
    display: flex;
    flex: none;
    gap: 0.5rem;
  }

  &__toggle {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
    color: #4b5563;
    cursor: pointer;
    background: #fff;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;

    &.is-on {
      color: #fff;
      background: #39c5bb;
      border-color: #39c5bb;
    }
  }

  &__stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
    gap: 0.75rem;
    margin-bottom: 1rem;
  }

  &__card {
    padding: 0.75rem 1rem 1rem;
    background: #fff;
    border-radius: 0.5rem;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);
  }

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

  &--info {
    color: #167a72;
    background: #e8f9f7;
    border-color: #b9e7e3;
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

  &__search {
    flex: 1 1 16rem;
    min-width: 12rem;
    padding: 0.375rem 0.625rem;
    font-size: 0.8125rem;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;
    outline: none;

    &:focus {
      border-color: #8addd6;
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
        background: #39c5bb;
      }
    }
  }

  &__refresh {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
    color: #39c5bb;
    cursor: pointer;
    background: #e8f9f7;
    border: 1px solid #b9e7e3;
    border-radius: 0.375rem;

    &:hover:not(:disabled) {
      background: #b9e7e3;
    }

    &:disabled {
      color: #9ca3af;
      cursor: not-allowed;
      background: #f9fafb;
      border-color: #e5e7eb;
    }

    &--danger {
      color: #b91c1c;
      background: #fef2f2;
      border-color: #fecaca;

      &:hover:not(:disabled) {
        background: #fee2e2;
      }
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
    vertical-align: middle;
    background: #fff;
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

  &__check {
    width: 2.5rem;
    padding-right: 0;
  }

  &__pick {
    cursor: pointer;

    &:hover td {
      background: #f9fafb;
    }

    &.is-selected td {
      background: #e8f9f7;
    }
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
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem;
    align-items: center;
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
  background: #39c5bb;
  border-radius: 9999px;
  box-shadow: 0 0 0 3px rgb(57 197 187 / 0.18);

  &--bot {
    background: #d1d5db;
    box-shadow: none;
  }
}

.os-device {
  color: #374151;
  white-space: nowrap;
}

.os-page-title {
  font-weight: 500;
  color: #111827;
}

.os-badge {
  display: inline-block;
  padding: 0 0.375rem;
  font-size: 0.6875rem;
  line-height: 1.5;
  color: #167a72;
  white-space: nowrap;
  background: #d8f4f1;
  border-radius: 0.25rem;

  &--bot {
    color: #4b5563;
    background: #f3f4f6;
  }
}

.os-thread-preview {
  max-width: 26rem;
  overflow: hidden;
  color: #374151;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.os-muted {
  color: #6b7280;
  font-variant-numeric: tabular-nums;
}

.os-pager {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  justify-content: flex-end;
  margin-top: 0.75rem;
  font-size: 0.8125rem;
  color: #6b7280;

  button {
    padding: 0.25rem 0.625rem;
    font-size: 0.8125rem;
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

  &__now {
    font-variant-numeric: tabular-nums;
  }

  &__jump {
    display: flex;
    flex: none;
    gap: 0.25rem;
    align-items: center;
    white-space: nowrap;

    input {
      flex: none;
      width: 4rem;
      padding: 0.25rem 0.375rem;
      font-size: 0.8125rem;
      border: 1px solid #e5e7eb;
      border-radius: 0.375rem;
      outline: none;

      &:focus {
        border-color: #8addd6;
      }
    }
  }
}

.os-thread {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(17 24 39 / 0.35);

  &__panel {
    display: flex;
    flex-direction: column;
    width: min(40rem, 92vw);
    max-height: 80vh;
    background: #fff;
    border-radius: 0.75rem;
    box-shadow: 0 20px 40px -12px rgb(0 0 0 / 0.35);
  }

  &__head {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid #f3f4f6;
  }

  &__count {
    font-size: 0.75rem;
    color: #9ca3af;
  }

  &__close {
    margin-left: auto;
    padding: 0.25rem 0.625rem;
    font-size: 0.8125rem;
    color: #4b5563;
    cursor: pointer;
    background: #fff;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;
  }

  &__body {
    padding: 0.75rem 1rem 1rem;
    overflow-y: auto;
  }

  &__msg {
    margin-bottom: 0.625rem;

    &--admin {
      text-align: right;
    }
  }

  &__meta {
    display: flex;
    gap: 0.5rem;
    justify-content: flex-start;
    margin-bottom: 0.125rem;
    font-size: 0.6875rem;
    color: #9ca3af;
  }

  &__msg--admin &__meta {
    justify-content: flex-end;
  }

  &__text {
    display: inline-block;
    padding: 0.375rem 0.625rem;
    font-size: 0.8125rem;
    color: #111827;
    white-space: pre-wrap;
    background: #f3f4f6;
    border-radius: 0.5rem;
  }

  &__msg--admin &__text {
    color: #167a72;
    background: #e8f9f7;
  }
}
</style>