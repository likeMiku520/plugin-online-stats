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

function chatMessage(seq: number, direction: string, text: string) {
  return {
    seq,
    direction,
    text,
    sentAt: Date.now(),
    adminName: 'fufu大王',
    adminAvatar: 'https://blog.fufu520.cn/upload/fufu_touming.webp',
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
          avatar: 'https://blog.fufu520.cn/upload/fufu_touming.webp',
          messages: [
            chatMessage(1, 'admin', '你好'),
            chatMessage(2, 'visitor', '在的'),
            chatMessage(3, 'admin', '看到你在看这篇博客'),
          ],
        },
      })
    }
    if (url.includes('uc.api.halo.run')) {
      return Promise.resolve({
        data: { name: 'fufu', displayName: 'fufu大王', avatar: '' },
      })
    }
    return Promise.resolve({ data: {} })
  })
  mocked.post.mockResolvedValue({ data: { ok: true, seq: 4 } })
})

describe('the console conversation panel', () => {
  it('greets the reader with the signed in administrator', async () => {
    const wrapper = mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    const text = wrapper.find('.os-chat__me-text').text()
    expect(text).toContain('fufu大王')
    expect(text).toContain('对方看到的是 fufu大王 的头像和名字')
  })

  it('opens on the newest message rather than the top of the thread', async () => {
    mount(VisitorDetailDrawer, { props: { record: record() } })
    await flushPromises()

    expect(scrollTop).toBe(600)
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

  it('disables sending once the plugin setting turns the feature off', async () => {
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

    expect(wrapper.find('.os-chat__notice').text()).toContain('即时通讯已在插件设置里关闭')
    expect(wrapper.find('.os-chat__input').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.os-chat .os-geo-btn').attributes('disabled')).toBeDefined()
  })
})