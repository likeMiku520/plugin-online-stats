import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import RiBarChartBoxLine from '~icons/ri/bar-chart-box-line'
import StatisticsPage from './views/StatisticsPage.vue'
import VisitorsPage from './views/OnlineStatsPage.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/online-stats',
        name: 'OnlineStats',
        component: StatisticsPage,
        meta: {
          title: '访问统计',
          searchable: true,
          menu: {
            name: '访问统计',
            group: '系统',
            icon: markRaw(RiBarChartBoxLine),
            priority: 0,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/online-stats/visitors',
        name: 'OnlineStatsVisitors',
        component: VisitorsPage,
        meta: {
          title: '访客明细',
          searchable: true,
          menu: {
            name: '访客明细',
            group: '系统',
            icon: markRaw(RiBarChartBoxLine),
            priority: 1,
          },
        },
      },
    },
  ],
})