import { createRouter, createWebHistory } from 'vue-router'

const EmptyRouteView = {
  render: () => null,
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: EmptyRouteView,
    },
    {
      path: '/thread/:threadId',
      name: 'thread',
      component: EmptyRouteView,
    },
    {
      path: '/new-thread',
      redirect: { name: 'home' },
    },
    {
      path: '/openclaw/chat',
      name: 'openclaw-chat',
      component: EmptyRouteView,
    },
    { path: '/:pathMatch(.*)*', redirect: { name: 'home' } },
  ],
})

export default router
