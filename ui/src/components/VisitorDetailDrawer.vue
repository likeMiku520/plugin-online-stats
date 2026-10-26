<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import axios from 'axios'
import { applyLocation, type LocationView, type RecordView } from '../utils/visitor'

interface ChatMessage {
  seq: number
  direction: string
  text: string
  sentAt: number
  /** Who wrote it: the administrator, or the account a signed in visitor used. */
  authorName: string | null
  authorAvatar: string | null
  pageUrl: string | null
  pageTitle: string | null
  readByAdmin: boolean
}

interface AdminInfo {
  name: string
  displayName: string
  avatar: string
}

const props = defineProps<{ record: RecordView }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const api = '/apis/online-stats.halo.run/v1alpha1'

const detail = ref<RecordView>({ ...props.record })
const copied = ref('')
const geoLoading = ref(false)
const geoError = ref('')
const geoEndpoint = ref('')

const chatOpen = ref(false)
const chatMessages = ref<ChatMessage[]>([])
const chatDraft = ref('')
const chatSending = ref(false)
const chatError = ref('')
const chatLoaded = ref(false)
/** null until the first reply from the server, so nothing flashes before it is known. */
const chatEnabled = ref<boolean | null>(null)
const listRef = ref<HTMLElement | null>(null)
let scrollOnNextLoad = true
const admin = ref<AdminInfo | null>(null)
/** Picture the plugin is configured to send, when one was chosen. */
const chatAvatar = ref('')
/** Who the conversation is with: a signed in account, or an anonymous visitor. */
const peerName = ref('')
const peerAvatar = ref('')
const peerIp = ref('')

let chatTimer: number | undefined

/** A conversation belongs to the device behind the record, not to the address. */
const chatKey = computed(() => detail.value.name)
const canChat = computed(() => !!chatKey.value)

const adminLabel = computed(() => admin.value?.displayName || admin.value?.name || '当前管理员')
const peerLabel = computed(() => peerName.value || '访客')
/** Single character stand-in when the other side has no picture of its own. */
const peerInitial = computed(() => (peerName.value ? peerName.value.slice(0, 1) : '访'))

/**
 * What the visitor's popup will actually put next to the messages. Showing it here
 * saves a round of guessing when the account has no picture of its own.
 */
const avatarHint = computed(() => {
  if (chatAvatar.value) {
    return '访客看到的是设置里指定的那张头像。'
  }
  if (admin.value?.avatar) {
    return '访客看到的是当前账号的头像。'
  }
  return '当前账号还没有头像，访客那边显示名字首字；在个人资料里上传头像，或到插件设置里指定一张。'
})

