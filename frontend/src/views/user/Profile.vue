<template>
  <div class="profile-page ff-page-shell">
    <el-container class="profile-layout">
      <AppTopNav title="ChenFootball" :subtitle="t('account.profile.subtitle')" :brand-icon="Football" active-path="" />
      <el-main id="app-main" class="profile-main" tabindex="-1">
        <div v-if="profileLoading" class="profile-loading ff-panel"><el-skeleton :rows="8" animated /></div>

        <template v-else>
        <section class="identity-card ff-panel" aria-labelledby="identity-title">
          <div class="identity-main">
            <div class="avatar-editor">
              <el-avatar :size="76" :src="avatarSrc || undefined" class="identity-avatar" @error="avatarLoadFailed = true">{{ avatarInitial }}</el-avatar>
              <button type="button" class="avatar-edit-button" :aria-label="t('account.profile.changeAvatar')" :title="t('account.profile.changeAvatar')" :disabled="avatarSaving" @click="openAvatarPicker"><el-icon><Camera /></el-icon></button>
              <input ref="avatarInput" class="avatar-input" type="file" accept="image/png,image/jpeg,image/webp" @change="onAvatarSelected" />
            </div>
            <div class="identity-copy">
              <span class="section-kicker">{{ t('account.profile.accountDetails') }}</span>
              <h2 id="identity-title">{{ displayName }}</h2>
              <p class="identity-email">{{ profileIdentity.email || t('account.profile.legacyNoEmail') }}</p>
              <div class="identity-meta">
                <el-tag :type="emailVerified ? 'success' : 'warning'" effect="plain" round size="small">{{ emailVerified ? t('account.profile.emailVerified') : t('account.profile.emailUnverified') }}</el-tag>
                <span v-if="profileIdentity.createdAt">{{ t('account.profile.joinedAt', { date: formatDateOnly(profileIdentity.createdAt) }) }}</span>
                <span v-if="profileIdentity.role && profileIdentity.role !== 'USER'">{{ t('account.profile.adminEnabled') }}</span>
              </div>
            </div>
          </div>
          <div class="identity-actions"><el-button type="primary" plain @click="goMatches"><el-icon><Football /></el-icon>{{ t('account.profile.browseMatches') }}</el-button><el-button plain @click="openProfileEditor">{{ t('account.profile.editProfile') }}</el-button><el-button plain @click="switchTab('favorites')">{{ t('account.profile.viewFavorites') }}</el-button><el-button plain @click="switchTab('security')">{{ t('account.profile.accountSecurity') }}</el-button></div>
        </section>

        <section v-if="publicStats" class="benchmark-strip model-performance ff-panel" :aria-label="t('account.profile.modelPerformance')">
          <div><span class="section-kicker">{{ t('account.profile.modelPerformance') }}</span><strong>{{ t('account.profile.modelAccuracyWindow', { days: publicStats.windowDays || 7 }) }}</strong><span>{{ t('account.profile.modelAccuracyNote') }}{{ statsUpdatedText }}</span><div class="benchmark-meta"><span>{{ t('account.profile.verifiedMatches', { count: publicStats.evaluated ?? 0 }) }}</span><span v-if="publicStats.baselineAccuracy != null">{{ t('account.profile.baselineAccuracy', { value: publicStats.baselineAccuracy }) }}</span><span v-if="publicStats.balancedAccuracy != null">{{ t('account.profile.balancedAccuracy', { value: publicStats.balancedAccuracy }) }}</span></div></div>
          <div class="benchmark-value"><strong>{{ publicAccuracy }}</strong><small>{{ t('account.profile.siteReference') }}</small></div>
        </section>

        <section ref="workspaceRef" class="workspace-panel ff-panel" :aria-label="t('account.profile.workspaceAria')">
          <nav class="workspace-tabs" :aria-label="t('account.profile.navAria')">
            <button v-for="tab in tabs" :id="`profile-tab-${tab.key}`" :key="tab.key" type="button" :class="{ active: activeTab === tab.key }" :aria-selected="activeTab === tab.key" :aria-controls="`profile-panel-${tab.key}`" :tabindex="activeTab === tab.key ? 0 : -1" role="tab" @click="switchTab(tab.key)" @keydown.left.prevent="moveProfileTab(-1)" @keydown.right.prevent="moveProfileTab(1)" @keydown.home.prevent="moveProfileTab('first')" @keydown.end.prevent="moveProfileTab('last')"><el-icon><component :is="tab.icon" /></el-icon><span>{{ tab.label }}</span><em v-if="tab.count !== undefined">{{ tab.count }}</em></button>
          </nav>

          <div v-if="activeTab === 'overview'" id="profile-panel-overview" class="workspace-content" role="tabpanel">
            <div class="overview-grid">
              <section class="overview-card">
                <div class="content-heading"><div><span class="section-kicker">{{ t('account.profile.followingKicker') }}</span><h2>{{ t('account.profile.overviewTitle') }}</h2></div><el-button link type="primary" @click="switchTab('favorites')">{{ t('account.profile.manageFavorites') }}</el-button></div>
                <div class="overview-summary"><strong>{{ favorites.length + matchFavorites.length }}</strong><span>{{ t('account.profile.followCountLabel') }}</span><small>{{ t('account.profile.followBreakdown', { teams: favorites.length, matches: matchFavorites.length }) }}</small></div>
                <div v-if="favorites.length || matchFavorites.length" class="mini-follow-list">
                  <div v-for="item in overviewFavorites" :key="item.key" class="mini-follow-item"><span class="mini-icon">{{ item.initial }}</span><div><strong>{{ item.title }}</strong><small>{{ item.subtitle }}</small></div><el-button text type="primary" @click="item.action">{{ t('account.common.view') }}</el-button></div>
                </div>
                <PageState v-else :title="t('account.profile.noFollowingTitle')" :description="t('account.profile.noFollowingDesc')" :action-text="t('account.profile.discoverMatches')" @action="goMatches" />
              </section>
              <section class="overview-card">
                <div class="content-heading"><div><span class="section-kicker">{{ t('account.profile.recentKicker') }}</span><h2>{{ t('account.profile.recentPredictions') }}</h2></div><el-button link type="primary" @click="switchTab('history')">{{ t('account.profile.viewAll') }}</el-button></div>
                <div v-if="history.length" class="recent-predictions">
                  <div v-for="item in history.slice(0, 3)" :key="item.id" class="recent-prediction" @click="goPrediction(item)"><div><strong>{{ teamLabel(item, 'home') || t('account.profile.teamA') }} <b>VS</b> {{ teamLabel(item, 'away') || t('account.profile.teamB') }}</strong><small>{{ leagueLabel(item) || t('account.profile.uncategorizedLeague') }} · {{ formatDate(item.createdAt) }}</small></div><el-tag :type="resultTagType(item.resultLabel)" size="small">{{ resultLabel(item.resultLabel) }}</el-tag></div>
                </div>
                <PageState v-else :title="t('account.profile.noPredictionsTitle')" :description="t('account.profile.noPredictionsDesc')" :action-text="t('account.profile.goToMatches')" @action="goMatches" />
              </section>
            </div>
          </div>

          <div v-else-if="activeTab === 'favorites'" id="profile-panel-favorites" class="workspace-content" role="tabpanel">
            <div class="content-heading workspace-title-row"><div><span class="section-kicker">{{ t('account.profile.followingKicker') }}</span><h2>{{ t('account.profile.myFavorites') }}</h2><p>{{ t('account.profile.favoritesDesc') }}</p></div><el-button type="primary" plain @click="goMatches">{{ t('account.profile.discoverMore') }}</el-button></div>
            <el-tabs v-model="favoriteTab" class="favorite-tabs"><el-tab-pane :label="t('account.profile.teamsTab', { count: favorites.length })" name="teams" /><el-tab-pane :label="t('account.profile.matchesTab', { count: matchFavorites.length })" name="matches" /></el-tabs>
            <div class="library-toolbar"><el-input v-model="favoriteKeyword" size="small" clearable :placeholder="t('account.profile.searchFavoritesPlaceholder')" :aria-label="t('account.profile.searchFavoritesAria')" /><el-select v-model="favoriteSort" size="small" :aria-label="t('account.profile.favoriteSortAria')"><el-option :label="t('account.profile.recentFavorite')" value="recent" /><el-option :label="t('account.profile.sortByName')" value="name" /></el-select><el-select v-if="favoriteTab === 'matches'" v-model="favoriteMatchFilter" size="small" :aria-label="t('account.profile.matchStatusFilterAria')"><el-option :label="t('account.profile.allStatuses')" value="all" /><el-option :label="t('account.profile.statusUpcoming')" value="upcoming" /><el-option :label="t('account.profile.statusFinished')" value="finished" /><el-option :label="t('account.profile.statusUnknown')" value="unknown" /></el-select></div>
            <PageState v-if="favoriteTab === 'teams' && favoritesLoading" type="loading" :title="t('account.profile.loadingFavoriteTeams')" :size="40" />
            <PageState v-else-if="favoriteTab === 'teams' && favoritesError" type="error" :title="t('account.profile.favoriteTeamsFailed')" :description="favoritesError" :action-text="t('account.common.retry')" @action="loadFavorites" />
            <PageState v-else-if="favoriteTab === 'teams' && !favorites.length" :title="t('account.profile.noFavoriteTeamsTitle')" :description="t('account.profile.noFavoriteTeamsDesc')" :action-text="t('account.profile.browseTeams')" @action="goMatches" />
            <PageState v-else-if="favoriteTab === 'teams' && !filteredFavorites.length" :title="t('account.profile.noMatchingTeams')" :description="t('account.profile.noMatchingTeamsDesc')" :action-text="t('account.profile.clearFilters')" @action="favoriteKeyword = ''; favoriteSort = 'recent'" />
            <div v-else-if="favoriteTab === 'teams'" class="library-list"><article v-for="fav in filteredFavorites" :key="fav.id" class="library-item"><div class="library-avatar">{{ teamInitial(teamLabel(fav)) }}</div><div class="library-copy"><strong>{{ teamLabel(fav) || t('account.profile.unnamedTeam') }}</strong><small>{{ leagueLabel(fav) || t('account.profile.leaguePending') }} · {{ t('account.profile.favoritedAt', { date: formatDate(fav.createdAt) }) }}</small></div><div class="library-actions"><el-button size="small" type="primary" plain @click="goTeam(fav.teamId, fav.teamName, fav.leagueName, fav.logo)">{{ t('account.profile.viewTeam') }}</el-button><el-button size="small" text type="danger" @click="removeFav(fav)">{{ t('account.profile.unfollow') }}</el-button></div></article></div>
            <PageState v-if="favoriteTab === 'matches' && matchFavoritesLoading" type="loading" :title="t('account.profile.loadingFavoriteMatches')" :size="40" />
            <PageState v-else-if="favoriteTab === 'matches' && matchFavoritesError" type="error" :title="t('account.profile.favoriteMatchesFailed')" :description="matchFavoritesError" :action-text="t('account.common.retry')" @action="loadMatchFavorites" />
            <PageState v-else-if="favoriteTab === 'matches' && !matchFavorites.length" :title="t('account.profile.noFavoriteMatchesTitle')" :description="t('account.profile.noFavoriteMatchesDesc')" :action-text="t('account.profile.browseFixtures')" @action="goMatches" />
            <PageState v-else-if="favoriteTab === 'matches' && !filteredMatchFavorites.length" :title="t('account.profile.noMatchingMatches')" :description="t('account.profile.noMatchingMatchesDesc')" :action-text="t('account.profile.clearFilters')" @action="favoriteKeyword = ''; favoriteMatchFilter = 'all'; favoriteSort = 'recent'" />
            <div v-else-if="favoriteTab === 'matches'" class="library-list"><article v-for="fav in filteredMatchFavorites" :key="fav.id" class="library-item match-library-item"><div class="library-avatar match-avatar">VS</div><div class="library-copy"><strong>{{ teamLabel(fav, 'home') || t('account.profile.unknownHomeTeam') }} <b>VS</b> {{ teamLabel(fav, 'away') || t('account.profile.unknownAwayTeam') }}</strong><small>{{ leagueLabel(fav) || t('account.profile.leaguePending') }} · {{ formatMatchTime(fav.matchTime) }}</small></div><div class="library-status"><el-tag size="small" effect="plain" :type="favoriteMatchState(fav).type">{{ favoriteMatchState(fav).label }}</el-tag></div><div class="library-actions"><el-button size="small" type="primary" plain @click="goPrediction(fav)">{{ t('account.profile.viewAnalysis') }}</el-button><el-button size="small" text type="danger" @click="removeMatchFav(fav)">{{ t('account.profile.unfollow') }}</el-button></div></article></div>
          </div>

          <div v-else-if="activeTab === 'history'" id="profile-panel-history" class="workspace-content" role="tabpanel">
            <div class="content-heading workspace-title-row"><div><span class="section-kicker">{{ t('account.profile.historyKicker') }}</span><h2>{{ t('account.profile.historyTitle') }}</h2><p>{{ t('account.profile.historyDesc') }}</p></div><div class="history-total">{{ t('account.profile.historyTotal', { total: personalStats?.total ?? history.length, pending: personalStats?.pending ?? '—' }) }}</div></div>
            <div class="history-toolbar"><el-input v-model="historyKeyword" class="history-search" size="small" clearable :placeholder="t('account.profile.searchHistoryPlaceholder')" :aria-label="t('account.profile.searchHistoryAria')" /><el-radio-group v-model="historyStatus" size="small" :aria-label="t('account.profile.verificationStatusAria')"><el-radio-button label="all">{{ t('account.profile.filterAll') }}</el-radio-button><el-radio-button label="correct">{{ t('account.profile.filterCorrect') }}</el-radio-button><el-radio-button label="wrong">{{ t('account.profile.filterWrong') }}</el-radio-button><el-radio-button label="pending">{{ t('account.profile.filterPending') }}</el-radio-button></el-radio-group><el-select v-model="historyOutcome" size="small" :aria-label="t('account.profile.outcomeFilterAria')"><el-option :label="t('account.profile.allOutcomes')" value="all" /><el-option :label="t('account.profile.homeWin')" value="HOME_WIN" /><el-option :label="t('account.profile.draw')" value="DRAW" /><el-option :label="t('account.profile.awayWin')" value="AWAY_WIN" /></el-select><el-select v-model="historyDateRange" size="small" :aria-label="t('account.profile.dateRangeAria')"><el-option :label="t('account.profile.allTime')" value="all" /><el-option :label="t('account.profile.last7Days')" value="7d" /><el-option :label="t('account.profile.last30Days')" value="30d" /></el-select></div>
            <PageState v-if="historyLoading && !history.length" type="loading" :title="t('account.profile.loadingHistory')" :size="40" />
            <PageState v-else-if="historyError && !history.length" type="error" :title="t('account.profile.historyFailed')" :description="historyError" :action-text="t('account.common.retry')" @action="() => loadHistoryPage(true)" />
            <PageState v-else-if="!filteredHistory.length" :title="t('account.profile.noMatchingHistory')" :description="t('account.profile.noMatchingHistoryDesc')" :action-text="t('account.profile.goToMatches')" @action="goMatches" />
            <div v-else class="history-list">
              <article v-for="item in filteredHistory" :key="item.id" class="history-item" tabindex="0" role="button" @click="goPrediction(item)" @keyup.enter="goPrediction(item)"><div class="history-main"><div class="history-date">{{ formatDate(item.createdAt) }}</div><strong>{{ teamLabel(item, 'home') || t('account.profile.teamA') }} <b>VS</b> {{ teamLabel(item, 'away') || t('account.profile.teamB') }}</strong><small>{{ leagueLabel(item) || t('account.profile.uncategorizedLeague') }}</small></div><div class="history-result"><el-tag :type="resultTagType(item.resultLabel)" size="small">{{ resultLabel(item.resultLabel) }}</el-tag><span :class="['verification-status', verificationClass(item)]">{{ verificationLabel(item) }}</span></div><div class="history-probability"><div class="prob-stack" :title="t('account.profile.probabilityTooltip', { home: pct(item.homeWinProb), draw: pct(item.drawProb), away: pct(item.awayWinProb) })"><span class="seg seg-home" :style="{ width: pct(item.homeWinProb) }"></span><span class="seg seg-draw" :style="{ width: pct(item.drawProb) }"></span><span class="seg seg-away" :style="{ width: pct(item.awayWinProb) }"></span></div><div class="prob-nums"><span class="c-home">{{ t('account.profile.homeShort') }} {{ pct(item.homeWinProb) }}</span><span class="c-draw">{{ t('account.profile.drawShort') }} {{ pct(item.drawProb) }}</span><span class="c-away">{{ t('account.profile.awayShort') }} {{ pct(item.awayWinProb) }}</span></div></div><el-button class="history-view" text type="primary" @click.stop="goPrediction(item)">{{ t('account.common.view') }}</el-button></article>
            </div>
            <div v-if="history.length && !historyError" class="history-footer"><el-button v-if="historyHasMore" :loading="historyLoading" plain @click="loadHistoryPage(false)">{{ t('account.profile.loadMore') }}</el-button><span v-else>{{ t('account.profile.allRecordsShown', { count: history.length }) }}</span></div>
          </div>

          <div v-else id="profile-panel-security" class="workspace-content security-content" role="tabpanel">
            <div class="content-heading workspace-title-row"><div><span class="section-kicker">{{ t('account.profile.securityKicker') }}</span><h2>{{ t('account.profile.securityTitle') }}</h2><p>{{ t('account.profile.securityDesc') }}</p></div></div>
            <div class="security-grid">
              <section class="security-card"><div class="security-card-heading"><h3>{{ t('account.profile.loginAccount') }}</h3><el-button link type="primary" @click="openProfileEditor">{{ t('account.profile.editProfile') }}</el-button></div><div class="account-row"><span>{{ t('account.common.email') }}</span><strong>{{ profileIdentity.email || t('account.profile.noEmailBound') }}</strong><el-tag v-if="profileIdentity.email" :type="emailVerified ? 'success' : 'warning'" size="small" effect="plain">{{ emailVerified ? t('account.profile.verified') : t('account.profile.unverified') }}</el-tag></div><div class="account-row"><span>{{ t('account.common.nickname') }}</span><strong>{{ displayName }}</strong></div><el-alert v-if="!profileIdentity.email" type="info" :closable="false" show-icon :title="t('account.profile.legacyAlert')" /></section>
              <section class="security-card"><h3>{{ t('account.profile.reminderPrefs') }}</h3><p class="security-help">{{ t('account.profile.reminderHelp') }}</p><el-alert type="info" :closable="false" show-icon :title="t('account.profile.reminderStatusAlert')" /><el-form label-position="top" class="security-form"><el-form-item :label="t('account.profile.reminderEmail')"><el-input v-model="accountProfile.email" type="email" :placeholder="t('account.profile.reminderEmailPlaceholder')" /></el-form-item><el-form-item :label="t('account.profile.reminderPhone')"><el-input v-model="accountProfile.phone" :placeholder="t('account.profile.reminderPhonePlaceholder')" /></el-form-item><el-checkbox v-model="accountProfile.matchRemindersEnabled">{{ t('account.profile.matchReminderCheckbox') }}</el-checkbox><el-button class="form-submit" plain :loading="profileSaving" @click="saveAccountProfile">{{ t('account.profile.saveReminderPrefs') }}</el-button></el-form></section>
              <section class="security-card notification-card"><div class="security-card-heading"><h3>{{ t('account.profile.notificationHistory') }} <el-badge v-if="notificationUnread" :value="notificationUnread" class="inline-badge" /></h3><el-button v-if="notificationUnread" link type="primary" @click="markAllNotifications">{{ t('account.profile.markAllRead') }}</el-button></div><PageState v-if="notificationLoading" type="loading" :title="t('account.profile.loadingNotifications')" :size="30" /><PageState v-else-if="!notifications.length" :title="t('account.profile.noNotificationsTitle')" :description="t('account.profile.noNotificationsDesc')" /><div v-else class="profile-notification-list"><button v-for="item in notifications.slice(0, 6)" :key="item.id" type="button" class="profile-notification-item" :class="{ 'is-unread': !item.read_at }" @click="markNotification(item)"><span class="notification-dot"></span><span><strong>{{ item.title }}</strong><small>{{ item.body || t('account.profile.noDetails') }} · {{ notificationTime(item.created_at) }}</small></span></button></div></section>
              <section class="security-card session-card"><div class="security-card-heading"><h3>{{ t('account.profile.loginDevices') }}</h3><span class="security-count">{{ t('account.profile.sessionCount', { count: sessions.length }) }}</span></div><PageState v-if="sessionsLoading" type="loading" :title="t('account.profile.loadingSessions')" :size="30" /><PageState v-else-if="!sessions.length" :title="t('account.profile.noSessionsTitle')" :description="t('account.profile.noSessionsDesc')" /><div v-else class="session-list"><div v-for="(session, index) in sessions" :key="`${session.label}-${index}`" class="session-item"><div><strong>{{ session.label }}</strong><small>{{ t('account.profile.refreshTokenRemaining', { value: formatSessionExpiry(session.expiresInSeconds) }) }}</small></div><el-tag v-if="session.current" size="small" type="success" effect="plain">{{ t('account.profile.current') }}</el-tag></div></div></section>
              <section class="security-card"><h3>{{ t('account.profile.changePassword') }}</h3><el-form label-position="top" class="security-form"><el-form-item :label="t('account.profile.currentPassword')"><el-input v-model="passwordForm.currentPassword" type="password" show-password /></el-form-item><el-form-item :label="t('account.profile.newPassword')"><el-input v-model="passwordForm.newPassword" type="password" show-password :placeholder="t('account.profile.newPasswordPlaceholder')" /></el-form-item><el-form-item :label="t('account.profile.confirmNewPassword')"><el-input v-model="passwordForm.confirmPassword" type="password" show-password /></el-form-item><el-button type="primary" class="form-submit" :loading="passwordSaving" @click="changePassword">{{ t('account.profile.updatePassword') }}</el-button></el-form></section>
              <section class="security-card danger-card"><h3>{{ t('account.profile.dangerZone') }}</h3><p class="security-help">{{ t('account.profile.dangerHelp') }}</p><div class="danger-actions"><el-button plain @click="revokeAllSessions">{{ t('account.profile.revokeOthers') }}</el-button><el-button type="danger" plain @click="disableAccount">{{ t('account.profile.disableAccount') }}</el-button></div></section>
            </div>
          </div>
        </section>
        </template>
      </el-main>
    </el-container>
    <el-dialog v-model="profileDialogVisible" :title="t('account.profile.editDialogTitle')" width="min(440px, 92vw)">
      <el-form label-position="top" @submit.prevent="saveProfile">
      <el-form-item :label="t('account.common.nickname')"><el-input v-model="profileForm.nickname" maxlength="32" show-word-limit autofocus :placeholder="t('account.profile.nicknamePlaceholder')" @keyup.enter="saveProfile" /><small class="form-hint">{{ t('account.profile.nicknameHint') }}</small></el-form-item>
        <el-form-item :label="t('account.profile.loginEmail')"><el-input :model-value="profileIdentity.email || t('account.profile.noEmailBound')" disabled /><small class="form-hint">{{ t('account.profile.loginEmailHint') }}</small></el-form-item>
      </el-form>
      <template #footer><el-button @click="profileDialogVisible = false">{{ t('account.common.cancel') }}</el-button><el-button type="primary" :loading="profileSaving" @click="saveProfile">{{ t('account.common.save') }}</el-button></template>
    </el-dialog>
      <AppFooter />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { predictionApi, favoriteApi, userApi } from '../../api'
