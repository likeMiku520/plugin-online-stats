import { flushPromises, mount } from '@vue/test-utils'
import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MaintenancePage from '../MaintenancePage.vue'

vi.mock('axios')
vi.mock('../../components/VisitorDetailDrawer.vue', () => ({
  default: { name: 'VisitorDetailDrawer', props: ['record'], template: '<div class="drawer" />' },
}))

const mocked = vi.mocked(axios, true)

function visitor(overrides: Record<string, unknown> = {}) {
  return {
    visitorKey: 'v-1',
    ip: '10.0.0.9',
    location: '中国 江苏 南京',
    pageTitle: '首页',
    userAgent: 'Mozilla/5.0',
    bot: false,
    firstSeenAt: 1_000,
    lastSeenAt: 9_000,
    visitCount: 3,
    pageViewCount: 7,
    ...overrides,
  }
}

function conversation(overrides: Record<string, unknown> = {}) {
  return {
    visitorKey: 'v-1',
    ip: '10.0.0.9',
    location: '中国 江苏 南京',
    bot: false,
    total: 4,
    lastText: '你好呀',
    lastAt: 8_000,
    firstSeenAt: 1_000,
    lastSeenAt: 8_000,
    visitCount: 2,
    ...overrides,
  }
}

function paged(items: unknown[], extra: Record<string, unknown> = {}) {
  return { data: { items, total: items.length, page: 1, size: 20, totalPages: 1, ...extra } }
}

function stub(items: unknown[] = [visitor()], summary = {}) {
  mocked.get.mockImplementation((url: string) => {
    if (url.includes('/maintenance/summary')) {
      return Promise.resolve({
        data: { visitors: 12, humans: 9, bots: 3, conversations: 4, messages: 30, ...summary },
      })
    }
    if (url.includes('/maintenance/conversations')) {
      return Promise.resolve(paged([]))
    }
    return Promise.resolve(paged(items))
  })
}

function buttonFor(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((node) => node.text().includes(text))
}

describe('the data management page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(window, 'open').mockImplementation(() => null)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('shows the counts above the table', async () => {
    stub()
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('访客总数')
    expect(text).toContain('12')
    expect(text).toContain('9')
    expect(text).toContain('30')
  })

  it('lists the visitors it is given', async () => {
    stub()
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    expect(wrapper.text()).toContain('10.0.0.9')
    expect(wrapper.text()).toContain('真人')
  })

  it('refreshes on its own so the numbers stay current', async () => {
    vi.useFakeTimers()
    stub()
    mount(MaintenancePage)
    await flushPromises()
    mocked.get.mockClear()

    vi.advanceTimersByTime(5_100)
    await flushPromises()

    expect(mocked.get).toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('opens the detail of a visitor that is clicked', async () => {
    stub()
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/maintenance/summary')) {
        return Promise.resolve({ data: { visitors: 1, humans: 1, bots: 0, conversations: 0, messages: 0 } })
      }
      if (url.includes('/records/')) {
        return Promise.resolve({ data: { ...visitor(), name: 'v-1' } })
      }
      return Promise.resolve(paged([visitor()]))
    })
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await wrapper.find('.os-table__pick').trigger('click')
    await flushPromises()

    expect(mocked.get).toHaveBeenCalledWith(expect.stringContaining('/records/v-1'))
    expect(wrapper.find('.drawer').exists()).toBe(true)
  })

  it('does not tick a visitor until multi-select is turned on', async () => {
    stub()
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    expect(wrapper.find('.os-table__check').exists()).toBe(false)

    await buttonFor(wrapper, '开启多选')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.os-table__check').exists()).toBe(true)
    expect(buttonFor(wrapper, '退出多选')).toBeTruthy()
  })

  it('selects several visitors for a batch clear', async () => {
    stub([visitor(), visitor({ visitorKey: 'v-2', ip: '10.0.0.10' })])
    mocked.post.mockResolvedValue({ data: { ok: true, visitors: 2, messages: 0 } })
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await buttonFor(wrapper, '开启多选')!.trigger('click')
    await flushPromises()

    const boxes = wrapper.findAll('.os-table__check input')
    await boxes[1].setValue(true)
    await boxes[2].setValue(true)
    await flushPromises()

    await buttonFor(wrapper, '清除选中的数据')!.trigger('click')
    await flushPromises()

    expect(mocked.post).toHaveBeenCalledWith(
      expect.stringContaining('/maintenance/clear'),
      { what: 'visitors', all: false, keys: ['v-1', 'v-2'] },
    )
  })

  it('clears everything only after asking', async () => {
    stub()
    mocked.post.mockResolvedValue({ data: { ok: true, visitors: 5, messages: 2 } })
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await buttonFor(wrapper, '清除全部数据')!.trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalled()
    expect(mocked.post).toHaveBeenCalledWith(
      expect.stringContaining('/maintenance/clear'),
      { what: 'visitors', all: true, keys: [] },
    )
  })

  it('does nothing when the confirmation is declined', async () => {
    stub()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await buttonFor(wrapper, '清除全部数据')!.trigger('click')
    await flushPromises()

    expect(mocked.post).not.toHaveBeenCalled()
  })

  it('exports with the current filter', async () => {
    stub()
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await buttonFor(wrapper, '机器人')!.trigger('click')
    await flushPromises()
    await buttonFor(wrapper, '导出当前筛选')!.trigger('click')

    expect(window.open).toHaveBeenCalledWith(
      expect.stringContaining('type=bot'),
      '_blank',
    )
  })

  it('jumps straight to the last page', async () => {
    mocked.get.mockResolvedValue(paged([visitor()], { total: 45, totalPages: 3 }))
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await buttonFor(wrapper, '末页')!.trigger('click')
    await flushPromises()

    expect(mocked.get).toHaveBeenLastCalledWith(
      expect.any(String),
      expect.objectContaining({ params: expect.objectContaining({ page: 3 }) }),
    )
  })

  it('shows the whole conversation of one visitor', async () => {
    mocked.get.mockImplementation((url: string) => {
      if (url.includes('/maintenance/summary')) {
        return Promise.resolve({ data: { visitors: 1, humans: 1, bots: 0, conversations: 1, messages: 2 } })
      }
      if (url.includes('/maintenance/thread')) {
        return Promise.resolve({
          data: {
            visitorKey: 'v-1',
            ip: '10.0.0.9',
            items: [
              { seq: 1, direction: 'admin', text: '你好', sentAt: 1_000, authorName: '管理员' },
              { seq: 2, direction: 'visitor', text: '你也好', sentAt: 2_000, authorName: null },
            ],
          },
        })
      }
      if (url.includes('/maintenance/conversations')) {
        return Promise.resolve(paged([conversation()]))
      }
      return Promise.resolve(paged([]))
    })
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    await buttonFor(wrapper, '聊天记录')!.trigger('click')
    await flushPromises()
    await wrapper.find('.os-table__pick').trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('你好')
    expect(text).toContain('你也好')
    expect(text).toContain('2 条消息')
  })

  it('shows an empty state rather than a blank table', async () => {
    stub([])
    const wrapper = mount(MaintenancePage)
    await flushPromises()

    expect(wrapper.text()).toContain('没有符合条件的数据')
  })
})