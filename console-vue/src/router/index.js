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
                { path: '', redirect: to => `/activities/${to.params.activityId}/info` },
                { path: 'info', name: 'activity-info', meta: { title: '基本信息' }, component: () => import('@/views/activity/steps/InfoStep.vue') },
                { path: 'pick', name: 'activity-pick', meta: { title: '选券' }, component: () => import('@/views/activity/steps/PickStep.vue') },
                { path: 'link', name: 'activity-link', meta: { title: '配渠道' }, component: () => import('@/views/activity/steps/LinkStep.vue') },
                { path: 'forecast', name: 'activity-forecast', meta: { title: '看预测' }, component: () => import('@/views/activity/steps/ForecastStep.vue') },
                { path: 'prepare', name: 'activity-prepare', meta: { title: '预热' }, component: () => import('@/views/activity/steps/PrepareStep.vue') },
                { path: 'live', name: 'activity-live', meta: { title: '开场' }, component: () => import('@/views/activity/steps/LiveStep.vue') },
                { path: 'review', name: 'activity-review', meta: { title: '复盘' }, component: () => import('@/views/activity/steps/ReviewStep.vue') }
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
