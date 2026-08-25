<template>
  <el-header class="app-top-nav ff-surface">
    <a class="skip-link" href="#app-main">跳转到主要内容</a>
    <div class="nav-brand" role="link" tabindex="0" aria-label="返回比赛" @click="handleBrandClick" @keydown.enter="handleBrandClick" @keydown.space.prevent="handleBrandClick">
      <div class="brand-icon football-brand">
        <component :is="brandIcon" :size="22" />
      </div>
      <div class="brand-text">
        <div class="brand-title">{{ title }}</div>
        <div class="brand-subtitle">{{ subtitle }}</div>
      </div>
    </div>

    <div class="nav-menu-wrap" :class="{ 'is-open': mobileMenuOpen }">
      <el-menu
        mode="horizontal"
        :default-active="activePath"
        :ellipsis="false"
        class="top-menu sport-menu"
        @select="handleSelect"
      >
        <el-menu-item index="/matches" class="menu-item">
          <el-icon><Football /></el-icon><span>比赛</span>
        </el-menu-item>
        <el-menu-item index="/competitions" class="menu-item">
          <el-icon><Notebook /></el-icon><span>赛事资料</span>
        </el-menu-item>
        <el-menu-item index="/agent" class="menu-item">
          <el-icon><ChatLineSquare /></el-icon><span>Agent</span>
        </el-menu-item>
        <el-menu-item v-if="isAdmin" index="/admin" class="menu-item">
          <el-icon><Setting /></el-icon><span>管理</span>
        </el-menu-item>
      </el-menu>
    </div>

    <div class="nav-actions">
      <slot name="actions" />
      <el-button text class="mobile-menu-toggle" :aria-expanded="mobileMenuOpen" :aria-label="mobileMenuOpen ? '关闭导航菜单' : '打开导航菜单'" :title="mobileMenuOpen ? '关闭导航菜单' : '导航菜单'" @click="mobileMenuOpen = !mobileMenuOpen"><el-icon><Menu /></el-icon></el-button>
      <el-button text class="global-search-btn" aria-label="全局搜索" title="全局搜索" @click="searchVisible = true"><el-icon><Search /></el-icon></el-button>
      <el-badge v-if="userStore.token" :value="notificationUnread" :hidden="notificationUnread === 0" :max="99" class="notification-badge">
        <el-button text class="global-search-btn" aria-label="通知中心" title="通知中心" @click="openNotifications"><el-icon><Bell /></el-icon></el-button>
      </el-badge>
      <el-dropdown trigger="click" @command="handleUserCommand">
        <div class="nav-account-trigger" :aria-label="userStore.token ? '打开个人菜单' : '打开登录菜单'">
          <el-avatar :size="36" class="nav-avatar" :src="userStore.avatarData || undefined">{{ userStore.username?.[0]?.toUpperCase() || '客' }}</el-avatar>
          <el-icon class="nav-account-arrow" aria-hidden="true"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="userStore.token" command="profile"><el-icon><User /></el-icon> 个人信息</el-dropdown-item>
            <el-dropdown-item v-else command="login"><el-icon><SwitchButton /></el-icon> 登录 / 注册</el-dropdown-item>
            <el-dropdown-item v-if="userStore.token" divided command="logout"><el-icon><SwitchButton /></el-icon> 退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <el-dialog v-model="searchVisible" title="全局搜索" width="min(620px, 92vw)" append-to-body>
      <el-input v-model="searchKeyword" clearable autofocus placeholder="搜索球队、联赛或比赛" @keyup.enter="runSearch" />
      <div v-if="searchLoading" class="global-search-state">正在搜索…</div>
      <div v-else-if="searchResults.matches.length" class="global-search-results">
        <button v-for="item in searchResults.matches" :key="`m-${item.fixtureId}`" type="button" class="global-search-item" @click="openMatch(item)"><strong>{{ item.homeTeamName }} vs {{ item.awayTeamName }}</strong><small>{{ item.leagueName || '比赛' }}</small></button>
      </div>
      <div v-else-if="searchKeyword" class="global-search-state">没有找到相关内容</div>
    </el-dialog>
    <el-dialog v-model="notificationVisible" title="通知中心" width="min(520px, 92vw)" append-to-body>
      <div class="notification-head">
        <span>比赛提醒、数据同步和账号安全通知会显示在这里</span>
        <el-button v-if="notificationUnread" link type="primary" @click="markAllNotifications">全部已读</el-button>
      </div>
      <div v-if="notificationLoading" class="global-search-state">正在加载通知…</div>
      <el-empty v-else-if="notifications.length === 0" description="暂无通知" />
      <div v-else class="notification-list">
        <button v-for="item in notifications" :key="item.id" type="button" class="notification-item" :class="{ 'is-unread': !item.read_at }" @click="markNotification(item)">
          <span class="notification-dot"></span>
          <span class="notification-copy"><strong>{{ item.title }}</strong><small>{{ item.body || '暂无详细内容' }}</small><em>{{ formatNotificationTime(item.created_at) }}</em></span>
        </button>
      </div>
    </el-dialog>
  </el-header>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { searchApi, userApi } from '../../api'
