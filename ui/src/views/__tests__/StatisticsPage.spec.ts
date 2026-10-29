import { flushPromises, mount } from '@vue/test-utils'
import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import StatisticsPage from '../StatisticsPage.vue'

vi.mock('axios')

const mocked = vi.mocked(axios, true)

function payload() {
  return {
    overview: {
      views: 10,
      visitors: 4,
      uniqueVisitors: 4,
      officialViews: 12,
      officialViewsRaw: 12,
      officialViewsAdjustment: 0,
      adjustmentApplied: 0,
      adjustmentError: null,
      totalOnlineMs: 60000,
      todayViews: 2,
      todayVisitors: 1,
      online: 1,
      articles: 2,
      trackedArticles: 2,
      days: 3,
      today: '2026-10-29',
      loaded: true,
      scope: 'all',
    },
    trend: { days: 30, series: [], daily: [] },
    articles: { sort: 'views', items: [] },
    sources: { dimension: 'browser', items: [], totalViews: 0 },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocked.get.mockImplementation((url: string) => {
    if (url.includes('/statistics/overview')) {
      return Promise.resolve({ data: payload().overview })
    }
    if (url.includes('/statistics/trend')) {
      return Promise.resolve({ data: payload().trend })
    }
    if (url.includes('/statistics/articles')) {
      return Promise.resolve({ data: payload().articles })
    }
    return Promise.resolve({ data: payload().sources })
  })
})

describe('the statistics page', () => {
  it('offers a human and bot view next to the refresh button', async () => {
    const wrapper = mount(StatisticsPage)
    await flushPromises()

    const labels = wrapper.findAll('.os-scope button').map((node) => node.text())
    expect(labels).toEqual(['全部', '真人', '机器人'])
    expect(wrapper.find('.os-scope button.is-active').text()).toBe('全部')
  })

  it('reloads every figure with the chosen view', async () => {
    const wrapper = mount(StatisticsPage)
    await flushPromises()

    const botButton = wrapper.findAll('.os-scope button')[2]
    await botButton.trigger('click')
    await flushPromises()

    const overview = mocked.get.mock.calls.filter((call) =>
      String(call[0]).includes('/statistics/overview'),
    )
    expect(overview.at(-1)?.[1]?.params?.type).toBe('bot')

    const sources = mocked.get.mock.calls.filter((call) =>
      String(call[0]).includes('/statistics/sources'),
    )
    // The three source lists are refetched together, so the last three calls carry it.
    expect(sources.slice(-3).every((call) => call[1]?.params?.type === 'bot')).toBe(true)
    expect(wrapper.find('.os-scope button.is-active').text()).toBe('机器人')
  })

  it('keeps the filter when the trend window changes', async () => {
    const wrapper = mount(StatisticsPage)
    await flushPromises()

    await wrapper.findAll('.os-scope button')[1].trigger('click')
    await flushPromises()

    const trendCalls = mocked.get.mock.calls.filter((call) =>
      String(call[0]).includes('/statistics/trend'),
    )
    expect(trendCalls.at(-1)?.[1]?.params?.type).toBe('human')
  })
})