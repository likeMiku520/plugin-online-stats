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
  scrollTop = 0
  mocked.get.mockImplementation((url: string) => {
    if (url.includes('/chat-messages')) {
      return Promise.resolve({
        data: {
          ip: '10.0.0.9',
          enabled: true,
          // No configured picture, so the panel previews the account's own avatar.
          avatar: null,
          messages: [
            chatMessage(1, 'admin', '你好'),
            chatMessage(2, 'visitor', '在的', null),
            chatMessage(3, 'admin', '看到你在看这篇博客'),
          ],
        },
      })
    }
    if (url.includes('uc.api.halo.run')) {
      return Promise.resolve({
        data: {
          name: 'fufu',
          displayName: 'fufu大王',
          avatar: 'https://blog.fufu520.cn/upload/fufu-account.webp',
        },
      })
    }
    return Promise.resolve({ data: {} })
  })
  mocked.post.mockResolvedValue({ data: { ok: true, seq: 4 } })
})

describe('the console conversation panel', () => {
  it('shows the signed in administrator by name and nothing more', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const block = wrapper.find('.os-chat__me-text')
    expect(block.text()).toContain('fufu大王')
    expect(block.text()).not.toContain('对方看到的是')
    expect(block.element.children.length).toBe(1)
  })

  it('opens on the newest message rather than the top of the thread', async () => {
    mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(scrollTop).toBe(600)
  })

  it('previews the account avatar when no picture is configured', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(wrapper.find('img.os-chat__avatar').attributes('src')).toBe(
      'https://blog.fufu520.cn/upload/fufu-account.webp',
    )
  })

  it('prefers a configured picture over the account avatar', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            enabled: true,
            avatar: 'https://blog.fufu520.cn/upload/one-for-everyone.webp',
            messages: [chatMessage(1, 'admin', '你好')],
          },
        })
      }
      return Promise.resolve({
        data: {
          name: 'fufu',
          displayName: 'fufu大王',
          avatar: 'https://blog.fufu520.cn/upload/fufu-account.webp',
        },
      })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(wrapper.find('img.os-chat__avatar').attributes('src')).toBe(
      'https://blog.fufu520.cn/upload/one-for-everyone.webp',
    )
  })

  it('scrolls back to the end after sending', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    scrollTop = 0
    await wrapper.find('.os-chat__input').setValue('看到你在看这篇博客')
    await wrapper.find('.os-chat .os-geo-btn').trigger('click')
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

  it('names a signed in visitor instead of calling them a visitor', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/chat-messages')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            enabled: true,
            avatar: '',
            messages: [
              chatMessage(1, 'admin', '你好'),
              chatMessage(2, 'visitor', '在的', '小明'),
            ],
          },
        })
      }
      return Promise.resolve({
        data: { name: 'fufu', displayName: 'fufu大王', avatar: '' },
      })
    })

    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const meta = wrapper.findAll('.os-chat__meta').map((node) => node.text())
    expect(meta.some((line) => line.includes('小明'))).toBe(true)
    expect(meta.some((line) => line.includes('访客'))).toBe(false)
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