watch(
  () => props.record,
  (next) => {
    if (!next) {
      return
    }
    // Realtime fields stay live while the drawer is open; the rest is a snapshot so a
    // location queried here is not overwritten by the page's refresh.
    detail.value.online = next.online
    detail.value.lastSeenAt = next.lastSeenAt
    detail.value.visitCount = next.visitCount
    detail.value.pageViewCount = next.pageViewCount
    detail.value.totalOnlineMs = next.totalOnlineMs
  },
)

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function fmtTime(ms: number | null | undefined): string {
  if (!ms) {
    return '—'
  }
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function fmtRelative(ms: number | null | undefined): string {
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

async function lookupLocation(force = false) {
  if (geoLoading.value) {
    return
  }
  if (!force && detail.value.geoResolvedAt) {
    return
  }
  geoLoading.value = true
  geoError.value = ''
  try {
    const { data } = await axios.get<LocationView>(`${api}/location`, {
      params: { name: detail.value.name },
    })
    geoEndpoint.value = data.endpoint || ''
    if (data.resolved) {
      applyLocation(detail.value, data)
    } else {
      geoError.value = data.error || '接口没有返回有效属地'
    }
  } catch (e: any) {
    geoError.value = e?.response?.status === 403 ? '没有权限调用查询接口' : '查询失败，请稍后重试'
  } finally {
    geoLoading.value = false
  }
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

async function loadAdmin() {
  try {
    // The console's own current-user endpoint, which is also what Halo's avatar
    // component reads: the picture lives in the account's spec.avatar.
    const { data } = await axios.get('/apis/api.console.halo.run/v1alpha1/users/-')
    const user = data?.user || data || {}
    const spec = user.spec || {}
    admin.value = {
      name: user.metadata?.name || data.name || '',
      displayName: spec.displayName || data.displayName || user.metadata?.name || '',
      avatar: spec.avatar || data.avatar || '',
    }
  } catch (e) {
    admin.value = null
  }
}

async function loadChat() {
  if (!canChat.value) {
    return
  }
  try {
    const { data } = await axios.get(`${api}/chat-messages`, {
      params: { key: chatKey.value },
    })
    chatMessages.value = data.messages || []
    chatAvatar.value = data.avatar || ''
    peerName.value = data.peerName || ''
    peerAvatar.value = data.peerAvatar || ''
    peerIp.value = data.ip || ''
    chatEnabled.value = data.enabled !== false
    chatLoaded.value = true
  } catch (e) {
    chatLoaded.value = true
  }
  const force = scrollOnNextLoad
  scrollOnNextLoad = false
  await scrollToLatest(force)
}

/** True while the list is close enough to the end that following it is not annoying. */
function isAtLatest(): boolean {
  const list = listRef.value
  if (!list) {
    return true
  }
  return list.scrollHeight - list.scrollTop - list.clientHeight < 48
}

/**
 * Puts the newest message in view. Opening the panel always jumps to the end; a poll
 * that arrives while the thread is being read leaves the scroll position alone.
 */
async function scrollToLatest(force: boolean) {
  if (!force && !isAtLatest()) {
    return
  }
  await nextTick()
  const list = listRef.value
  if (list) {
    list.scrollTop = list.scrollHeight
  }
}

function openChat() {
  chatOpen.value = true
  chatError.value = ''
  scrollOnNextLoad = true
  loadAdmin()
  loadChat()
}

function closeChat() {
  chatOpen.value = false
}

async function send() {
  const text = chatDraft.value.trim()
  if (!text || chatSending.value) {
    return
  }
  chatSending.value = true
  chatError.value = ''
  // The message just written is the newest one, so it has to be visible.
  scrollOnNextLoad = true
  try {
    await axios.post(`${api}/chat-send`, { key: chatKey.value, text })
    chatDraft.value = ''
    await loadChat()
  } catch (e: any) {
    chatError.value =
      e?.response?.status === 403 ? '没有权限发送消息' : '发送失败，请稍后重试'
  } finally {
    chatSending.value = false
  }
}

/**
 * Enter sends, Alt+Enter breaks the line. A keystroke that belongs to an input method
 * is left alone, otherwise picking a candidate with Enter would send half a sentence.
 */
function onDraftKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229) {
    return
  }
  if (event.altKey) {
    return
  }
  event.preventDefault()
  send()
}

onMounted(() => {
  if (canChat.value) {
    openChat()
  }
  chatTimer = window.setInterval(() => {
    if (chatOpen.value && chatEnabled.value !== false && !document.hidden) {
      loadChat()
    }
  }, 4_000)
})

onUnmounted(() => {
  if (chatTimer) {
    window.clearInterval(chatTimer)
  }
})
</script>

<template>
  <div class="os-drawer-mask" @click.self="emit('close')">
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
        <button class="os-drawer__close" type="button" @click="emit('close')">×</button>
      </header>

      <div class="os-drawer__body">
        <section v-if="canChat && chatEnabled === true" class="os-section os-chat">
          <h4>
            <span>给这位访客发消息</span>
            <button class="os-copy" type="button" @click="chatOpen ? closeChat() : openChat()">
              {{ chatOpen ? '收起' : '展开' }}
            </button>
          </h4>

          <div v-if="chatOpen" class="os-chat__panel">
            <div class="os-chat__me">
              <img v-if="peerAvatar" class="os-chat__avatar" :src="peerAvatar" alt="" />
              <span v-else class="os-chat__avatar os-chat__avatar--fallback">
                {{ peerInitial }}
              </span>
              <div class="os-chat__me-text">
                <strong>{{ peerLabel }}</strong>
                <span v-if="peerIp">（{{ peerIp }}）</span>
              </div>
            </div>
            <p class="os-chat__hint">{{ avatarHint }}</p>

            <div v-if="!chatLoaded" class="os-chat__empty">加载中…</div>
            <ul v-else-if="chatMessages.length" ref="listRef" class="os-chat__list">
              <li
                v-for="message in chatMessages"
                :key="message.seq"
                :class="['os-chat__item', message.direction === 'admin' ? 'is-mine' : 'is-theirs']"
              >
                <div class="os-chat__bubble">{{ message.text }}</div>
                <div class="os-chat__meta">
                  {{ message.direction === 'admin' ? message.authorName || adminLabel : peerLabel }} ·
                  {{ fmtRelative(message.sentAt) }}
                  <span v-if="message.direction === 'visitor' && message.pageTitle">
                    · 在「{{ message.pageTitle }}」
                  </span>
                </div>
              </li>
            </ul>
            <div v-else class="os-chat__empty">
              还没有对话。发送后，对方页面会弹出对话卡片，他可以直接回复你。
            </div>

            <textarea
              v-model="chatDraft"
              class="os-chat__input"
              rows="3"
              maxlength="1000"
              placeholder="输入要发送的文本内容，Enter 发送，Alt + Enter 换行"
              @keydown="onDraftKeydown"
            />
            <div class="os-chat__actions">
              <span v-if="chatError" class="os-chat__error">{{ chatError }}</span>
              <span v-else class="os-chat__hint">仅支持纯文本，最多 1000 字</span>
              <button
                class="os-geo-btn"
                type="button"
                :disabled="chatSending || !chatDraft.trim()"
                @click="send()"
              >
                {{ chatSending ? '发送中…' : '发送' }}
              </button>
            </div>
            <p class="os-chat__notice">提示：此功能仅为学习测试使用。</p>
          </div>
          <p v-else class="os-chat__closed">点击「展开」可以和这个访客实时对话。</p>
        </section>

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
            <button
              class="os-geo-btn"
              type="button"
              :disabled="geoLoading"
              @click="lookupLocation()"
            >
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
              <button
                v-if="detail.ip && !detail.geoResolvedAt"
                class="os-copy"
                type="button"
                :disabled="geoLoading"
                @click="lookupLocation()"
              >
                {{ geoLoading ? '查询中…' : '查属地' }}
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
</template>

<style lang="scss" scoped>
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

.os-muted {
  margin-top: 0.125rem;
  font-size: 0.6875rem;
  color: #9ca3af;
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

  &--bot {
    color: #6b7280;
    background: #e5e7eb;
  }
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

.os-chat {
  h4 {
    justify-content: space-between;
  }

  &__panel {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  &__me {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    padding: 0.5rem 0.625rem;
    background: #F2FBFA;
    border: 1px solid #D8F4F1;
    border-radius: 0.5rem;
  }

  &__avatar {
    flex: none;
    width: 2rem;
    height: 2rem;
    object-fit: cover;
    border-radius: 9999px;

    &--fallback {
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.8125rem;
      font-weight: 600;
      color: #fff;
      background: #39C5BB;
    }
  }

  &__me-text {
    display: flex;
    flex-direction: column;
    min-width: 0;

    strong {
      font-size: 0.8125rem;
      color: #111827;
    }

    span {
      font-size: 0.6875rem;
      line-height: 1.5;
      color: #6b7280;
    }
  }

  &__hint {
    margin: 0 0 0.25rem;
    font-size: 0.6875rem;
    line-height: 1.6;
    color: #9ca3af;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    max-height: 16rem;
    padding: 0;
    margin: 0;
    overflow-y: auto;
    list-style: none;
  }

  &__item {
    display: flex;
    flex-direction: column;
    gap: 0.125rem;
    max-width: 90%;

    &.is-mine {
      align-self: flex-end;
      align-items: flex-end;
    }

    &.is-theirs {
      align-self: flex-start;
    }
  }

  &__bubble {
    padding: 0.375rem 0.625rem;
    font-size: 0.8125rem;
    line-height: 1.6;
    color: #111827;
    white-space: pre-wrap;
    word-break: break-word;
    background: #f3f4f6;
    border-radius: 0.5rem;
  }

  &__item.is-mine &__bubble {
    color: #fff;
    background: #39C5BB;
  }

  &__meta {
    font-size: 0.625rem;
    color: #9ca3af;
  }

  &__empty {
    padding: 0.75rem;
    font-size: 0.75rem;
    color: #9ca3af;
    text-align: center;
    background: #f9fafb;
    border-radius: 0.5rem;
  }

  &__input {
    width: 100%;
    padding: 0.5rem 0.625rem;
    font-family: inherit;
    font-size: 0.8125rem;
    line-height: 1.6;
    color: #111827;
    resize: vertical;
    border: 1px solid #e5e7eb;
    border-radius: 0.375rem;
    outline: none;

    &:focus {
      border-color: #8ADDD6;
    }
  }

  &__actions {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    justify-content: space-between;
  }

  &__hint {
    font-size: 0.6875rem;
    color: #9ca3af;
  }

  &__error {
    font-size: 0.6875rem;
    color: #b91c1c;
  }

  &__closed {
    margin: 0;
    font-size: 0.75rem;
    color: #9ca3af;
  }

  &__notice {
    margin: 0;
    font-size: 0.6875rem;
    line-height: 1.6;
    color: #9ca3af;
  }
}

@media (max-width: 768px) {
  .os-drawer {
    width: 100%;
  }
}
</style>