import { ArrowDown, ChatLineSquare, Football, Notebook, User, SwitchButton, Setting, Search, Bell, Menu } from '@element-plus/icons-vue'

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, required: true },
  brandIcon: { type: [Object, Function, String], required: true },
  brandHref: { type: String, default: '/matches' },
  activePath: { type: String, default: '/matches' },
})

const router = useRouter()
const userStore = useUserStore()
const searchVisible = ref(false)
const searchLoading = ref(false)
const searchKeyword = ref('')
const searchResults = reactive({ matches: [] })
const notificationVisible = ref(false)
const mobileMenuOpen = ref(false)
const notificationLoading = ref(false)
const notifications = ref([])
const notificationUnread = ref(0)
const isAdmin = computed(() => ['ADMIN', 'SUPER_ADMIN'].includes(userStore.role))
const protectedPaths = ['/agent', '/profile', '/admin']

const requiresLogin = path => protectedPaths.some(value => path === value || path.startsWith(`${value}/`))

const handleBrandClick = () => {
  router.push(props.brandHref)
}

const runSearch = async () => {
  if (!searchKeyword.value.trim()) return
  searchLoading.value = true
  try {
    const result = await searchApi.search(searchKeyword.value.trim())
    searchResults.matches = result?.matches || []
  } catch { searchResults.matches = [] } finally { searchLoading.value = false }
}
const openMatch = item => { searchVisible.value = false; const id = item?.matchId || item?.id || item?.fixtureId; if (!id) return; router.push(`/prediction/${id}?homeName=${encodeURIComponent(item.homeTeamName || '')}&awayName=${encodeURIComponent(item.awayTeamName || '')}&leagueName=${encodeURIComponent(item.leagueName || '')}&matchTime=${encodeURIComponent(item.matchTime || '')}`) }

const loadNotifications = async () => {
  if (!userStore.token) return
  notificationLoading.value = true
  try {
    const result = await userApi.getNotifications(20)
    notifications.value = result?.items || []
    notificationUnread.value = Number(result?.unread || 0)
  } catch { notifications.value = []; notificationUnread.value = 0 } finally { notificationLoading.value = false }
}
const openNotifications = async () => { notificationVisible.value = true; await loadNotifications() }
const markNotification = async item => {
  if (!item.read_at) {
    await userApi.readNotification(item.id).catch(() => {})
    item.read_at = new Date().toISOString()
    notificationUnread.value = Math.max(0, notificationUnread.value - 1)
  }
  if (item.link) router.push(item.link)
}
const markAllNotifications = async () => {
  await userApi.readAllNotifications().catch(() => {})
  notifications.value.forEach(item => { item.read_at = item.read_at || new Date().toISOString() })
  notificationUnread.value = 0
}
const formatNotificationTime = value => value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''

const handleSelect = (index) => {
  mobileMenuOpen.value = false
  if (index === props.activePath) return
  if (requiresLogin(index) && !userStore.token) {
    userStore.openAuthDialog(index)
    return
  }
  router.push(index)
}

const handleUserCommand = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    if (requiresLogin(router.currentRoute.value.path)) router.replace('/matches')
  } else if (cmd === 'profile') {
    if (userStore.token) router.push('/profile')
    else userStore.openAuthDialog('/profile')
  } else if (cmd === 'login') {
    userStore.openAuthDialog(router.currentRoute.value.fullPath)
  }
}

watch(() => userStore.token, token => { if (token) loadNotifications(); else { notifications.value = []; notificationUnread.value = 0 } })
onMounted(() => { if (userStore.token) loadNotifications() })
</script>

<style scoped>
.app-top-nav {
  display: flex;
  align-items: center;
  gap: 24px;
  position: sticky;
  top: 0;
  z-index: 20;
  padding: 0 28px;
  min-height: 64px;
  border-radius: 0;
  border-top: none;
  border-left: none;
  border-right: none;
  border-bottom: 1px solid var(--ff-border);
  background: rgba(255, 255, 255, 0.96);
}
.skip-link { position:absolute; top:-48px; left:14px; z-index:30; padding:8px 12px; border-radius:6px; color:#fff; background:var(--ff-primary); font-size:12px; font-weight:700; transition:top var(--ff-transition-fast); }
.skip-link:focus { top:8px; outline:2px solid var(--ff-gold); outline-offset:2px; }
.global-search-btn { color: var(--ff-text-muted); }
.notification-badge { display: inline-flex; }
.notification-head { display:flex; align-items:center; justify-content:space-between; gap:12px; color:var(--ff-text-muted); font-size:12px; }
.notification-list { display:flex; flex-direction:column; gap:6px; margin-top:14px; max-height:420px; overflow:auto; }
.notification-item { display:flex; gap:10px; width:100%; padding:12px; border:1px solid transparent; border-radius:8px; background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer; }
.notification-item:hover { border-color:var(--ff-primary); }
.notification-item.is-unread { background:rgba(15,107,77,.06); }
.notification-dot { width:7px; height:7px; margin-top:6px; border-radius:50%; background:transparent; flex:none; }
.notification-item.is-unread .notification-dot { background:var(--ff-primary); }
.notification-copy { display:flex; flex-direction:column; gap:4px; min-width:0; }
.notification-copy strong { font-size:13px; }
.notification-copy small, .notification-copy em { color:var(--ff-text-muted); font-size:12px; font-style:normal; }
.global-search-results { display:flex; flex-direction:column; gap:8px; margin-top:16px; }
.global-search-item { display:flex; flex-direction:column; align-items:flex-start; gap:3px; padding:10px 12px; border:1px solid var(--ff-border); border-radius:8px; background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer; }
.global-search-item:hover { border-color:var(--ff-primary); }
.global-search-item small, .global-search-state { color:var(--ff-text-muted); font-size:12px; }
.global-search-state { padding:20px 0; text-align:center; }


.nav-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  flex: none;
  transition: opacity var(--ff-transition-fast);
}
.nav-brand:hover { opacity: 0.88; }
.nav-brand:focus-visible { outline:2px solid var(--ff-primary); outline-offset:4px; border-radius:6px; }

