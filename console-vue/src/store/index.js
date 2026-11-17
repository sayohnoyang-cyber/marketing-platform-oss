import { createStore } from 'vuex'

const TOKEN_KEY = 'marketing.token'
const PROFILE_KEY = 'marketing.profile'

function readJson(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null')
  } catch {
    return null
  }
}

const store = createStore({
  state() {
    return {
      token: localStorage.getItem(TOKEN_KEY) || '',
      profile: readJson(PROFILE_KEY),
      workspaceTitle: ''
    }
  },
  mutations: {
    setSession(state, payload) {
      state.token = payload.token
      state.profile = payload.profile
      localStorage.setItem(TOKEN_KEY, payload.token)
      localStorage.setItem(PROFILE_KEY, JSON.stringify(payload.profile))
    },
    setWorkspaceTitle(state, title) {
      state.workspaceTitle = title || ''
    },
    clearSession(state) {
      state.token = ''
      state.profile = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(PROFILE_KEY)
    }
  }
})

export default store
