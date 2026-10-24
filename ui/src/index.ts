import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import OnlineStatsIcon from '~icons/ri/eye-line'
import OnlineStatsPage from './views/OnlineStatsPage.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/online-stats',
        name: 'OnlineStats',
        component: OnlineStatsPage,
        meta: {
          title: '在线访客统计',
          searchable: true,
          menu: {
            name: '在线访客统计',
            group: '内容',
            icon: markRaw(OnlineStatsIcon),
            priority: 1,
          },
        },
      },
    },
  ],
})
