import { flushPromises, mount } from '@vue/test-utils'
import axios from 'axios'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RecordView } from '../../utils/visitor'
import VisitorDetailDrawer from '../VisitorDetailDrawer.vue'

vi.mock('axios')

const mocked = vi.mocked(axios, true)

let scrollTop = 0

// jsdom has no layout, so every element reports the same scroll geometry and the
// scroll offset is recorded, which is enough to assert where the panel positions
// itself.
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollHeight', {
    configurable: true,
    get: () => 600,
  })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get: () => 200,
  })
  Object.defineProperty(HTMLElement.prototype, 'scrollTop', {
    configurable: true,
    get: () => scrollTop,
    set: (value: number) => {
      scrollTop = value
    },
  })
})

function record(): RecordView {
  return {
    name: 'v-1',
    ip: '10.0.0.9',
    location: null,
    country: null,
    countryCode: null,
    region: null,
    city: null,
    districts: null,
    isp: null,
    net: null,
    idc: null,
    zipcode: null,
    areacode: null,
    protocol: null,
    dec: null,
    geoResolvedAt: null,
    userAgent: 'Mozilla/5.0',
    ua: { os: 'Windows', browser: 'Chrome' },
    bot: false,
    botReason: null,
    firstPageUrl: '/a',
    firstPageTitle: 'A',
    pageUrl: '/b',
    pageTitle: 'B',
    referer: null,
    firstReferer: null,
    firstSeenAt: 1,
    lastSeenAt: 2,
    visitCount: 1,
    pageViewCount: 1,
    totalOnlineMs: 1000,
    screen: '1920x1080',
    viewport: '1280x800',
    timezone: 'Asia/Shanghai',
    language: 'zh-CN',
    platform: 'Win32',
    cpuCores: '8',
    deviceMemory: '8GB',
    connection: null,
    online: true,
  }
}

function chatMessage(seq: number, direction: string, text: string, authorName: string | null = 'fufu大王') {
  return {
    seq,
    direction,
    text,
    sentAt: Date.now(),
    authorName,
    authorAvatar: 'https://blog.fufu520.cn/upload/fufu_touming.webp',
    visitorKey: null,
    pageUrl: null,
    pageTitle: null,
    readByAdmin: true,
  }
}

beforeEach(() => {
  // The project does not configure clearMocks, so each test starts from a clean slate
  // by hand; otherwise "was called" assertions see the previous test's requests.
  vi.clearAllMocks()
  scrollTop = 0
  mocked.get.mockImplementation((url: string) => {
    if (url.includes('/chat-messages')) {
      return Promise.resolve({
        data: {
          visitorKey: 'v-1',
          ip: '10.0.0.9',
          enabled: true,
          peerName: '小明',
          peerAvatar: 'https://blog.fufu520.cn/upload/xiaoming.webp',
          // No configured picture, so the visitor sees whatever the account has.
          avatar: null,
          messages: [
            chatMessage(1, 'admin', '你好'),
            chatMessage(2, 'visitor', '在的', '小明'),
            chatMessage(3, 'admin', '看到你在看这篇博客'),
          ],
        },
      })
    }
    if (url.includes('api.console.halo.run')) {
      return Promise.resolve({
        data: {
          user: {
            metadata: { name: 'fufu' },
            spec: {
              displayName: 'fufu大王',
              avatar: 'https://blog.fufu520.cn/upload/fufu-account.webp',
            },
          },
        },
      })
    }
    return Promise.resolve({ data: {} })
  })
  mocked.post.mockResolvedValue({ data: { ok: true, seq: 4 } })
})

