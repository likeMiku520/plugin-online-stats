import { flushPromises, mount } from '@vue/test-utils'
import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RecordView } from '../../utils/visitor'
import OnlineStatsPage from '../OnlineStatsPage.vue'

vi.mock('axios')

const mocked = vi.mocked(axios, true)

function record(): RecordView {
  return {
    name: 'v-1',
    ip: '10.0.0.9',
    location: '中国 江苏 南京',
    country: '中国',
    countryCode: 'CN',
    region: '江苏省',
    city: '南京市',
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

function conversations(unread: number) {
  return {
    data: {
      loaded: true,
      enabled: true,
      unreadTotal: unread,
      items: unread
        ? [
            {
              visitorKey: 'v-1',
              ip: '10.0.0.9',
              unread,
              total: 3,
              lastText: '在的',
              lastDirection: 'visitor',
              lastAt: Date.now(),
            },
          ]
        : [],
    },
  }
}

beforeEach(() => {
  mocked.get.mockImplementation((url: string) => {
    if (url.includes('/records')) {
      return Promise.resolve({
        data: {
          items: [record()],
          total: 1,
          stats: { online: 1, total: 1, todayNew: 1, loaded: true },
        },
      })
    }
    if (url.includes('/chat-conversations')) {
      return Promise.resolve(conversations(2))
    }
    return Promise.resolve({ data: {} })
  })
})

describe('the visitor list', () => {
  it('reminds the reader about unread visitor messages', async () => {
    const wrapper = mount(OnlineStatsPage)
    await flushPromises()

    expect(wrapper.find('.os-unread').exists()).toBe(true)
    expect(wrapper.find('.os-unread').text()).toContain('2 条未读访客消息')
    expect(wrapper.find('.os-unread').text()).toContain('10.0.0.9')
  })

  it('marks the row the unread reply belongs to, by device rather than by address', async () => {
    const wrapper = mount(OnlineStatsPage)
    await flushPromises()

    expect(wrapper.find('.os-more').classes()).toContain('os-more--unread')
    expect(wrapper.find('.os-more__badge').text()).toBe('2')
  })

  it('stays quiet when everything has been read', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/records')) {
        return Promise.resolve({
          data: {
            items: [record()],
            total: 1,
            stats: { online: 1, total: 1, todayNew: 1, loaded: true },
          },
        })
      }
      if (url.includes('/chat-conversations')) {
        return Promise.resolve(conversations(0))
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(OnlineStatsPage)
    await flushPromises()

    expect(wrapper.find('.os-unread').exists()).toBe(false)
    expect(wrapper.find('.os-more').classes()).not.toContain('os-more--unread')
  })

  it('jumps straight to a typed page number', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/records')) {
        return Promise.resolve({
          data: {
            items: [record()],
            total: 100,
            stats: { online: 1, total: 100, todayNew: 1, loaded: true },
          },
        })
      }
      if (url.includes('/chat-conversations')) {
        return Promise.resolve(conversations(0))
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(OnlineStatsPage)
    await flushPromises()
    expect(wrapper.find('.os-pager__info').text()).toContain('第 1/5 页')

    await wrapper.find('.os-pager__input').setValue('4')
    await wrapper.find('.os-pager__jump button').trigger('click')
    await flushPromises()

    const last = mocked.get.mock.calls.filter((call) => String(call[0]).includes('/records')).pop()
    expect(last?.[1]?.params?.page).toBe(4)
    expect(wrapper.find('.os-pager__info').text()).toContain('第 4/5 页')
  })

  it('clamps a typed page number to the pages that exist', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/records')) {
        return Promise.resolve({
          data: {
            items: [record()],
            total: 100,
            stats: { online: 1, total: 100, todayNew: 1, loaded: true },
          },
        })
      }
      if (url.includes('/chat-conversations')) {
        return Promise.resolve(conversations(0))
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = mount(OnlineStatsPage)
    await flushPromises()

    await wrapper.find('.os-pager__input').setValue('99')
    await wrapper.find('.os-pager__jump button').trigger('click')
    await flushPromises()

    const last = mocked.get.mock.calls.filter((call) => String(call[0]).includes('/records')).pop()
    expect(last?.[1]?.params?.page).toBe(5)
  })
})