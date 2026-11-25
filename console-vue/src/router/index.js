import { createRouter, createWebHistory } from 'vue-router'
import store from '@/store'

const router = createRouter({
  history: createWebHistory(),
  linkActiveClass: 'active',
  linkExactActiveClass: 'exact',
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/login/LoginView.vue'), meta: { public: true, title: '登录' } },
    { path: '/my-coupons', name: 'my-coupons', meta: { title: '我的券' }, component: () => import('@/views/coupon/MyCouponView.vue') },
    { path: '/a/:id', name: 'activity-landing', component: () => import('@/views/activity/ActivityLanding.vue'), meta: { public: true, title: '活动入口' } },
    {
      path: '/',
      component: () => import('@/views/layout/WorkspaceLayout.vue'),
      children: [
        { path: '', redirect: '/overview' },
        { path: 'overview', name: 'overview', meta: { title: '营销总览' }, component: () => import('@/views/activity/OverviewView.vue') },
        {
          path: 'activities',
          component: () => import('@/views/activity/ActivitySection.vue'),
          children: [
            { path: '', name: 'activities', meta: { title: '活动管理' }, component: () => import('@/views/activity/ActivityList.vue') },
            { path: 'new', name: 'activity-new', meta: { title: '创建活动' }, component: () => import('@/views/activity/ActivityCreate.vue') },
            {
              path: ':activityId',
              component: () => import('@/views/activity/ActivityWorkspace.vue'),
              children: [
                { path: '', redirect: to => `/activities/${to.params.activityId}/setup` },
                // 新版 4 步工作台路由：活动配置 / 推广入口 / 实时与复盘 / 高级保障
                { path: 'setup', name: 'activity-setup', meta: { title: '活动配置' }, component: () => import('@/views/activity/steps/SetupStep.vue') },
                { path: 'promote', name: 'activity-promote', meta: { title: '推广入口' }, component: () => import('@/views/activity/steps/LinkStep.vue') },
                { path: 'analytics', name: 'activity-analytics', meta: { title: '实时与复盘' }, component: () => import('@/views/activity/steps/AnalyticsStep.vue') },
                { path: 'guard', name: 'activity-guard', meta: { title: '高级保障' }, component: () => import('@/views/activity/steps/GuardStep.vue') },
                // 兼容历史 7 步书签与跳转链接
                { path: 'info', redirect: to => ({ path: `/activities/${to.params.activityId}/setup`, query: to.query }) },
                { path: 'pick', redirect: to => ({ path: `/activities/${to.params.activityId}/setup`, query: to.query }) },
                { path: 'link', redirect: to => ({ path: `/activities/${to.params.activityId}/promote`, query: to.query }) },
                { path: 'forecast', redirect: to => ({ path: `/activities/${to.params.activityId}/guard`, query: { ...to.query, tab: 'forecast' } }) },
                { path: 'prepare', redirect: to => ({ path: `/activities/${to.params.activityId}/guard`, query: { ...to.query, tab: 'prepare' } }) },
                { path: 'live', redirect: to => ({ path: `/activities/${to.params.activityId}/analytics`, query: to.query }) },
                { path: 'review', redirect: to => ({ path: `/activities/${to.params.activityId}/analytics`, query: to.query }) }
              ]
            }
          ]
        },
        { path: 'preview', name: 'preview', meta: { title: '领券页预览' }, component: () => import('@/views/activity/PreviewView.vue') },
        { path: 'coupons', name: 'coupons', meta: { title: '券库' }, component: () => import('@/views/coupon/CouponView.vue') },
        { path: 'coupon-management', name: 'coupon-management', meta: { title: '优惠券核销' }, component: () => import('@/views/coupon/CouponManagementView.vue') },
        { path: 'links', name: 'links', meta: { title: '短链管理' }, component: () => import('@/views/link/LinkView.vue') },
        { path: 'account', name: 'account', meta: { title: '账号' }, component: () => import('@/views/account/AccountView.vue') }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) {
    return true
  }
  if (!store.state.token) {
    return { path: '/login', query: { return: to.fullPath } }
  }
  return true
})

export default router