import { authStorage } from '../../utils/authStorage'
import { displayLeagueName, displayTeamName } from '../../utils/teamNames'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import PageState from '../../components/layout/PageState.vue'
import { Camera, ChatLineSquare, Collection, Football, Lock, User } from '@element-plus/icons-vue'
import { useI18n } from '../../i18n'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const { t, locale } = useI18n()
const profileLoading = ref(true)
const activeTab = ref('overview')
const workspaceRef = ref(null)
const favoriteTab = ref('teams')
const favoriteKeyword = ref('')
const favoriteSort = ref('recent')
const favoriteMatchFilter = ref('all')
const historyStatus = ref('all')
const historyOutcome = ref('all')
const historyKeyword = ref('')
const historyDateRange = ref('all')
const profileIdentity = reactive({ userId: null, username: '', nickname: '', email: '', emailVerified: false, avatarData: '', nicknameUpdatedAt: null, role: 'USER', createdAt: null })
const accountProfile = reactive({ email: '', phone: '', matchRemindersEnabled: false })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const profileForm = reactive({ nickname: '' })
const profileDialogVisible = ref(false)
const profileSaving = ref(false)
const avatarInput = ref(null)
const avatarSaving = ref(false)
const avatarLoadFailed = ref(false)
const passwordSaving = ref(false)
const favorites = ref([])
const matchFavorites = ref([])
const history = ref([])
const personalStats = ref(null)
const publicStats = ref(null)
const historyCursor = ref('')
const historyHasMore = ref(false)
const historyLoading = ref(false)
const favoritesLoading = ref(false)
const matchFavoritesLoading = ref(false)
const historyError = ref('')
const favoritesError = ref('')
const matchFavoritesError = ref('')
const notifications = ref([])
const notificationUnread = ref(0)
const notificationLoading = ref(false)
const sessions = ref([])
const sessionsLoading = ref(false)
const statsLoadedAt = ref(null)