.brand-icon.football-brand {
  width: 32px;
  height: 32px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  background: var(--ff-primary);
  flex: none;
  box-shadow: none;
  transition: background-color var(--ff-transition-fast);
}
.nav-brand:hover .brand-icon.football-brand {
  background: var(--ff-primary-hover);
}

.brand-text { min-width: 0; }
.brand-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--ff-text-strong);
  line-height: 1.1;
  letter-spacing: -0.01em;
}
.brand-title em {
  font-style: normal;
  color: var(--ff-primary);
}
.brand-subtitle {
  font-size: 11px;
  font-weight: 400;
  color: var(--ff-text-muted);
  margin-top: 2px;
  line-height: 1.4;
}

.nav-menu-wrap {
  flex: 1;
  display: flex;
  justify-content: center;
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.mobile-menu-toggle { display:none; color:var(--ff-text-muted); }
.nav-account-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 3px 2px 2px;
  border: 1px solid transparent;
  border-radius: 22px;
  color: var(--ff-text-muted);
  background: transparent;
  cursor: pointer;
  outline: none;
  transition: border-color var(--ff-transition-fast), background-color var(--ff-transition-fast), color var(--ff-transition-fast);
}
.nav-account-trigger:hover,
.nav-account-trigger:focus-visible {
  border-color: var(--ff-border-strong);
  background: var(--ff-surface-quiet);
  color: var(--ff-primary);
}
.nav-account-arrow {
  font-size: 14px;
}

.nav-avatar {
  background: var(--ff-primary);
  color: #ffffff;
  font-weight: 600;
  cursor: pointer;
  border: none;
  box-shadow: none;
  transition: background-color var(--ff-transition-fast), color var(--ff-transition-fast);
}
.nav-avatar:hover {
  background: var(--ff-primary-hover);
}

.sport-menu {
  background: transparent;
  padding: 0;
  border-radius: 0;
  border: none;
  box-shadow: none;
}
.sport-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  border-radius: 4px;
  margin: 0 1px;
  color: var(--ff-text-muted);
  font-weight: 600;
  transition: background-color var(--ff-transition-fast), color var(--ff-transition-fast), border-color var(--ff-transition-fast);
  border-bottom: 2px solid transparent !important;
}
.sport-menu :deep(.el-menu-item:hover) {
  background: var(--ff-surface-quiet);
  color: var(--ff-primary);
}
.sport-menu :deep(.el-menu-item.is-active) {
  background: transparent;
  color: var(--ff-primary) !important;
  border-bottom-color: var(--ff-primary) !important;
  box-shadow: none;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 5px;
  font-weight: 600;
}

@media (max-width: 768px) {
  .app-top-nav {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      "brand actions"
      "menu menu";
    align-items: center;
    height: auto;
    min-height: 60px;
    padding: 10px 14px;
    gap: 8px 12px;
  }
  .nav-brand { grid-area: brand; min-width: 0; }
  .nav-menu-wrap {
    grid-area: menu;
    display:none;
    position:absolute;
    top:calc(100% + 6px);
    left:12px;
    right:12px;
    width:auto;
    min-width:0;
    padding:6px;
    border:1px solid var(--ff-border);
    border-radius:12px;
    background:var(--ff-surface);
    box-shadow:var(--ff-shadow-md);
  }
  .nav-menu-wrap.is-open { display:flex; }
  .mobile-menu-toggle { display:inline-flex; }
  .nav-menu-wrap .sport-menu {
    display:grid;
    grid-template-columns:repeat(2,minmax(0,1fr));
    width:100%;
    min-width:0;
  }
  .nav-menu-wrap .sport-menu :deep(.el-menu-item) {
    justify-content:flex-start;
    margin:2px;
    padding:0 12px;
  }
  .nav-menu-wrap .sport-menu :deep(.el-menu-item.is-active) { border-bottom-color:transparent !important; }
  .nav-menu-wrap::-webkit-scrollbar { display: none; }
  .nav-menu-wrap .sport-menu :deep(.el-menu-item) {
    height:34px;
    line-height:34px;
  }
  .nav-actions { grid-area: actions; gap: 6px; }
}
</style>