describe('the console conversation panel', () => {
  it('heads the panel with the signed in visitor and their address', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const block = wrapper.find('.os-chat__me-text')
    expect(block.text()).toContain('小明')
    expect(block.text()).toContain('10.0.0.9')
    expect(wrapper.find('img.os-chat__avatar').attributes('src')).toBe(
      'https://blog.fufu520.cn/upload/xiaoming.webp',
    )
    expect(block.text()).not.toContain('对方看到的是')
  })

  it('heads the panel with 访客 and a single character when nobody is signed in', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            ip: '10.0.0.9',
            enabled: true,
            peerName: null,
            peerAvatar: null,
            avatar: null,
            messages: [chatMessage(2, 'visitor', '在的', null)],
          },
        })
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const block = wrapper.find('.os-chat__me-text')
    expect(block.text()).toContain('访客')
    expect(block.text()).toContain('10.0.0.9')
    expect(wrapper.find('img.os-chat__avatar').exists()).toBe(false)
    expect(wrapper.find('.os-chat__avatar--fallback').text()).toBe('访')
  })

  it('names each administrator on their own messages', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            ip: '10.0.0.9',
            enabled: true,
            peerName: null,
            peerAvatar: null,
            avatar: null,
            messages: [
              chatMessage(1, 'admin', '第一条', '甲管理员'),
              chatMessage(2, 'admin', '第二条', '乙管理员'),
            ],
          },
        })
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const meta = wrapper.findAll('.os-chat__meta').map((node) => node.text())
    expect(meta[0]).toContain('甲管理员')
    expect(meta[1]).toContain('乙管理员')
  })

  it('opens on the newest message rather than the top of the thread', async () => {
    mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(scrollTop).toBe(600)
  })

  it('carries no standing avatar explanation in the panel', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(wrapper.find('.os-chat').text()).not.toContain('访客看到的是')
  })

  it('shows the author picture beside each administrator message', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            ip: '10.0.0.9',
            enabled: true,
            peerName: null,
            peerAvatar: null,
            messages: [
              chatMessage(1, 'admin', '第一条', '甲管理员'),
              {
                ...chatMessage(2, 'admin', '第二条', '乙管理员'),
                authorAvatar: 'https://blog.fufu520.cn/upload/b.webp',
              },
              {
                ...chatMessage(3, 'admin', '第三条', '丙管理员'),
                authorAvatar: null,
              },
            ],
          },
        })
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const authors = wrapper.findAll('.os-chat__author')
    expect(authors.length).toBe(3)
    expect(authors[1].attributes('src')).toBe('https://blog.fufu520.cn/upload/b.webp')
    // An account without a picture falls back to the first character of its name.
    expect(authors[2].text()).toBe('丙')
    // Their own messages are the right hand column, and the author is the last child
    // so a plain row places the picture to the right of the message.
    const item = authors[0].element.closest('.os-chat__item')
    expect(item?.classList).toContain('is-mine')
    expect(item?.lastElementChild?.classList).toContain('os-chat__author')
    expect(item?.firstElementChild?.classList).toContain('os-chat__body')
  })

  it('sends on Enter and breaks the line only with the button', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    await wrapper.find('.os-chat__input').setValue('看到你在看这篇博客')
    const breakButton = wrapper.findAll('.os-chat__actions .os-geo-btn')[0]
    expect(breakButton.text()).toBe('换行')

    await breakButton.trigger('click')
    await flushPromises()
    expect(mocked.post).not.toHaveBeenCalled()
    expect((wrapper.find('.os-chat__input').element as HTMLTextAreaElement).value).toBe(
      '看到你在看这篇博客\n',
    )

    // Enter always sends, whatever modifier is held. The draft is trimmed on the way
    // out, so the trailing newline does not reach the server.
    await wrapper.find('.os-chat__input').trigger('keydown', { key: 'Enter', altKey: true })
    await flushPromises()
    expect(mocked.post).toHaveBeenCalledWith(
      expect.stringContaining('/chat-send'),
      expect.objectContaining({ text: '看到你在看这篇博客' }),
    )
  })

  it('has no placeholder in the composer', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(wrapper.find('.os-chat__input').attributes('placeholder')).toBeUndefined()
  })

  it('leaves Enter alone while an input method is composing', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    await wrapper.find('.os-chat__input').setValue('正在输入')
    const input = wrapper.find('.os-chat__input').element as HTMLTextAreaElement
    const composing = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
    Object.defineProperty(composing, 'isComposing', { value: true })
    input.dispatchEvent(composing)
    await flushPromises()

    expect(mocked.post).not.toHaveBeenCalled()
  })

  it('lists the pages the visitor opened with how long they stayed', async () => {
    const visited = {
      ...record(),
      pageVisits: [
        {
          url: '/archives/1',
          title: '第一篇',
          enteredAt: 1_760_000_000_000,
          leftAt: 1_760_000_120_000,
          durationMs: 120_000,
        },
        {
          url: '/archives/2',
          title: '第二篇',
          enteredAt: 1_760_000_120_000,
          leftAt: null,
          durationMs: 30_000,
        },
      ],
      currentPageMs: 30_000,
    }
    const wrapper = mount(VisitorDetailDrawer, { props: { record: visited } })
    await flushPromises()

    expect(wrapper.find('.os-visits').exists()).toBe(false)
    const toggle = wrapper.findAll('.os-link')[0]
    expect(toggle.text()).toBe('展开明细')
    await toggle.trigger('click')

    const rows = wrapper.findAll('.os-visits li')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('第一篇')
    expect(rows[0].text()).toContain('2 分')
    expect(rows[0].text()).toContain('离开')
    expect(rows[1].text()).toContain('还在这页')
  })

  it('shows what the visitor is doing right now', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const sections = wrapper.findAll('.os-section h4').map((node) => node.text())
    expect(sections).toContain('当前在做什么')
  })

  it('marks only the messages sent from here, unread until the visitor opens them', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            enabled: true,
            peerName: null,
            peerAvatar: null,
            messages: [
              { ...chatMessage(1, 'admin', '看过了'), seenByVisitor: true },
              { ...chatMessage(2, 'admin', '还没看'), seenByVisitor: false },
              { ...chatMessage(3, 'visitor', '在的', null), readByAdmin: true },
            ],
          },
        })
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const states = wrapper.findAll('.os-chat__read')
    expect(states.length).toBe(2)
    expect(states[0].text()).toContain('已读')
    expect(states[0].classes()).toContain('is-read')
    expect(states[1].text()).toContain('未读')
    expect(states[1].classes()).toContain('is-unread')
    // The visitor's own replies carry no marker here.
    expect(wrapper.find('.os-chat__list').text()).toContain('在的')
    expect(wrapper.findAll('.os-chat__read').length).toBe(2)
  })

  it('no longer shows fields browsers cap or withhold', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const labels = wrapper.findAll('.os-kv dt').map((node) => node.text())
    expect(labels).not.toContain('CPU 核心')
    expect(labels).not.toContain('设备内存')
    expect(labels).not.toContain('网络类型')
    // The ones that are accurate stay.
    expect(labels).toContain('时区')
    expect(labels).toContain('屏幕分辨率')
  })

  it('scrolls back to the end after sending', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    scrollTop = 0
    await wrapper.find('.os-chat__input').setValue('看到你在看这篇博客')
    await wrapper.find('.os-chat__send').trigger('click')
    await flushPromises()

    expect(mocked.post).toHaveBeenCalledWith(
      expect.stringContaining('/chat-send'),
      expect.objectContaining({ text: '看到你在看这篇博客' }),
    )
    expect(scrollTop).toBe(600)
  })

  it('shows the learning-only notice and the switch state', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(wrapper.find('.os-chat__notice').text()).toContain('此功能仅为学习测试使用')
    expect(wrapper.find('.os-chat__input').attributes('disabled')).toBeUndefined()
  })

  it('addresses the conversation by device, so an address is not required', async () => {
    const anonymous = { ...record(), ip: null }
    const wrapper = mount(VisitorDetailDrawer, { props: { record: anonymous } })
    await flushPromises()

    const chatCall = mocked.get.mock.calls.find((call) => String(call[0]).includes('/chat-messages'))
    expect(chatCall?.[1]?.params?.key).toBe('v-1')
    expect(wrapper.find('.os-chat').exists()).toBe(true)
  })

  it('labels the visitor side with the peer name from the server', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            ip: '10.0.0.9',
            enabled: true,
            avatar: '',
            peerName: '小明',
            peerAvatar: '',
            messages: [
              chatMessage(1, 'admin', '你好'),
              chatMessage(2, 'visitor', '在的', '小明'),
            ],
          },
        })
      }
      return Promise.resolve({
        data: { user: { metadata: { name: 'fufu' }, spec: { displayName: 'fufu大王' } } },
      })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const meta = wrapper.findAll('.os-chat__meta').map((node) => node.text())
    expect(meta.some((line) => line.includes('小明'))).toBe(true)
    expect(meta.every((line) => !line.startsWith('访客 ·'))).toBe(true)
  })

  it('hides the whole conversation section once the plugin setting turns it off', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: { ip: '10.0.0.9', enabled: false, avatar: '', messages: [] },
        })
      }
      return Promise.resolve({ data: { name: 'fufu', displayName: 'fufu大王' } })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(wrapper.find('.os-chat').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('给这位访客发消息')
    expect(wrapper.text()).not.toContain('此功能仅为学习测试使用')
    // The rest of the drawer still works.
    expect(wrapper.find('.os-drawer__title').exists()).toBe(true)
  })
})