const tabs = computed(() => [
  { key: 'overview', label: t('account.profile.tabOverview'), icon: User },
  { key: 'favorites', label: t('account.profile.tabFavorites'), icon: Collection, count: favorites.value.length + matchFavorites.value.length },
  { key: 'history', label: t('account.profile.tabHistory'), icon: ChatLineSquare, count: personalStats.value?.total },
  { key: 'security', label: t('account.profile.tabSecurity'), icon: Lock, count: notificationUnread.value || undefined },
])
const displayName = computed(() => profileIdentity.nickname || profileIdentity.username || userStore.username || t('account.profile.fanFallback'))
const avatarInitial = computed(() => displayName.value.slice(0, 1).toUpperCase())
const avatarSrc = computed(() => avatarLoadFailed.value ? '' : profileIdentity.avatarData)
const emailVerified = computed(() => Boolean(profileIdentity.email && profileIdentity.emailVerified))
const publicAccuracy = computed(() => publicStats.value?.accuracy != null ? `${publicStats.value.accuracy}%` : '—')
const statsUpdatedText = computed(() => statsLoadedAt.value
  ? t('account.profile.statsUpdatedAt', { time: statsLoadedAt.value.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) })
  : t('account.profile.statsLoading'))
const teamLabel = (item, side = '') => displayTeamName(
  item?.[`${side}TeamName`] || item?.teamName || item?.name || '',
  item?.[`${side}TeamDisplayName`] || item?.[`${side}TeamDisplay`] || item?.teamDisplayName || item?.displayName || item?.nameEn || ''
)
const leagueLabel = item => displayLeagueName(
  item?.leagueName || item?.league?.name || '',
  item?.leagueDisplay || item?.leagueDisplayName || item?.league?.leagueDisplay || item?.league?.displayName || ''
)
const filteredFavorites = computed(() => {
  const keyword = favoriteKeyword.value.trim().toLowerCase()
  const rows = favorites.value.filter(item => !keyword || `${teamLabel(item)} ${leagueLabel(item)}`.toLowerCase().includes(keyword))
  return [...rows].sort((a, b) => favoriteSort.value === 'name' ? String(teamLabel(a)).localeCompare(String(teamLabel(b)), 'zh-CN') : new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
})
const filteredMatchFavorites = computed(() => {
  const keyword = favoriteKeyword.value.trim().toLowerCase()
  const rows = matchFavorites.value.filter(item => {
    const textMatch = !keyword || `${teamLabel(item, 'home')} ${teamLabel(item, 'away')} ${leagueLabel(item)}`.toLowerCase().includes(keyword)
    const state = favoriteMatchState(item)
    return textMatch && (favoriteMatchFilter.value === 'all' || state.key === favoriteMatchFilter.value)
  })
  return [...rows].sort((a, b) => favoriteSort.value === 'name' ? String(teamLabel(a, 'home')).localeCompare(String(teamLabel(b, 'home')), 'zh-CN') : new Date(a.matchTime || a.createdAt || 0) - new Date(b.matchTime || b.createdAt || 0))
})
const overviewFavorites = computed(() => [
  ...favorites.value.slice(0, 2).map(item => ({ key: `team-${item.id}`, initial: teamInitial(teamLabel(item)), title: teamLabel(item) || t('account.profile.unnamedTeam'), subtitle: t('account.profile.watchTeam'), action: () => goTeam(item.teamId, item.teamName, item.leagueName, item.logo) })),
  ...matchFavorites.value.slice(0, 2).map(item => ({ key: `match-${item.id}`, initial: 'VS', title: `${teamLabel(item, 'home') || t('account.profile.teamFallbackHome')} VS ${teamLabel(item, 'away') || t('account.profile.teamFallbackAway')}`, subtitle: t('account.profile.watchMatch'), action: () => goPrediction(item) }))
].slice(0, 3))
const filteredHistory = computed(() => history.value.filter(item => {
  const correct = Number(item.isCorrect) === 1; const wrong = Number(item.isCorrect) === 0
  const statusMatch = historyStatus.value === 'all' || (historyStatus.value === 'correct' && correct) || (historyStatus.value === 'wrong' && wrong) || (historyStatus.value === 'pending' && !correct && !wrong)
  const keyword = historyKeyword.value.trim().toLowerCase()
  const textMatch = !keyword || `${teamLabel(item, 'home')} ${teamLabel(item, 'away')} ${leagueLabel(item)}`.toLowerCase().includes(keyword)
  const date = new Date(item.createdAt || 0).getTime()
  const rangeDays = historyDateRange.value === '7d' ? 7 : historyDateRange.value === '30d' ? 30 : null
  const dateMatch = !rangeDays || (Number.isFinite(date) && date >= Date.now() - rangeDays * 86400000)
  return statusMatch && textMatch && dateMatch && (historyOutcome.value === 'all' || item.resultLabel === historyOutcome.value)
}))

const unwrap = value => value?.data ?? value
const normalizeHistoryItem = item => ({ ...item, homeWinProb: item.homeWinProb, drawProb: item.drawProb, awayWinProb: item.awayWinProb })
const loadIdentity = async () => {
  const result = unwrap(await userApi.getCurrentUser())
  Object.assign(profileIdentity, { userId: result?.userId || userStore.userId, username: result?.username || userStore.username, nickname: result?.nickname || result?.username || userStore.username, email: result?.email || '', emailVerified: Boolean(result?.emailVerified), avatarData: result?.avatarData || '', nicknameUpdatedAt: result?.nicknameUpdatedAt || null, role: result?.role || userStore.role, createdAt: result?.createdAt || null })
  userStore.email = profileIdentity.email
  userStore.emailVerified = profileIdentity.emailVerified
  userStore.avatarData = profileIdentity.avatarData
  avatarLoadFailed.value = false
  profileForm.nickname = profileIdentity.nickname
}
const loadHistoryPage = async (reset = false) => {
  if (historyLoading.value || (!reset && !historyHasMore.value)) return
  historyLoading.value = true; historyError.value = ''
  try {
    const result = unwrap(await predictionApi.getHistoryPage(reset ? '' : historyCursor.value, 20)); const items = Array.isArray(result?.items) ? result.items.map(normalizeHistoryItem) : []
    history.value = reset ? items : [...history.value, ...items]; historyCursor.value = result?.nextCursor || ''; historyHasMore.value = Boolean(result?.hasMore)
  } catch (error) {
    historyError.value = error?.message || t('account.profile.loadRetryLater')
    if (reset) { try { const fallback = unwrap(await predictionApi.getHistory(50)); history.value = Array.isArray(fallback) ? fallback.map(normalizeHistoryItem) : []; historyHasMore.value = false; historyError.value = '' } catch { history.value = [] } }
  } finally { historyLoading.value = false }
}
const loadPredictionStats = async () => { try { personalStats.value = unwrap(await predictionApi.getStatistics()) } catch { personalStats.value = null } }
const loadPublicStats = async () => { try { publicStats.value = unwrap(await predictionApi.getPerformance(7)) } catch { publicStats.value = null } }
const loadFavorites = async () => { favoritesLoading.value = true; favoritesError.value = ''; try { const result = unwrap(await favoriteApi.list()); favorites.value = Array.isArray(result?.items) ? result.items : (Array.isArray(result) ? result : []) } catch (error) { favoritesError.value = error?.message || t('account.profile.loadRetryLater'); favorites.value = [] } finally { favoritesLoading.value = false } }
const loadMatchFavorites = async () => { matchFavoritesLoading.value = true; matchFavoritesError.value = ''; try { const result = unwrap(await favoriteApi.listMatches()); matchFavorites.value = Array.isArray(result?.items) ? result.items : (Array.isArray(result) ? result : []) } catch (error) { matchFavoritesError.value = error?.message || t('account.profile.loadRetryLater'); matchFavorites.value = [] } finally { matchFavoritesLoading.value = false } }
const loadAccountProfile = async () => { try { const result = unwrap(await userApi.getPreferences()); const raw = result?.preferences || result || '{}'; const saved = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {}); accountProfile.email = saved.email || ''; accountProfile.phone = saved.phone || ''; accountProfile.matchRemindersEnabled = Boolean(saved.matchRemindersEnabled) } catch { /* preferences are optional */ } }
const loadNotifications = async () => {
  notificationLoading.value = true
  try {
    const result = unwrap(await userApi.getNotifications(50))
    notifications.value = Array.isArray(result?.items) ? result.items : []
    notificationUnread.value = Number(result?.unread || 0)
  } catch { notifications.value = []; notificationUnread.value = 0 } finally { notificationLoading.value = false }
}
const loadSessions = async () => {
  sessionsLoading.value = true
  try { const result = unwrap(await userApi.getSessions(userStore.refreshToken)); sessions.value = Array.isArray(result?.items) ? result.items : [] } catch { sessions.value = [] } finally { sessionsLoading.value = false }
}

