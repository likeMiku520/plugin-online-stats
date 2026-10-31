import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import RiBarChartBoxLine from '~icons/ri/bar-chart-box-line'
import RiDatabase2Line from '~icons/ri/database-2-line'
import StatisticsPage from './views/StatisticsPage.vue'
import VisitorsPage from './views/OnlineStatsPage.vue'
import MaintenancePage from './views/MaintenancePage.vue'

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
            name: '访客统计',
            group: 'dashboard',
            icon: markRaw(RiBarChartBoxLine),
            priority: 1,
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
            group: 'dashboard',
            icon: markRaw(RiBarChartBoxLine),
            priority: 2,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/online-stats/maintenance',
        name: 'OnlineStatsMaintenance',
        component: MaintenancePage,
        meta: {
          title: '访客数据管理',
          searchable: true,
          menu: {
            name: '访客数据管理',
            group: 'dashboard',
            icon: markRaw(RiDatabase2Line),
            priority: 3,
          },
        },
      },
    },
  ],
})