const goMatches = () => router.push('/matches')
const goTeam = (teamId, teamName, leagueName, logo) => {
  const target = teamName || teamId
  if (!target) return
  router.push({
    path: `/team/${encodeURIComponent(String(target))}/squad`,
    query: { name: target, teamId: String(teamId || ''), league: leagueName || '', logo: logo || '', season: String(new Date().getFullYear()) }
  })
}
const getPublicMatchId = item => item?.matchId || item?.fixture?.id || item?.fixtureId || item?.externalMatchId || (item?.teams || item?.fixture ? item?.id : null)
const goPrediction = item => {
  const fixtureId = getPublicMatchId(item)
  if (!fixtureId) { ElMessage.warning(t('account.profile.missingMatchId')); return }
  router.push({
    path: `/prediction/${fixtureId}`,
    query: {
      homeId: item?.homeTeamId || item?.home?.id || '',
      awayId: item?.awayTeamId || item?.away?.id || '',
      homeName: item?.homeTeamName || item?.home?.name || '主队',
      awayName: item?.awayTeamName || item?.away?.name || '客队',
      homeLogo: item?.homeTeamLogo || item?.home?.logo || '',
      awayLogo: item?.awayTeamLogo || item?.away?.logo || '',
      leagueName: item?.leagueName || item?.league?.name || '',
      leagueId: item?.leagueId || item?.league?.id || '',
      matchTime: item?.matchTime || item?.fixture?.date || ''
    }
  })
}
const switchTab = tab => { activeTab.value = tab; nextTick(() => workspaceRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })) }
const moveProfileTab = direction => {
  const items = tabs.value
  const currentIndex = Math.max(0, items.findIndex(item => item.key === activeTab.value))
  const nextIndex = direction === 'first' ? 0 : direction === 'last' ? items.length - 1 : (currentIndex + Number(direction) + items.length) % items.length
  const key = items[nextIndex]?.key
  if (!key) return
  switchTab(key)
  window.requestAnimationFrame(() => document.getElementById(`profile-tab-${key}`)?.focus())
}
const openProfileEditor = () => { profileForm.nickname = displayName.value; profileDialogVisible.value = true }
const openAvatarPicker = () => { if (!avatarSaving.value) avatarInput.value?.click() }
const onAvatarSelected = event => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const allowed = ['image/png', 'image/jpeg', 'image/webp']
  if (!allowed.includes(file.type)) { ElMessage.warning(t('account.profile.avatarTypeWarning')); return }
  if (file.size > 512 * 1024) { ElMessage.warning(t('account.profile.avatarSizeWarning')); return }
  const reader = new FileReader()
  reader.onerror = () => ElMessage.error(t('account.profile.avatarReadError'))
  reader.onload = async () => {
    const avatarData = String(reader.result || '')
    avatarSaving.value = true
    try {
      const result = unwrap(await userApi.updateAvatar(avatarData))
      if (result?.ok === false) throw new Error(result.message || t('account.profile.avatarUpdateFailed'))
      profileIdentity.avatarData = avatarData
      userStore.avatarData = avatarData
      avatarLoadFailed.value = false
      ElMessage.success(t('account.profile.avatarUpdated'))
    } catch (error) { ElMessage.error(error?.message || t('account.profile.avatarUpdateFailed')) } finally { avatarSaving.value = false }
  }
  reader.readAsDataURL(file)
}
const teamInitial = name => (name || t('account.profile.teamFallbackShort')).slice(0, 1).toUpperCase()
const parseBusinessDate = value => {
  if (!value) return null
  const text = String(value).trim().replace(' ', 'T')
  const normalized = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}+08:00`
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}
const formatDate = value => { const date = parseBusinessDate(value); return date ? date.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : t('account.profile.unknownTime') }
const formatDateOnly = value => { const date = parseBusinessDate(value); return date ? date.toLocaleDateString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric' }) : '' }
const normalizedProb = value => { const number = Number(value); if (!Number.isFinite(number)) return 0; const ratio = number > 1 ? number / 100 : number; return Math.max(0, Math.min(1, ratio)) }
const pct = value => `${Math.round(normalizedProb(value) * 100)}%`
const resultLabel = label => ({ HOME_WIN: t('account.profile.predictedHomeWin'), DRAW: t('account.profile.predictedDraw'), AWAY_WIN: t('account.profile.predictedAwayWin') }[label] || t('account.profile.unknownResult'))
const resultTagType = label => ({ HOME_WIN: 'success', DRAW: 'info', AWAY_WIN: 'warning' }[label] || 'info')
const favoriteMatchState = item => {
  const time = parseBusinessDate(item.matchTime)?.getTime() ?? NaN
  if (!Number.isFinite(time)) return { key: 'unknown', label: t('account.profile.matchPending'), type: 'info' }
  if (time < Date.now()) return { key: 'finished', label: t('account.profile.matchFinished'), type: 'info' }
  return { key: 'upcoming', label: t('account.profile.matchUpcoming'), type: 'success' }
}
const formatMatchTime = value => { const date = parseBusinessDate(value); return date ? date.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', weekday: 'short', hour: '2-digit', minute: '2-digit' }) : t('account.profile.kickoffPending') }
const formatSessionExpiry = seconds => { const value = Number(seconds); if (!Number.isFinite(value) || value <= 0) return t('account.profile.expiringSoon'); if (value >= 86400) return t('account.profile.days', { count: Math.ceil(value / 86400) }); if (value >= 3600) return t('account.profile.hours', { count: Math.ceil(value / 3600) }); return t('account.profile.minutes', { count: Math.ceil(value / 60) }) }
const verificationLabel = item => Number(item.isCorrect) === 1 ? t('account.profile.hit') : Number(item.isCorrect) === 0 ? t('account.profile.miss') : t('account.profile.pendingVerification')
const verificationClass = item => Number(item.isCorrect) === 1 ? 'is-correct' : Number(item.isCorrect) === 0 ? 'is-wrong' : 'is-pending'
const notificationTime = value => value ? new Date(value).toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''
const markAllNotifications = async () => { await userApi.readAllNotifications().catch(() => {}); notifications.value.forEach(item => { item.read_at = item.read_at || new Date().toISOString() }); notificationUnread.value = 0 }
const markNotification = async item => { if (!item.read_at) { await userApi.readNotification(item.id).catch(() => {}); item.read_at = new Date().toISOString(); notificationUnread.value = Math.max(0, notificationUnread.value - 1) } }
const removeFav = async fav => { try { await ElMessageBox.confirm(t('account.profile.unfollowConfirm', { name: fav.teamName || t('account.profile.thisTeam') }), t('account.profile.unfollowTitle'), { type: 'warning' }); await favoriteApi.remove(fav.teamId); ElMessage.success(t('account.profile.unfollowed')); await loadFavorites() } catch (error) { if (error !== 'cancel') ElMessage.error(error?.message || t('account.profile.unfollowFailed')) } }
const removeMatchFav = async fav => { try { await ElMessageBox.confirm(t('account.profile.unfollowMatchConfirm'), t('account.profile.unfollowTitle'), { type: 'warning' }); await favoriteApi.removeMatch(getPublicMatchId(fav)); ElMessage.success(t('account.profile.unfollowed')); await loadMatchFavorites() } catch (error) { if (error !== 'cancel') ElMessage.error(error?.message || t('account.profile.unfollowFailed')) } }
const saveProfile = async () => { const nickname = profileForm.nickname.trim(); if (!nickname || nickname.length > 32) { ElMessage.warning(t('account.profile.nicknameInvalid')); return }; profileSaving.value = true; try { const result = unwrap(await userApi.updateProfile({ nickname })); if (result?.ok === false) throw new Error(result.message || t('account.profile.saveFailed')); profileIdentity.nickname = result?.nickname || nickname; profileIdentity.nicknameUpdatedAt = result?.nicknameUpdatedAt || profileIdentity.nicknameUpdatedAt; profileForm.nickname = profileIdentity.nickname; userStore.username = profileIdentity.nickname; authStorage.set('football_user', JSON.stringify({ username: profileIdentity.nickname, userId: userStore.userId, role: userStore.role })); profileDialogVisible.value = false; ElMessage.success(result?.message || t('account.profile.profileSaved')) } catch (error) { ElMessage.error(error?.message || t('account.profile.saveFailed')) } finally { profileSaving.value = false } }
const saveAccountProfile = async () => { profileSaving.value = true; try { await userApi.updatePreferences({ ...accountProfile }); ElMessage.success(t('account.profile.reminderSaved')) } catch (error) { ElMessage.error(error?.message || t('account.profile.saveFailed')) } finally { profileSaving.value = false } }
const revokeAllSessions = async () => { try { await ElMessageBox.confirm(t('account.profile.revokeConfirm'), t('account.profile.revokeTitle'), { type: 'warning' }); await userApi.revokeAllSessions(userStore.refreshToken); ElMessage.success(t('account.profile.revoked')); await loadSessions() } catch (error) { if (error !== 'cancel') ElMessage.error(error?.message || t('account.profile.operationFailed')) } }
const changePassword = async () => { if (passwordForm.newPassword.length < 8 || passwordForm.newPassword !== passwordForm.confirmPassword) { ElMessage.warning(t('account.profile.passwordInvalid')); return }; passwordSaving.value = true; try { const result = unwrap(await userApi.changePassword(passwordForm.currentPassword, passwordForm.newPassword)); if (result?.ok === false) throw new Error(result.message || t('account.profile.passwordUpdateFailed')); ElMessage.success(t('account.profile.passwordUpdated')); userStore.logout(); router.replace('/matches') } catch (error) { ElMessage.error(error?.message || t('account.profile.passwordUpdateFailed')) } finally { passwordSaving.value = false } }
const disableAccount = async () => { try { await ElMessageBox.confirm(t('account.profile.disableConfirm'), t('account.profile.disableTitle'), { type: 'warning', confirmButtonText: t('account.profile.confirmDisable'), cancelButtonText: t('account.common.cancel') }); const result = unwrap(await userApi.disableAccount()); if (result?.ok === false) throw new Error(result.message || t('account.profile.disableFailed')); ElMessage.success(t('account.profile.disabled')); userStore.logout(); router.replace('/matches') } catch (error) { if (error !== 'cancel') ElMessage.error(error?.message || t('account.profile.disableFailed')) } }
const onHistoryKeydown = event => {
  if ((event.key !== ' ' && event.code !== 'Space') || !event.target?.closest?.('.history-item')) return
  event.preventDefault()
  event.target.closest('.history-item')?.click()
}

onMounted(async () => { document.addEventListener('keydown', onHistoryKeydown); if (!userStore.token) { router.replace('/matches'); return }; const requestedTab = String(route.query.tab || ''); if (['overview', 'favorites', 'history', 'security'].includes(requestedTab)) activeTab.value = requestedTab; await Promise.allSettled([loadIdentity(), loadHistoryPage(true), loadPredictionStats(), loadPublicStats(), loadFavorites(), loadMatchFavorites(), loadAccountProfile(), loadNotifications(), loadSessions()]); statsLoadedAt.value = new Date(); profileLoading.value = false })
watch(locale, async () => {
  if (!userStore.token) return
  await Promise.allSettled([
    loadHistoryPage(true),
    loadPublicStats(),
    loadFavorites(),
    loadMatchFavorites(),
    loadNotifications(),
    loadSessions()
  ])
})
onBeforeUnmount(() => document.removeEventListener('keydown', onHistoryKeydown))
</script>

<style scoped>
.profile-page { min-height: 100vh; }
.profile-layout { min-height: 100vh; flex-direction: column; background: transparent; }
.profile-main { width: min(1180px, 100%); padding: 22px 24px 48px; margin: 0 auto; }
.profile-loading { padding: 28px; min-height: 480px; }
.profile-heading { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; margin-bottom:14px; }
.profile-heading h1 { margin:6px 0 5px; color:var(--ff-text-strong); font-size:28px; letter-spacing:-.03em; }
.profile-heading p, .workspace-title-row p { margin:0; color:var(--ff-text-muted); font-size:13px; line-height:1.6; }
.section-kicker { color:var(--ff-primary); font-size:10px; font-weight:800; letter-spacing:.14em; }
.identity-card { display:flex; align-items:center; justify-content:space-between; gap:24px; padding:19px 22px; margin-bottom:14px; }
.identity-main { display:flex; align-items:center; gap:16px; min-width:0; }
.avatar-editor { position:relative; flex:none; width:76px; height:76px; }.identity-avatar { flex:none; color:#fff; background:var(--ff-primary); font-size:26px; font-weight:800; }.avatar-edit-button { position:absolute; right:-4px; bottom:-4px; display:grid; place-items:center; width:27px; height:27px; padding:0; border:2px solid var(--ff-surface); border-radius:50%; color:var(--ff-text-strong); background:var(--ff-gold); cursor:pointer; }.avatar-edit-button:hover { color:var(--ff-primary); background:var(--ff-gold-soft); }.avatar-edit-button:disabled { cursor:wait; opacity:.6; }.avatar-input { display:none; }
.identity-copy { min-width:0; }.identity-copy h2 { margin:5px 0 3px; color:var(--ff-text-strong); font-size:23px; }.identity-email { margin:0; color:var(--ff-text-muted); font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.identity-meta { display:flex; flex-wrap:wrap; align-items:center; gap:9px; margin-top:9px; color:var(--ff-text-faint); font-size:12px; }
.identity-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:8px; flex:none; }
.benchmark-strip { display:flex; align-items:center; justify-content:space-between; gap:20px; padding:15px 18px; margin-bottom:16px; background:var(--ff-surface-soft); }.benchmark-strip > div:first-child { display:flex; flex-direction:column; gap:3px; }.benchmark-strip strong { color:var(--ff-text-strong); font-size:14px; }.benchmark-strip span:last-child { color:var(--ff-text-muted); font-size:12px; }.benchmark-meta { display:flex; flex-wrap:wrap; gap:8px; margin-top:5px; }.benchmark-meta span { color:var(--ff-text-faint) !important; font-size:11px !important; }.benchmark-value { display:flex; flex-direction:column; align-items:flex-end; gap:3px; }.benchmark-value strong { color:var(--ff-primary); font-family:var(--ff-mono); font-size:22px; }.benchmark-value small { color:var(--ff-text-muted); font-size:11px; }
.workspace-panel { position:relative; overflow:visible; scroll-margin-top:76px; }.workspace-tabs { position:sticky; top:60px; z-index:4; display:flex; gap:0; padding:0; border-bottom:1px solid var(--ff-border); background:var(--ff-surface); overflow-x:auto; }.workspace-tabs button { display:inline-flex; align-items:center; gap:6px; min-height:40px; padding:0 14px; border:0; border-bottom:2px solid transparent; border-radius:0; color:var(--ff-text-muted); background:transparent; cursor:pointer; white-space:nowrap; font-size:13px; font-weight:700; transition:border-color var(--ff-transition-fast), color var(--ff-transition-fast), background var(--ff-transition-fast); }.workspace-tabs button:hover, .workspace-tabs button.active { color:var(--ff-primary); background:var(--ff-surface-soft); }.workspace-tabs button.active { border-bottom-color:var(--ff-primary); }.workspace-tabs button:focus-visible { outline:2px solid var(--ff-primary); outline-offset:-2px; }.workspace-tabs em { min-width:18px; padding:1px 5px; border-radius:99px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:10px; font-style:normal; text-align:center; }
.workspace-content { min-height:0; padding:16px 22px 22px; }.workspace-content > :first-child { margin-top:0; }.overview-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); align-items:start; gap:16px; }.overview-card, .security-card { min-width:0; padding:18px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); }.overview-card { display:flex; flex-direction:column; }.content-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:14px; }.content-heading h2 { margin:4px 0 0; color:var(--ff-text-strong); font-size:17px; }.overview-summary { display:flex; align-items:baseline; flex-wrap:wrap; gap:7px 9px; padding:0 0 13px; margin-bottom:12px; border-bottom:1px solid var(--ff-border); }.overview-summary strong { color:var(--ff-text-strong); font-family:var(--ff-mono); font-size:24px; line-height:1; }.overview-summary span { color:var(--ff-text-muted); font-size:12px; }.overview-summary small { width:100%; color:var(--ff-text-faint); font-size:11px; }.mini-follow-list, .recent-predictions { display:flex; flex:1; flex-direction:column; gap:7px; }.mini-follow-item, .recent-prediction { display:flex; align-items:center; gap:9px; min-width:0; padding:9px 10px; border:1px solid transparent; border-radius:var(--ff-radius-sm); background:var(--ff-surface-quiet); }.mini-follow-item:hover, .recent-prediction:hover { border-color:var(--ff-border-strong); }.overview-card > .state-shell { padding:8px 0 0; border:0; box-shadow:none; background:transparent; }.overview-card > .state-shell .state-block { min-height:110px; }.mini-icon, .library-avatar { display:grid; flex:none; place-items:center; width:32px; height:32px; border-radius:50%; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:12px; font-weight:800; }.mini-follow-item > div, .recent-prediction > div:first-child { display:flex; flex:1; min-width:0; flex-direction:column; gap:2px; }.mini-follow-item strong, .recent-prediction strong { overflow:hidden; color:var(--ff-text); font-size:12px; text-overflow:ellipsis; white-space:nowrap; }.mini-follow-item small, .recent-prediction small { overflow:hidden; color:var(--ff-text-muted); font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.recent-prediction { cursor:pointer; }.recent-prediction strong b, .library-copy b, .history-main b { color:var(--ff-text-faint); font-size:10px; }
.workspace-title-row { align-items:center; margin-bottom:12px; }.favorite-tabs { margin-bottom:10px; }.library-toolbar { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:14px; }.library-toolbar .el-input { flex:1 1 220px; }.library-toolbar .el-select { width:130px; }.library-list { display:flex; flex-direction:column; gap:8px; }.library-item { display:flex; align-items:center; gap:12px; padding:13px 14px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); transition:border-color var(--ff-transition-fast), background-color var(--ff-transition-fast); }.library-item:hover { border-color:var(--ff-border-strong); background:var(--ff-surface-soft); }.library-avatar { width:38px; height:38px; }.match-avatar { border-radius:var(--ff-radius-sm); color:var(--ff-accent); background:var(--ff-gold-soft); }.library-copy { display:flex; flex:1; min-width:0; flex-direction:column; gap:4px; }.library-copy strong { overflow:hidden; color:var(--ff-text); font-size:14px; text-overflow:ellipsis; white-space:nowrap; }.library-copy small { color:var(--ff-text-muted); font-size:11px; }.library-status { flex:none; }.library-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:4px; }
.history-toolbar { display:flex; align-items:center; flex-wrap:wrap; justify-content:flex-end; gap:8px; margin-bottom:14px; }.history-search { flex:1 1 190px; }.history-toolbar .el-select { width:124px; }.history-total { color:var(--ff-text-muted); font-size:12px; }.history-list { display:flex; flex-direction:column; gap:9px; }.history-item { display:grid; grid-template-columns:minmax(200px,1.4fr) minmax(88px,.45fr) minmax(180px,.9fr) auto; align-items:center; gap:16px; padding:15px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface); cursor:pointer; outline:none; transition:border-color var(--ff-transition-fast), background-color var(--ff-transition-fast); }.history-item:hover, .history-item:focus-visible { border-color:var(--ff-primary); background:var(--ff-surface-soft); }.history-main { display:flex; min-width:0; flex-direction:column; gap:4px; }.history-main strong { overflow:hidden; color:var(--ff-text); font-size:13px; text-overflow:ellipsis; white-space:nowrap; }.history-main small, .history-date { color:var(--ff-text-muted); font-size:11px; }.history-result { display:flex; flex-direction:column; align-items:flex-start; gap:7px; }.verification-status { font-size:11px; font-weight:700; }.is-correct { color:var(--ff-success); }.is-wrong { color:var(--ff-danger); }.is-pending { color:var(--ff-warning); }.history-probability { min-width:0; }.prob-stack { display:flex; height:8px; overflow:hidden; border-radius:99px; background:var(--ff-border); }.seg { height:100%; min-width:0; }.seg-home { background:var(--ff-primary); }.seg-draw { background:var(--ff-border-strong); }.seg-away { background:var(--ff-accent-2); }.prob-nums { display:flex; justify-content:space-between; gap:6px; margin-top:5px; color:var(--ff-text-muted); font-family:var(--ff-mono); font-size:10px; }.c-home { color:var(--ff-primary); }.c-draw { color:var(--ff-text-muted); }.c-away { color:var(--ff-accent); }.history-view { justify-self:end; }.history-footer { display:flex; justify-content:center; padding-top:18px; color:var(--ff-text-faint); font-size:12px; }
.security-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; }.security-card h3 { margin:0; color:var(--ff-text-strong); font-size:15px; }.security-card-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:14px; }.security-count { color:var(--ff-text-faint); font-size:11px; }.inline-badge { margin-left:6px; }.account-row { display:flex; align-items:center; gap:10px; min-height:36px; border-bottom:1px solid var(--ff-border); }.account-row:last-of-type { border-bottom:0; }.account-row span { width:64px; color:var(--ff-text-muted); font-size:12px; }.account-row strong { flex:1; min-width:0; overflow:hidden; color:var(--ff-text); font-size:13px; text-overflow:ellipsis; white-space:nowrap; }.security-help { margin:10px 0 14px; color:var(--ff-text-muted); font-size:12px; line-height:1.6; }.security-form .el-form-item { margin-bottom:12px; }.form-submit { width:100%; margin-top:4px; }.form-hint { display:block; margin-top:5px; color:var(--ff-text-muted); font-size:11px; line-height:1.5; }.session-list { display:flex; flex-direction:column; gap:7px; }.session-item { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 11px; border-radius:var(--ff-radius-sm); background:var(--ff-surface-quiet); }.session-item > div { display:flex; min-width:0; flex-direction:column; gap:3px; }.session-item strong { overflow:hidden; color:var(--ff-text); font-size:12px; text-overflow:ellipsis; white-space:nowrap; }.session-item small { color:var(--ff-text-muted); font-size:11px; }.danger-card { border-color:rgba(196,61,53,.28); }.danger-card h3 { color:var(--ff-danger); }.danger-actions { display:flex; flex-wrap:wrap; gap:8px; }.profile-notification-list { display:flex; flex-direction:column; gap:6px; margin-top:14px; }.profile-notification-item { display:flex; gap:10px; width:100%; padding:10px; border:1px solid transparent; border-radius:var(--ff-radius-sm); background:var(--ff-surface-quiet); color:var(--ff-text); text-align:left; cursor:pointer; }.profile-notification-item:hover, .profile-notification-item.is-unread { border-color:var(--ff-primary); background:var(--ff-primary-soft); }.profile-notification-item > span:last-child { display:flex; min-width:0; flex-direction:column; gap:3px; }.profile-notification-item strong { font-size:12px; }.profile-notification-item small { color:var(--ff-text-muted); font-size:11px; line-height:1.5; }
@media (max-width: 960px) { .history-item { grid-template-columns:minmax(180px,1fr) minmax(85px,.45fr) minmax(150px,.8fr) auto; gap:10px; } }
@media (max-width: 760px) { .profile-main { padding:18px 12px 40px; }.profile-heading, .identity-card { align-items:flex-start; flex-direction:column; }.profile-heading h1 { font-size:24px; }.identity-card { padding:18px; }.identity-actions { width:100%; justify-content:stretch; }.identity-actions .el-button { flex:1 1 auto; }.overview-grid, .security-grid { grid-template-columns:1fr; }.workspace-content { padding:14px; }.workspace-tabs { top:60px; }.history-toolbar { align-items:stretch; flex-direction:column; }.history-toolbar .el-radio-group { max-width:100%; overflow-x:auto; }.history-toolbar .el-select, .history-search { width:100%; flex-basis:auto; }.history-item { display:flex; align-items:stretch; flex-wrap:wrap; gap:11px; }.history-main { flex:1 1 calc(100% - 90px); }.history-result { flex:0 0 82px; align-items:flex-end; }.history-probability { flex:1 1 calc(100% - 52px); }.history-view { flex:0 0 auto; align-self:center; }.library-toolbar { flex-direction:column; }.library-toolbar .el-input, .library-toolbar .el-select { width:100%; flex-basis:auto; }.library-item { align-items:flex-start; flex-wrap:wrap; }.library-copy { flex-basis:calc(100% - 52px); }.library-status { margin-left:50px; }.library-actions { width:100%; justify-content:flex-start; margin-left:50px; }.benchmark-strip { align-items:flex-start; flex-direction:column; }.benchmark-value { align-items:flex-start; }.workspace-tabs button { padding:0 11px; } }
.profile-main { padding-top: 24px; }
.profile-heading { margin-bottom: 12px; }
.identity-card { padding: 16px 20px; margin-bottom: 12px; }
.benchmark-strip { margin-bottom: 12px; }
.workspace-content { padding-top: 14px; }
.security-card { padding: 15px; }
@media (max-width: 760px) { .profile-main { padding-top: 16px; } .profile-heading { gap: 10px; } }
</style>
