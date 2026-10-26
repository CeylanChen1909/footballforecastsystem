<template>
  <div class="agent-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="" :brand-icon="ChatLineSquare" active-path="/agent" />

    <main id="app-main" class="agent-main" tabindex="-1">
      <section v-if="!userStore.token" class="agent-teaser ff-panel" :aria-label="t('agent.teaser.aria')">
        <div class="agent-teaser-intro">
          <span class="launcher-mark"><el-icon :size="22"><ChatLineSquare /></el-icon></span>
          <div>
            <h1>{{ t('agent.teaser.title') }}</h1>
            <p>{{ t('agent.teaser.body') }}</p>
          </div>
        </div>
        <div class="agent-teaser-examples" :aria-label="t('agent.teaser.examplesAria')">
          <button v-for="task in teaserPrompts" :key="task.label" type="button" class="agent-teaser-card" @click="promptLogin">
            <strong>{{ task.label }}</strong>
            <small>{{ task.description }}</small>
          </button>
        </div>
        <el-button type="primary" size="large" @click="promptLogin">{{ t('agent.teaser.cta') }}</el-button>
        <p class="agent-teaser-note">{{ t('agent.teaser.note') }}</p>
      </section>

      <section v-else class="agent-shell">
        <header class="agent-toolbar">
          <div class="toolbar-leading">
            <el-button class="toolbar-icon" text :icon="ChatLineSquare" :aria-label="sessionDrawerVisible ? t('agent.toolbar.closeSessions') : t('agent.toolbar.openSessions')" @click="sessionDrawerVisible = !sessionDrawerVisible" />
            <div class="toolbar-title">
              <strong>{{ currentSessionTitle }}</strong>
              <span v-if="matchContext" class="toolbar-context">{{ contextHomeName }} vs {{ contextAwayName }}</span>
              <span v-else class="toolbar-context">{{ healthLabel }}<span v-if="latestAssistant && latestAssistant.lastRunMeta"> · {{ latestAssistant.lastRunMeta }}</span></span>
            </div>
          </div>
          <div class="toolbar-actions">
            <span class="service-state" :class="`is-${healthState}`"><i></i>{{ healthLabel }}</span>
            <el-tag v-if="currentIntent" class="intent-tag" effect="plain" type="info">{{ intentLabel }}</el-tag>
            <el-button class="new-session-button" size="small" type="primary" :icon="Plus" @click="startNewSession">{{ t('agent.toolbar.newSession') }}</el-button>
            <el-dropdown trigger="click" @command="handleConversationCommand">
              <el-button class="more-button" text :aria-label="t('agent.toolbar.moreAria')">{{ t('agent.toolbar.more') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="evidence" :icon="Connection">{{ t('agent.toolbar.evidence') }}</el-dropdown-item>
                  <el-dropdown-item command="export" :icon="Download" :disabled="!messages.length">{{ t('agent.toolbar.export') }}</el-dropdown-item>
                  <el-dropdown-item command="share" :icon="Share" :disabled="!messages.length">{{ t('agent.toolbar.share') }}</el-dropdown-item>
                  <el-dropdown-item divided command="clear" :icon="Delete" :disabled="!messages.length || sending">{{ t('agent.toolbar.clear') }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </header>

        <div class="agent-layout" :class="{ 'has-session-drawer': sessionDrawerVisible }">
          <aside v-if="sessionDrawerVisible" class="session-drawer ff-panel" :aria-label="t('agent.sessions.aria')">
            <div class="drawer-heading">
              <div><span class="drawer-eyebrow">{{ t('agent.sessions.eyebrow') }}</span><strong>{{ t('agent.sessions.continue') }}</strong></div>
              <el-button text :icon="Close" :aria-label="t('agent.toolbar.closeSessions')" @click="sessionDrawerVisible = false" />
            </div>
            <el-input v-model="sessionKeyword" class="session-search" clearable size="small" :prefix-icon="Search" :placeholder="t('agent.sessions.searchPlaceholder')" />
            <div class="session-filters" role="tablist" :aria-label="t('agent.sessions.filtersAria')">
              <button v-for="filter in sessionFilters" :key="filter.value" type="button" :class="{ active: sessionFilter === filter.value }" @click="sessionFilter = filter.value">{{ filter.label }}</button>
            </div>
            <div class="session-list">
              <div v-for="session in visibleSessions" :key="session.id" class="session-item" :class="{ active: session.id === sessionId }" role="button" tabindex="0" @click="selectSession(session.id)" @keydown.enter="selectSession(session.id)" @keydown.space.prevent="selectSession(session.id)">
                <div class="session-item-top"><span class="session-title">{{ session.title || t('agent.common.untitledSession') }}</span><span class="session-state" :class="`is-${sessionState(session)}`">{{ sessionStateLabel(session) }}</span></div>
                <span class="session-preview">{{ session.preview || t('agent.sessions.historyPreview') }}</span>
                <div class="session-item-meta"><span v-if="session.hasMatchContext">{{ t('agent.sessions.matchContext') }}</span><time>{{ formatRelativeTime(session.updatedAt) }}</time></div>
                <span class="session-item-actions">
                  <el-button text size="small" :icon="Edit" :aria-label="t('agent.sessions.renameAria')" @click.stop="renameSession(session)" />
                  <el-button text size="small" :icon="Delete" :aria-label="t('agent.sessions.deleteAria')" @click.stop="deleteSession(session)" />
                </span>
              </div>
              <div v-if="!visibleSessions.length" class="session-empty"><el-icon :size="22"><ChatLineSquare /></el-icon><span>{{ t('agent.sessions.empty') }}</span><small>{{ t('agent.sessions.emptyHint') }}</small></div>
            </div>
          </aside>

          <section class="conversation-panel ff-panel">
            <div v-if="matchContext" class="match-context-strip">
              <div class="context-mark"><el-icon><Trophy /></el-icon></div>
              <div class="context-copy"><span>{{ t('agent.conversation.currentMatch') }}</span><strong>{{ contextHomeName }} <em>vs</em> {{ contextAwayName }}</strong><small>{{ contextLeagueName || t('agent.common.leagueTbd') }}<span v-if="matchContext.matchTime"> · {{ formatMatchTime(matchContext.matchTime) }}</span></small></div>
              <el-button text size="small" :disabled="sending" @click="useMatchPrompt">{{ t('agent.conversation.startAnalysis') }}</el-button>
            </div>

            <div ref="messageList" class="message-list" aria-live="polite" @scroll="handleMessageScroll">
              <div v-if="!messages.length" class="task-launcher">
                <div class="task-launcher-intro"><span class="launcher-mark"><el-icon :size="20"><ChatLineSquare /></el-icon></span><div><h1>{{ t('agent.conversation.launcherTitle') }}</h1><p>{{ t('agent.conversation.launcherBody') }}</p></div></div>
                <div class="task-launcher-list">
                  <button v-for="task in taskPrompts" :key="task.label" type="button" :disabled="sending" @click="sendMessage(task.prompt)"><span><strong>{{ task.label }}</strong><small>{{ task.description || t('agent.conversation.taskFallback') }}</small></span><el-icon><ArrowDown /></el-icon></button>
                </div>
              </div>

              <article v-for="(message, index) in messages" :key="message.id || `${message.role}-${index}`" class="message-row" :class="`is-${message.role}`">
                <div class="message-avatar" aria-hidden="true"><el-icon v-if="message.role === 'assistant'"><ChatLineSquare /></el-icon><span v-else>{{ userInitial }}</span></div>
                <div class="message-bubble">
                  <div class="message-meta"><strong>{{ message.role === 'assistant' ? t('agent.common.assistant') : t('agent.common.you') }}</strong><span v-if="message.pending">{{ runPhaseLabel || t('agent.common.analyzing') }}</span><span v-else-if="message.role === 'assistant' && message.intent" class="message-intent">{{ intentLabelFor(message.intent) }}</span></div>
                  <div v-if="message.content && message.role === 'assistant'" class="message-content markdown-body" v-html="renderMessageMarkdown(message.content)"></div>
                  <p v-else-if="message.content" class="message-content">{{ message.content }}</p>
                  <div v-if="message.pending" class="typing-indicator"><i></i><i></i><i></i></div>
                  <div v-if="message.role === 'assistant' && !message.pending && (message.dataQuality || message.evidence?.length || message.toolEvents?.length || message.unknowns?.length)" class="answer-meta">
                    <span class="quality-badge" :class="`is-${qualityMeta(message).tone}`"><i></i>{{ qualityMeta(message).label }}</span>
                    <span v-if="message.evidence?.length || message.toolEvents?.length">{{ t('agent.common.sourcesCount', { count: evidenceCount(message) }) }}</span>
                    <span v-if="message.dataFreshness || lastDataFreshness">{{ t('agent.common.updatedAt', { time: formatEvidenceTime(message.dataFreshness || lastDataFreshness) }) }}</span>
                    <button type="button" class="evidence-link" @click="openEvidence(message)">{{ t('agent.conversation.evidenceLink') }}</button>
                  </div>
                  <div v-if="message.unknowns?.length" class="uncertainty-note"><el-icon><InfoFilled /></el-icon><span>{{ t('agent.common.unknownCount', { count: message.unknowns.length }) }}</span></div>
                  <div v-if="message.artifacts?.length" class="message-artifacts">
                    <div v-for="artifact in message.artifacts" :key="`${message.id}-${artifact.type}`" class="artifact-card" :class="`artifact-${artifact.type}`">
                      <div v-if="artifact.type === 'match'" class="artifact-match"><span class="artifact-label">{{ t('agent.artifact.match') }}</span><strong>{{ artifactHomeName(artifact) }} <em>vs</em> {{ artifactAwayName(artifact) }}</strong><small>{{ artifactLeagueName(artifact) || t('agent.common.leagueTbd') }}<span v-if="artifact.matchTime"> · {{ formatMatchTime(artifact.matchTime) }}</span></small></div>
                      <div v-else-if="artifact.type === 'prediction'" class="artifact-prediction"><span class="artifact-label">{{ t('agent.artifact.prediction') }}</span><strong>{{ artifact.predictionAvailable === false ? t('agent.artifact.predictionUnavailable') : (artifact.resultLabel || t('agent.artifact.noResult')) }}</strong><div class="artifact-probs"><span>{{ t('agent.common.probHome', { value: formatProbability(artifact.homeWinProb) }) }}</span><span>{{ t('agent.common.probDraw', { value: formatProbability(artifact.drawProb) }) }}</span><span>{{ t('agent.common.probAway', { value: formatProbability(artifact.awayWinProb) }) }}</span></div><small v-if="artifact.featureStatus">{{ artifact.featureStatus }}</small></div>
                      <div v-else-if="artifact.type === 'squad'" class="artifact-squad"><span class="artifact-label">{{ t('agent.artifact.squad') }}</span><strong>{{ artifactTeamName(artifact) || t('agent.common.team') }} · {{ t('agent.common.peopleCount', { count: artifact.playerCount || 0 }) }}</strong><small v-if="artifact.status && artifact.status !== 'AVAILABLE'">{{ artifact.message || evidenceStatusLabel(artifact.status.toLowerCase()) }}</small><small v-else>{{ t('agent.artifact.squadRead') }}</small></div>
                      <div v-else-if="artifact.type === 'schedule'" class="artifact-schedule"><span class="artifact-label">{{ t('agent.artifact.schedule') }}</span><strong>{{ t('agent.common.matchesCount', { count: artifact.total || 0 }) }}<span v-if="artifact.truncated">{{ t('agent.artifact.scheduleTruncated') }}</span></strong><small>{{ artifact.windowType === 'NEXT_24_HOURS' ? t('agent.artifact.next24h') : (artifactLeagueName(artifact) || t('agent.artifact.mainCrawler')) }}<span v-if="artifact.predictionSummary"> · {{ formatPredictionSummary(artifact.predictionSummary) }}</span></small></div>
                      <div v-else class="artifact-team"><span class="artifact-label">{{ t('agent.artifact.team') }}</span><strong>{{ artifactTeamName(artifact) || t('agent.common.team') }}</strong><small>{{ t('agent.common.recentMatches', { count: artifact.recentMatchCount || 0 }) }}</small></div>
                    </div>
                  </div>
                  <div v-if="message.actions?.length" class="message-actions"><el-button v-for="action in message.actions" :key="`${message.id}-${action.type}`" size="small" type="primary" plain @click="runAgentAction(action)">{{ action.label }}</el-button></div>
                  <div v-if="message.role === 'assistant' && !message.pending && message.content" class="message-feedback"><el-button text size="small" :icon="CopyDocument" @click="copyText(message.content)">{{ t('agent.feedback.copy') }}</el-button><el-dropdown trigger="click" @command="command => handleMessageCommand(command, message, index)"><el-button text size="small">{{ t('agent.feedback.more') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="regenerate" :icon="Refresh">{{ t('agent.feedback.regenerate') }}</el-dropdown-item><el-dropdown-item command="helpful">{{ t('agent.feedback.helpful') }}</el-dropdown-item><el-dropdown-item command="not-helpful">{{ t('agent.feedback.notHelpful') }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
                </div>
              </article>
              <button v-if="newContentAvailable" class="new-content-button" type="button" @click="scrollToBottom(true)">{{ t('agent.conversation.newContent') }}</button>
            </div>

            <div v-if="sending" class="run-strip" aria-live="polite"><div class="run-strip-label">{{ t('agent.runStrip.label') }}</div><div class="run-steps"><span v-for="(step, index) in runSteps" :key="step.key" :class="runStepClass(index)"><i></i>{{ step.label }}</span></div></div>
            <form class="composer" @submit.prevent="sendMessage()">
              <div class="composer-scope"><span class="scope-dot"></span><span v-if="matchContext">{{ t('agent.conversation.scopeMatch') }}</span><span v-else>{{ t('agent.conversation.scopeGeneral') }}</span><span class="composer-hint">{{ t('agent.conversation.composerHint') }}</span></div>
              <el-input v-model="composerText" type="textarea" :rows="3" resize="none" maxlength="2000" show-word-limit :placeholder="t('agent.conversation.placeholder')" :disabled="sending" @keydown.enter.exact.prevent="sendMessage()" @keydown.enter.shift.stop />
              <div class="composer-actions"><span class="composer-status" :class="{ active: sending }"><span class="status-dot"></span>{{ sending ? (runPhaseLabel || t('agent.common.analyzing')) : t('agent.common.ready') }}</span><div><el-button v-if="sending" type="warning" plain :icon="Close" @click.prevent="cancelRequest">{{ t('agent.conversation.stop') }}</el-button><el-button v-else type="primary" native-type="submit" :icon="Promotion" :disabled="!composerText.trim()">{{ t('agent.conversation.send') }}</el-button></div></div>
            </form>
          </section>

          <aside v-if="evidenceDrawerVisible" class="evidence-drawer ff-panel" :aria-label="t('agent.evidence.aria')">
            <div class="drawer-heading"><div><span class="drawer-eyebrow">{{ t('agent.evidence.eyebrow') }}</span><strong>{{ t('agent.evidence.title') }}</strong></div><el-button text :icon="Close" :aria-label="t('agent.evidence.closeAria')" @click="evidenceDrawerVisible = false" /></div>
            <div v-if="selectedEvidenceMessage" class="evidence-content">
              <div class="evidence-summary"><span class="quality-badge" :class="`is-${qualityMeta(selectedEvidenceMessage).tone}`"><i></i>{{ qualityMeta(selectedEvidenceMessage).label }}</span><strong>{{ t('agent.common.sourceCount', { count: evidenceCount(selectedEvidenceMessage) }) }}</strong><small v-if="selectedEvidenceMessage.dataFreshness || lastDataFreshness">{{ t('agent.common.updatedAt', { time: formatEvidenceTime(selectedEvidenceMessage.dataFreshness || lastDataFreshness) }) }}</small></div>
              <section v-if="selectedEvidenceMessage.evidence?.length" class="evidence-section"><h3>{{ t('agent.evidence.results') }}</h3><div class="evidence-source" v-for="source in selectedEvidenceMessage.evidence" :key="`${selectedEvidenceMessage.id}-${source.tool || source.source}`"><span class="source-status" :class="`is-${evidenceTagType(source.status)}`"></span><div><strong>{{ source.label || source.source || toolLabel(source.tool) }}</strong><small>{{ evidenceStatusLabel(source.status) }}<span v-if="source.sourceUpdatedAt">{{ t('agent.common.updatedAtSuffix', { time: formatEvidenceTime(source.sourceUpdatedAt) }) }}</span><span v-else-if="source.observedAt">{{ t('agent.common.readAtSuffix', { time: formatEvidenceTime(source.observedAt) }) }}</span></small></div></div></section>
              <section v-if="selectedEvidenceMessage.facts?.length" class="evidence-section"><h3>{{ t('agent.evidence.verified') }}</h3><div class="fact-list"><span v-for="fact in selectedEvidenceMessage.facts" :key="`${selectedEvidenceMessage.id}-${fact.tool}`">{{ fact.label || fact.tool }} · {{ t('agent.common.recordsCount', { count: fact.recordCount ?? 0 }) }}</span></div></section>
              <section v-if="selectedEvidenceMessage.unknowns?.length" class="evidence-section"><h3>{{ t('agent.evidence.unverified') }}</h3><div class="fact-list is-warning"><span v-for="unknown in selectedEvidenceMessage.unknowns" :key="`${selectedEvidenceMessage.id}-${unknown.tool}`">{{ unknown.label || unknown.tool }} · {{ unknown.message || evidenceStatusLabel(unknown.status) }}</span></div></section>
              <section v-if="selectedEvidenceMessage.toolEvents?.length" class="evidence-section"><h3>{{ t('agent.evidence.process') }}</h3><div v-for="(trace, traceIndex) in selectedEvidenceMessage.toolEvents" :key="`${selectedEvidenceMessage.id}-${trace.eventId || traceIndex}`" class="tool-trace-row"><span class="trace-dot" :class="`is-${trace.status || 'running'}`"></span><span>{{ trace.type === 'tool_start' ? t('agent.evidence.traceRead', { tool: toolLabel(trace.tool) }) : t(trace.status === 'error' ? 'agent.evidence.traceFailed' : 'agent.evidence.traceCompleted', { tool: toolLabel(trace.tool) }) }}</span><small v-if="trace.latencyMs">{{ trace.latencyMs }}ms</small></div></section>
            </div>
            <div v-else class="drawer-empty"><el-icon><Connection /></el-icon><p>{{ t('agent.evidence.empty') }}</p></div>
            <div class="evidence-note"><el-icon><InfoFilled /></el-icon><p>{{ t('agent.evidence.note') }}</p></div>
          </aside>
        </div>
      </section>
    </main>
      <AppFooter />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { ElMessage } from 'element-plus'
import { agentApi, analyticsApi } from '../../api'
import { authStorage } from '../../utils/authStorage'
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { renderMarkdown } from '../../utils/markdown'
import { displayLeagueName, displayTeamName } from '../../utils/teamNames'
import { useI18n } from '../../i18n'
import { ArrowDown, ChatLineSquare, Close, Connection, CopyDocument, Delete, Download, Edit, InfoFilled, Plus, Promotion, Refresh, Search, Share, Trophy } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()

const STORAGE_KEY = 'football_agent_sessions_v1'
const sessions = ref([])
const sessionKeyword = ref('')
const sessionId = ref('')
const messages = ref([])
const composerText = ref('')
const messageList = ref(null)
const sending = ref(false)
const healthState = ref('checking')
const tools = ref([])
const currentIntent = ref('')
const lastLatency = ref(null)
const lastRoute = ref(null)
// Structured phase state (`{ key, tool, status }`) so the visible label can be
// re-translated when the locale changes instead of freezing at assignment time.
const runPhase = ref(null)
const runPhaseKey = () => typeof runPhase.value === 'string' ? runPhase.value : (runPhase.value?.key || '')
// Desktop keeps the session switcher visible as part of the workspace grid;
// on narrow screens it becomes an on-demand drawer opened by the toolbar icon.
const sessionDrawerVisible = ref(typeof window === 'undefined' ? true : window.innerWidth > 900)
const evidenceDrawerVisible = ref(false)
const selectedEvidenceMessage = ref(null)
const sessionFilter = ref('all')
const newContentAvailable = ref(false)
const lastDataFreshness = ref('')
const activeStreamId = ref('')
let abortController = null

const taskPrompts = computed(() => [
  // Both the display copy and the request value follow the active locale, so
  // an English UI sends an English prompt and the model answers in English.
  { label: t('agent.task.briefLabel'), description: t('agent.task.briefDescription'), prompt: t('agent.task.briefPrompt') },
  { label: t('agent.task.compareLabel'), description: t('agent.task.compareDescription'), prompt: t('agent.task.comparePrompt') },
  { label: t('agent.task.explainLabel'), description: t('agent.task.explainDescription'), prompt: t('agent.task.explainPrompt') },
  { label: t('agent.task.watchlistLabel'), description: t('agent.task.watchlistDescription'), prompt: t('agent.task.watchlistPrompt') }
])
const userStore = useUserStore()
const teaserPrompts = computed(() => taskPrompts.value.slice(0, 2))
const promptLogin = () => userStore.openAuthDialog('/agent')

const runSteps = computed(() => [
  { key: 'understand', label: t('agent.runStep.understand') },
  { key: 'retrieve', label: t('agent.runStep.retrieve') },
  { key: 'validate', label: t('agent.runStep.validate') },
  { key: 'answer', label: t('agent.runStep.answer') }
])
const sessionFilters = computed(() => [
  { value: 'all', label: t('agent.sessions.filter.all') },
  { value: 'today', label: t('agent.sessions.filter.today') },
  { value: 'match', label: t('agent.sessions.filter.match') },
  { value: 'unfinished', label: t('agent.sessions.filter.unfinished') }
])
const toolLabels = {
  agent_summary: 'agent.tool.agentSummary',
  crawler_summary: 'agent.tool.crawlerSummary',
  match_context: 'agent.tool.matchContext',
  news_context: 'agent.tool.newsContext',
  prediction: 'agent.tool.prediction',
  team_context: 'agent.tool.teamContext',
  squad_context: 'agent.tool.squadContext'
}
const routeLabel = computed(() => {
  if (!lastRoute.value) return ''
  const provider = providerLabel(lastRoute.value.provider)
  const model = lastRoute.value.model ? ` · ${lastRoute.value.model}` : ''
  const fallback = lastRoute.value.fallbackFrom ? t('agent.common.failoverSuffix') : ''
  return `${provider}${model}${fallback}`
})

const userInitial = computed(() => {
  try {
    const user = JSON.parse(authStorage.get('football_user') || '{}')
    return String(user.username || t('agent.common.me')).slice(0, 1).toUpperCase()
  } catch {
    return t('agent.common.me')
  }
})
const currentSession = computed(() => sessions.value.find(item => item.id === sessionId.value))
const visibleSessions = computed(() => {
  const keyword = sessionKeyword.value.trim().toLowerCase()
  return sessions.value.filter(item => {
    const textMatch = !keyword || `${item.title || ''} ${item.preview || ''}`.toLowerCase().includes(keyword)
    if (!textMatch) return false
    if (sessionFilter.value === 'today') return new Date(item.updatedAt).toDateString() === new Date().toDateString()
    if (sessionFilter.value === 'match') return Boolean(item.hasMatchContext)
    if (sessionFilter.value === 'unfinished') return ['draft', 'failed'].includes(sessionState(item))
    return true
  })
})
const currentSessionTitle = computed(() => currentSession.value?.title || t('agent.common.newSession'))
const healthLabel = computed(() => ({
  checking: t('agent.health.checking'),
  ready: t('agent.health.ready'),
  degraded: t('agent.health.degraded'),
  error: t('agent.health.offline')
}[healthState.value] || t('agent.health.unknown')))
const toolCount = computed(() => tools.value.length)
const latestAssistant = computed(() => [...messages.value].reverse().find(item => item.role === 'assistant' && !item.pending) || null)
const providerHealthSummary = ref('')
const providerHealth = ref([])
const globalModelLabel = ref('')
const providerLabel = provider => ({ deepseek: 'DeepSeek', openrouter: 'OpenRouter', scnet: 'SCNet' }[provider] || provider || t('agent.common.modelProvider'))
const intentLabels = {
  schedule: 'agent.intent.schedule',
  'match-analysis': 'agent.intent.matchAnalysis',
  'team-roster': 'agent.intent.teamRoster',
  'team-analysis': 'agent.intent.teamAnalysis',
  'news-analysis': 'agent.intent.newsAnalysis',
  prediction: 'agent.intent.prediction',
  'small-talk': 'agent.intent.smallTalk',
  general: 'agent.intent.general'
}
const intentLabelFor = value => value ? (intentLabels[value] ? t(intentLabels[value]) : value) : ''
const intentLabel = computed(() => intentLabelFor(currentIntent.value))

const queryValue = (value, fallback = '') => Array.isArray(value) ? String(value[0] || fallback) : String(value || fallback)
const matchContext = computed(() => {
  const query = route.query
  const fixtureId = queryValue(query.fixtureId)
  const homeName = queryValue(query.homeName)
  const awayName = queryValue(query.awayName)
  const matchTime = queryValue(query.matchTime)
  const prediction = queryValue(query.prediction)
  if (!fixtureId && !homeName && !awayName) return null
  return {
    fixtureId,
    homeName: homeName || t('agent.common.homeTeam'),
    awayName: awayName || t('agent.common.awayTeam'),
    homeDisplayName: queryValue(query.homeTeamDisplayName || query.homeDisplayName),
    awayDisplayName: queryValue(query.awayTeamDisplayName || query.awayDisplayName),
    leagueName: queryValue(query.leagueName),
    leagueDisplay: queryValue(query.leagueDisplay || query.leagueDisplayName),
    matchTime,
    prediction
  }
})
const contextHomeName = computed(() => displayTeamName(matchContext.value?.homeName, matchContext.value?.homeDisplayName))
const contextAwayName = computed(() => displayTeamName(matchContext.value?.awayName, matchContext.value?.awayDisplayName))
const contextLeagueName = computed(() => displayLeagueName(matchContext.value?.leagueName, matchContext.value?.leagueDisplay))
const artifactHomeName = artifact => displayTeamName(artifact?.homeTeamName, artifact?.homeTeamDisplayName || artifact?.homeTeamDisplay)
const artifactAwayName = artifact => displayTeamName(artifact?.awayTeamName, artifact?.awayTeamDisplayName || artifact?.awayTeamDisplay)
const artifactTeamName = artifact => displayTeamName(artifact?.teamName, artifact?.teamDisplayName || artifact?.displayName)
const artifactLeagueName = artifact => displayLeagueName(artifact?.leagueName, artifact?.leagueDisplay || artifact?.leagueDisplayName)
const createSessionId = () => {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `agent-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const persistSessions = () => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value.slice(0, 30)))
}

const loadLocalSessions = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
    sessions.value = Array.isArray(raw) ? raw.filter(item => item?.id) : []
  } catch {
    sessions.value = []
  }
}

const loadSessions = async () => {
  try {
    const remote = await agentApi.listSessions()
    const list = Array.isArray(remote) ? remote : (remote?.items || remote?.data || [])
    sessions.value = list.filter(item => item?.id).map(item => ({
      id: item.id,
      title: item.title || '',
      preview: item.preview || '',
      updatedAt: item.updatedAt || new Date().toISOString(),
      status: item.status || 'complete',
      hasMatchContext: Boolean(item.hasMatchContext || item.context?.fixtureId)
    }))
    // 服务端成功返回空列表时也要清除陈旧的本地会话，否则用户点击后
    // 会得到不存在的 session 404。
    persistSessions()
    return
  } catch {
    // 首次部署或未登录时回退本地会话，保证不丢失当前浏览器中的历史。
  }
  loadLocalSessions()
}

const touchSession = (firstMessage = '') => {
  const now = new Date().toISOString()
  const index = sessions.value.findIndex(item => item.id === sessionId.value)
  const title = firstMessage ? firstMessage.replace(/\s+/g, ' ').slice(0, 24) : currentSession.value?.title || ''
  const preview = firstMessage ? firstMessage.replace(/\s+/g, ' ').slice(0, 48) : currentSession.value?.preview || ''
  const item = { id: sessionId.value, title, preview, updatedAt: now, status: sending.value ? 'draft' : 'complete', hasMatchContext: Boolean(matchContext.value) }
  if (index === -1) sessions.value.unshift(item)
  else sessions.value.splice(index, 1, { ...sessions.value[index], ...item })
  sessions.value.sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')))
  persistSessions()
}

const updateSessionUrl = () => {
  router.replace({ path: '/agent', query: { ...route.query, sessionId: sessionId.value } })
}

const startNewSession = () => {
  sessionId.value = createSessionId()
  messages.value = []
  currentIntent.value = ''
  lastLatency.value = null
  lastRoute.value = null
  runPhase.value = null
  lastDataFreshness.value = ''
  selectedEvidenceMessage.value = null
  evidenceDrawerVisible.value = false
  updateSessionUrl()
}

const normalizeAssistantText = (value) => {
  const text = String(value || '').trim()
  const candidate = text.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').trim()
  if (!candidate || !/^[{[]/.test(candidate)) return text
  try {
    const parsed = JSON.parse(candidate)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      for (const key of ['reply', 'answer', 'message', 'content', 'text', 'summary']) {
        if (parsed[key] != null && String(parsed[key]).trim()) return String(parsed[key]).trim()
      }
    }
  } catch {
    // 兼容模型返回的非标准 JSON，保留原文。
  }
  return text
}

const normalizeMessages = (items) => (Array.isArray(items) ? items : [])
  .filter(item => item && ['user', 'assistant'].includes(item.role))
  .map((item, index) => ({
    id: `${sessionId.value}-${index}`,
    role: item.role,
    content: item.role === 'assistant' ? normalizeAssistantText(item.content) : String(item.content || ''),
    ...(item.metadata || {}).reasoning ? { reasoning: String(item.metadata.reasoning) } : {},
    ...(Array.isArray(item.metadata?.evidence) ? { evidence: item.metadata.evidence } : {}),
    ...(Array.isArray(item.metadata?.facts) ? { facts: item.metadata.facts } : {}),
    ...(Array.isArray(item.metadata?.unknowns) ? { unknowns: item.metadata.unknowns } : {}),
    ...(item.metadata?.answerValidation ? { answerValidation: item.metadata.answerValidation } : {}),
    ...(item.metadata?.dataQuality ? { dataQuality: item.metadata.dataQuality } : {}),
    ...(item.metadata?.dataFreshness ? { dataFreshness: item.metadata.dataFreshness } : {}),
    ...(item.metadata?.intent ? { intent: item.metadata.intent } : {}),
    ...(Array.isArray(item.metadata?.artifacts) ? { artifacts: item.metadata.artifacts } : {}),
    ...(Array.isArray(item.metadata?.actions) ? { actions: item.metadata.actions } : {}),
    ...(Array.isArray(item.metadata?.toolSteps) ? { toolEvents: item.metadata.toolSteps.map(tool => ({ tool, type: 'tool_result', status: 'ok' })) } : {})
  }))

const applySnapshot = (snapshot) => {
  messages.value = normalizeMessages(snapshot?.messages)
  currentIntent.value = snapshot?.metadata?.intent || ''
  const metadata = snapshot?.metadata || {}
  lastRoute.value = metadata.provider ? {
    provider: metadata.provider,
    model: metadata.model,
    fallbackFrom: metadata.fallbackFrom
  } : null
  const lastAssistant = [...messages.value].reverse().find(item => item.role === 'assistant')
  if (lastAssistant) {
    if (metadata.reasoning) lastAssistant.reasoning = String(metadata.reasoning)
    if (Array.isArray(metadata.evidence)) lastAssistant.evidence = metadata.evidence
    if (Array.isArray(metadata.facts)) lastAssistant.facts = metadata.facts
    if (Array.isArray(metadata.unknowns)) lastAssistant.unknowns = metadata.unknowns
    if (metadata.answerValidation) lastAssistant.answerValidation = metadata.answerValidation
    if (metadata.dataQuality) lastAssistant.dataQuality = metadata.dataQuality
    if (metadata.dataFreshness) lastAssistant.dataFreshness = metadata.dataFreshness
    if (metadata.intent) lastAssistant.intent = metadata.intent
    if (Array.isArray(metadata.artifacts)) lastAssistant.artifacts = metadata.artifacts
    if (Array.isArray(metadata.actions)) lastAssistant.actions = metadata.actions
    if (Array.isArray(metadata.evidenceSources) && !lastAssistant.evidence?.length) lastAssistant.sources = metadata.evidenceSources
    if (lastRoute.value) lastAssistant.routeLabel = routeLabel.value
  }
  lastDataFreshness.value = metadata.dataFreshness || ''
}

const selectSession = async (id) => {
  if (!id || id === sessionId.value || sending.value) return
  sessionId.value = id
  messages.value = []
  updateSessionUrl()
  try {
    const snapshot = await agentApi.getConversation(id)
    applySnapshot(snapshot)
    await scrollToBottom()
  } catch {
    sessions.value = sessions.value.filter(item => item.id !== id)
    persistSessions()
    ElMessage.warning(t('agent.error.loadSession'))
  }
}

const loadCurrentSession = async () => {
  const requested = queryValue(route.query.sessionId)
  if (!requested) {
    startNewSession()
    return
  }
  sessionId.value = requested
  if (!sessions.value.some(item => item.id === requested)) {
    sessions.value.unshift({ id: requested, title: '', preview: '', updatedAt: new Date().toISOString(), status: 'complete', hasMatchContext: Boolean(matchContext.value) })
    persistSessions()
  }
  try {
    const snapshot = await agentApi.getConversation(requested)
    applySnapshot(snapshot)
  } catch {
    sessions.value = sessions.value.filter(item => item.id !== requested)
    persistSessions()
    sessionId.value = createSessionId()
    updateSessionUrl()
    ElMessage.warning(t('agent.error.sessionExpired'))
  }
}

const applyRoutePrompt = () => {
  const prompt = queryValue(route.query.prompt)
  if (prompt && !messages.value.length && !composerText.value) composerText.value = prompt
}

const isNearBottom = () => {
  if (!messageList.value) return true
  const { scrollTop, scrollHeight, clientHeight } = messageList.value
  return scrollHeight - (scrollTop + clientHeight) < 96
}
const handleMessageScroll = () => {
  if (isNearBottom()) newContentAvailable.value = false
}
const scrollToBottom = async (force = false) => {
  await nextTick()
  if (!messageList.value) return
  if (!force && !isNearBottom()) {
    newContentAvailable.value = true
    return
  }
  messageList.value.scrollTop = messageList.value.scrollHeight
  newContentAvailable.value = false
}

const sendMessage = async (rawText = composerText.value) => {
  const text = String(rawText || '').trim()
  if (!text || sending.value) return
  if (!sessionId.value) startNewSession()

  const userMessage = { id: `${Date.now()}-user`, role: 'user', content: text }
  const assistantMessage = { id: `${Date.now()}-assistant`, role: 'assistant', content: '', pending: true }
  messages.value.push(userMessage, assistantMessage)
  composerText.value = ''
  touchSession(text)
  sending.value = true
  currentIntent.value = ''
  lastLatency.value = null
  lastRoute.value = null
  runPhase.value = { key: 'agent.phase.understanding' }
  lastDataFreshness.value = ''
  analyticsApi.track('agent_message_sent', { page: '/agent', entityType: 'agent_session', entityId: sessionId.value, properties: { intent: 'pending', hasMatchContext: Boolean(matchContext.value) } }).catch(() => {})
  await scrollToBottom()

  const payload = {
    message: text,
    history: [],
    sessionId: sessionId.value,
    context: matchContext.value || {}
  }
  abortController = new AbortController()
  const requestTimeout = window.setTimeout(() => abortController?.abort(), 90_000)
  let streamError = null
  let streamCompleted = false
  try {
    await agentApi.streamChat(payload, {
      signal: abortController.signal,
      onEvent: (event) => {
        if (event.type === 'stream_start') activeStreamId.value = event.streamId || ''
        if (event.type === 'intent') currentIntent.value = event.intent || ''
        if (event.type === 'chunk') assistantMessage.content += String(event.content || '')
        if (event.type === 'reasoning_delta') assistantMessage.reasoning = `${assistantMessage.reasoning || ''}${String(event.content || '')}`
        if (event.type === 'reasoning') assistantMessage.reasoning = String(event.content || '')
        if (event.type === 'tool_start' || event.type === 'tool_result') {
          assistantMessage.toolEvents = assistantMessage.toolEvents || []
          assistantMessage.toolEvents.push(event)
        }
        if (event.type === 'tool_start') runPhase.value = { key: 'agent.phase.readingTool', tool: event.tool }
        if (event.type === 'tool_result') {
          runPhase.value = event.status === 'error'
            ? { key: 'agent.phase.toolReadFailed', tool: event.tool }
            : (event.dataStatus && !['AVAILABLE', 'PARTIAL'].includes(String(event.dataStatus).toUpperCase())
              ? { key: 'agent.phase.toolStatus', tool: event.tool, status: event.dataStatus }
              : { key: 'agent.phase.toolRead', tool: event.tool })
        }
        if (event.type === 'stream_end') {
          streamCompleted = true
          lastLatency.value = Number(event.latencyMs) || null
          lastRoute.value = { provider: event.provider, model: event.model, fallbackFrom: event.fallbackFrom }
          assistantMessage.routeLabel = routeLabel.value
          assistantMessage.intent = currentIntent.value
          assistantMessage.sources = Array.isArray(event.evidenceSources) ? event.evidenceSources : []
          assistantMessage.evidence = Array.isArray(event.evidence) ? event.evidence : []
          assistantMessage.facts = Array.isArray(event.facts) ? event.facts : []
          assistantMessage.unknowns = Array.isArray(event.unknowns) ? event.unknowns : []
          assistantMessage.answerValidation = event.answerValidation || null
          assistantMessage.dataQuality = event.dataQuality || null
          assistantMessage.artifacts = Array.isArray(event.artifacts) ? event.artifacts : []
          assistantMessage.actions = Array.isArray(event.actions) ? event.actions : []
          lastDataFreshness.value = event.dataFreshness || ''
          assistantMessage.dataFreshness = event.dataFreshness || ''
          runPhase.value = { key: event.status && event.status !== 'ok' ? 'agent.phase.modelError' : 'agent.phase.completed' }
          analyticsApi.track('agent_message_completed', { page: '/agent', entityType: 'agent_session', entityId: sessionId.value, properties: { latencyMs: event.latencyMs, provider: event.provider, model: event.model, status: event.status || 'ok', streamMode: event.streamMode, usage: event.usage || {}, toolCount: Array.isArray(event.toolSteps) ? event.toolSteps.length : 0, dataQuality: event.dataQuality || {}, grounded: event.answerValidation?.grounded !== false } }).catch(() => {})
        }
        if (event.type === 'error') { runPhase.value = { key: 'agent.phase.failed' }; streamError = new Error(event.message || t('agent.error.handling')) }
        void scrollToBottom()
      }
    })
    if (streamError) throw streamError
    if (!streamCompleted && !abortController.signal.aborted) {
      throw new Error(t('agent.error.incomplete'))
    }
    assistantMessage.content = normalizeAssistantText(assistantMessage.content)
    if (!assistantMessage.content) assistantMessage.content = t('agent.error.emptyContent')
  } catch (error) {
    if (error?.name === 'AbortError') {
      assistantMessage.content = assistantMessage.content || t('agent.error.stoppedContent')
      runPhase.value = { key: 'agent.phase.stopped' }
    } else {
      assistantMessage.content = assistantMessage.content || t('agent.error.unavailable', { message: error?.message || t('agent.error.network') })
      ElMessage.warning(t('agent.error.responseFailed'))
      analyticsApi.track('agent_message_failed', { page: '/agent', entityType: 'agent_session', entityId: sessionId.value, properties: { error: error?.message || 'network_error', partial: Boolean(assistantMessage.content) } }).catch(() => {})
    }
    streamError = error
  } finally {
    window.clearTimeout(requestTimeout)
    assistantMessage.pending = false
    sending.value = false
    abortController = null
    activeStreamId.value = ''
    if (!runPhase.value || runPhaseKey() === 'agent.phase.completed') runPhase.value = null
    touchSession(text)
    await scrollToBottom()
  }
}

const cancelRequest = () => {
  if (activeStreamId.value) agentApi.cancelStream(activeStreamId.value).catch(() => {})
  abortController?.abort()
  analyticsApi.track('agent_message_cancelled', { page: '/agent', entityType: 'agent_session', entityId: sessionId.value }).catch(() => {})
}
const deleteSession = async (session) => {
  if (!session?.id || sending.value) return
  try {
    await agentApi.deleteSession(session.id)
    sessions.value = sessions.value.filter(item => item.id !== session.id)
    if (session.id === sessionId.value) startNewSession()
    ElMessage.success(t('agent.sessions.deleted'))
  } catch { ElMessage.warning(t('agent.sessions.deleteFailed')) }
}
const renameSession = async (session) => {
  if (!session?.id || sending.value) return
  const title = window.prompt(t('agent.sessions.renamePrompt'), session.title || t('agent.common.newSession'))
  if (!title || !title.trim()) return
  try {
    await agentApi.renameSession(session.id, title.trim())
    session.title = title.trim().slice(0, 64)
    persistSessions()
  } catch { ElMessage.warning(t('agent.sessions.renameFailed')) }
}
const copyText = async (value) => {
  try {
    await navigator.clipboard.writeText(String(value || ''))
    ElMessage.success(t('agent.feedback.copied'))
  } catch { ElMessage.warning(t('agent.feedback.copyFailed')) }
}
const regenerateMessage = (index) => {
  if (sending.value) return
  const message = messages.value[index]
  const previousUser = [...messages.value.slice(0, index)].reverse().find(item => item.role === 'user')
  if (!previousUser) return
  messages.value = messages.value.slice(0, index)
  sendMessage(previousUser.content)
}
const sendFeedback = (message, value) => {
  analyticsApi.track('agent_feedback', { page: '/agent', entityType: 'agent_message', entityId: message.id, properties: { value, sessionId: sessionId.value } }).catch(() => {})
  ElMessage.success(value === 'helpful' ? t('agent.feedback.thanks') : t('agent.feedback.recorded'))
}
const runAgentAction = (action) => {
  if (!action) return
  if (action.type === 'open-match' && action.fixtureId) {
    const matchId = action.matchId || action.id || action.fixtureId
    if (!matchId) return
    router.push({ path: `/prediction/${matchId}`, query: { homeName: action.homeTeamName || '', awayName: action.awayTeamName || '', leagueName: action.leagueName || '', matchTime: action.matchTime || '' } })
  } else if (action.type === 'open-team' && action.teamId) {
    router.push(`/team/${action.teamId}/squad`)
  } else if (action.type === 'select-team' && action.prompt) {
    sendMessage(action.prompt)
  }
}
const exportConversation = () => {
  if (!messages.value.length) return
  const text = messages.value.map(item => `${item.role === 'assistant' ? t('agent.common.assistant') : t('agent.common.me')}:\n${item.content || ''}`).join('\n\n')
  const blob = new Blob([`# ${currentSessionTitle.value}\n\n${text}`], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${currentSessionTitle.value || 'agent-session'}.md`
  link.click()
  URL.revokeObjectURL(url)
  analyticsApi.track('agent_export', { page: '/agent', entityType: 'agent_session', entityId: sessionId.value }).catch(() => {})
}
const shareConversation = async () => {
  const shareText = `${currentSessionTitle.value}\n${messages.value.at(-1)?.content || ''}`
  try {
    if (navigator.share) await navigator.share({ title: currentSessionTitle.value, text: shareText, url: window.location.href })
    else await copyText(shareText)
  } catch { /* 用户取消分享 */ }
}
const clearMessages = () => {
  if (sending.value) return
  messages.value = []
  currentIntent.value = ''
  lastLatency.value = null
  lastRoute.value = null
  runPhase.value = null
  lastDataFreshness.value = ''
  touchSession('')
}
// The prompt is both display-adjacent copy and the request value sent to the
// backend, so it follows the active locale.
const useMatchPrompt = () => sendMessage(t('agent.conversation.matchPrompt'))
const toolLabel = (tool) => {
  const key = toolLabels[tool]
  return key ? t(key) : tool
}
const evidenceStatusKeys = {
  ok: 'agent.status.ok',
  available: 'agent.status.ok',
  empty: 'agent.status.empty',
  'missing-input': 'agent.status.missingInput',
  error: 'agent.status.error',
  stale: 'agent.status.stale',
  conflict: 'agent.status.conflict',
  partial: 'agent.status.partial',
  'not-configured': 'agent.status.notConfigured',
  not_configured: 'agent.status.notConfigured',
  'quota-limited': 'agent.status.quotaLimited',
  quota_limited: 'agent.status.quotaLimited',
  'request-failed': 'agent.status.requestFailed',
  request_failed: 'agent.status.requestFailed',
  invalid: 'agent.status.invalid'
}
const evidenceStatusLabel = status => t(evidenceStatusKeys[String(status || '').toLowerCase()] || 'agent.status.unknown')
const runPhaseLabel = computed(() => {
  const phase = runPhase.value
  if (!phase) return ''
  if (typeof phase === 'string') return t(phase)
  return t(phase.key, {
    tool: phase.tool ? toolLabel(phase.tool) : '',
    status: phase.status ? evidenceStatusLabel(phase.status) : ''
  })
})
const evidenceTagType = status => ({ ok: 'success', available: 'success', empty: 'info', 'missing-input': 'warning', error: 'danger', stale: 'warning', conflict: 'danger', partial: 'warning', 'not-configured': 'warning', not_configured: 'warning', 'quota-limited': 'warning', quota_limited: 'warning', 'request-failed': 'danger', request_failed: 'danger', invalid: 'danger' }[String(status || '').toLowerCase()] || 'info')
const dataQualityLabel = level => t(({ high: 'agent.quality.high', medium: 'agent.quality.medium', low: 'agent.quality.low' }[String(level || '').toLowerCase()] || 'agent.status.unknown'))
const formatMatchTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
const formatProbability = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? `${(number <= 1 ? number * 100 : number).toFixed(1)}%` : '--'
}
const formatPredictionSummary = summary => {
  if (!summary || typeof summary !== 'object') return ''
  const labels = { READY: 'agent.prediction.ready', UNAVAILABLE: 'agent.prediction.unavailable', PENDING: 'agent.prediction.pending', FAILED: 'agent.prediction.failed', NOT_GENERATED: 'agent.prediction.notGenerated', NOT_READ: 'agent.prediction.notRead' }
  return Object.entries(summary)
    .filter(([, count]) => Number(count) > 0)
    .map(([status, count]) => `${labels[status] ? t(labels[status]) : status} ${count}`)
    .join(' · ')
}
const renderMessageMarkdown = value => renderMarkdown(value)
const formatEvidenceTime = (value) => {
  if (!value) return t('agent.common.unknownTime')
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
const formatRelativeTime = (value) => {
  if (!value) return ''
  const diff = Math.max(0, Date.now() - new Date(value).getTime())
  if (diff < 60_000) return t('agent.common.justNow')
  if (diff < 3_600_000) return t('agent.common.minutesAgo', { count: Math.floor(diff / 60_000) })
  if (diff < 86_400_000) return t('agent.common.hoursAgo', { count: Math.floor(diff / 3_600_000) })
  return new Date(value).toLocaleDateString(locale.value === 'zh' ? 'zh-CN' : 'en-US', { month: 'numeric', day: 'numeric' })
}
const sessionState = session => {
  if (session?.status) return String(session.status).toLowerCase()
  if (session?.id === sessionId.value && sending.value) return 'draft'
  return 'complete'
}
const sessionStateLabel = session => {
  const key = { complete: 'agent.sessionState.complete', draft: 'agent.sessionState.draft', failed: 'agent.sessionState.failed', error: 'agent.sessionState.error' }[sessionState(session)] || 'agent.sessionState.complete'
  return t(key)
}
const qualityMeta = message => {
  const level = String(message?.dataQuality?.level || '').toLowerCase()
  const statuses = (message?.evidence || []).map(item => String(item?.status || '').toLowerCase())
  if (statuses.some(status => ['error', 'request-failed', 'quota-limited', 'conflict'].includes(status))) return { label: t('agent.quality.sourceError'), tone: 'danger' }
  if (statuses.some(status => ['empty', 'stale', 'partial', 'missing-input'].includes(status)) || message?.unknowns?.length) return { label: t('agent.quality.partial'), tone: 'warning' }
  if (level === 'high' || statuses.some(status => ['ok', 'available'].includes(status))) return { label: t('agent.quality.verified'), tone: 'good' }
  if (level === 'low') return { label: t('agent.quality.insufficient'), tone: 'warning' }
  return { label: t('agent.quality.pending'), tone: 'neutral' }
}
const evidenceCount = message => {
  const toolsFromEvents = Array.isArray(message?.toolEvents) ? message.toolEvents.filter(item => item.type === 'tool_result' || !item.type).map(item => item.tool).filter(Boolean) : []
  const sources = Array.isArray(message?.evidence) ? message.evidence.map(item => item.tool || item.source).filter(Boolean) : []
  return new Set([...toolsFromEvents, ...sources]).size
}
const openEvidence = message => {
  selectedEvidenceMessage.value = message || latestAssistant.value
  evidenceDrawerVisible.value = true
}
const runStepClass = index => {
  if (!sending.value) return runPhaseKey() === 'agent.phase.completed' && index === 3 ? 'is-done' : ''
  const phase = runPhaseKey()
  const current = phase === 'agent.phase.understanding' ? 0 : (phase.includes('reading') || phase.includes('tool')) ? 1 : phase.includes('validat') ? 2 : 3
  return index < current ? 'is-done' : index === current ? 'is-active' : ''
}
const handleConversationCommand = command => {
  if (command === 'evidence') openEvidence(latestAssistant.value)
  if (command === 'export') exportConversation()
  if (command === 'share') shareConversation()
  if (command === 'clear') clearMessages()
}
const handleMessageCommand = (command, message, index) => {
  if (command === 'regenerate') regenerateMessage(index)
  if (command === 'helpful' || command === 'not-helpful') sendFeedback(message, command)
}

const loadAgentStatus = async () => {
  try {
    const [health, availableTools] = await Promise.all([agentApi.health(), agentApi.tools()])
    const providers = health?.models?.providers || []
    providerHealth.value = providers
    const configured = providers.filter(item => item?.configured)
    // The backend keeps the canonical Chinese status and adds statusLabel for
    // English. Accept both spellings so older/newer payloads stay compatible.
    const readyStatuses = new Set(['正常', '未验证', 'normal', 'not verified'])
    const statusValue = item => String(item?.status || '').toLowerCase()
    const hasReady = configured.some(item => readyStatuses.has(statusValue(item))
      || readyStatuses.has(String(item?.statusLabel || '').toLowerCase()))
    healthState.value = health?.status === 'checking'
      ? 'checking'
      : health?.status === 'ok' && (hasReady || configured.length === 0)
      ? 'ready'
      : (health?.status === 'degraded' ? 'degraded' : 'error')
    providerHealthSummary.value = configured.length ? configured.map(item => `${item.provider === 'deepseek' ? 'DS' : item.provider === 'openrouter' ? 'OR' : item.provider === 'scnet' ? 'SC' : item.provider}:${item.statusLabel || item.status || ''}`).join(' · ') : t('agent.common.modelsUnconfigured')
    const policy = health?.models?.configuredPolicy || {}
    const configuredProvider = String(policy.provider || 'auto').toLowerCase()
    const activeProvider = configuredProvider === 'openrouter'
      ? (providers.some(item => item.provider === 'openrouter' && item.configured) ? 'openrouter' : 'deepseek')
      : configuredProvider === 'scnet'
        ? (providers.some(item => item.provider === 'scnet' && item.configured) ? 'scnet' : (providers.some(item => item.provider === 'openrouter' && item.configured) ? 'openrouter' : 'deepseek'))
        : configuredProvider === 'deepseek'
          ? 'deepseek'
          : (providers.some(item => item.provider === 'openrouter' && item.configured) ? 'openrouter' : (providers.some(item => item.provider === 'scnet' && item.configured) ? 'scnet' : 'deepseek'))
    const activeProviderItem = providers.find(item => item.provider === activeProvider)
    const activeModel = policy?.[activeProvider]?.model || activeProviderItem?.model
    globalModelLabel.value = activeModel ? `${providerLabel(activeProvider)} · ${activeModel}` : ''
    tools.value = Array.isArray(availableTools) ? availableTools : health?.tools || []
  } catch {
    healthState.value = 'error'
  }
}

onMounted(async () => {
  await loadSessions()
  await loadCurrentSession()
  applyRoutePrompt()
  await Promise.all([loadAgentStatus(), scrollToBottom()])
})

watch(locale, async () => {
  if (sending.value) return
  await loadSessions()
  if (queryValue(route.query.sessionId)) await loadCurrentSession()
})

onBeforeUnmount(() => {
  if (activeStreamId.value) agentApi.cancelStream(activeStreamId.value).catch(() => {})
  abortController?.abort()
})
</script>

<style scoped>
/* Agent 工作台：主回答优先，系统信息按需展开。 */
.agent-page { --agent-ink: #17211c; --agent-muted: #68736d; --agent-line: rgba(31, 47, 39, .14); --agent-soft: #f5f7f5; --agent-accent: #176b4f; --agent-warn: #b56a16; --agent-danger: #bd4e49; display:flex; flex-direction:column; height:100dvh; min-height:100dvh; overflow:hidden; }
.agent-main { display:flex; flex:1; flex-direction:column; width:100%; max-width:1480px; min-width:0; min-height:0; margin:0 auto; padding:16px 28px 22px; }
.agent-shell { position:relative; display:flex; flex:1; min-width:0; min-height:0; flex-direction:column; }
.agent-toolbar { display:flex; align-items:center; justify-content:space-between; gap:20px; min-height:58px; border-bottom:1px solid var(--agent-line); }
.toolbar-leading, .toolbar-actions { display:flex; align-items:center; gap:10px; min-width:0; }
.toolbar-icon { display:inline-flex; }
.toolbar-title { display:flex; align-items:baseline; gap:10px; min-width:0; }
.toolbar-title strong { overflow:hidden; color:var(--agent-ink); font-size:18px; letter-spacing:-.02em; text-overflow:ellipsis; white-space:nowrap; }
.toolbar-context { overflow:hidden; color:var(--agent-muted); font-size:12px; text-overflow:ellipsis; white-space:nowrap; }
.toolbar-context::before { content:'·'; margin-right:10px; color:#9ba7a0; }
.service-state { display:inline-flex; align-items:center; gap:6px; color:var(--agent-muted); font-size:12px; white-space:nowrap; }
.service-state i, .scope-dot, .status-dot { width:7px; height:7px; border-radius:50%; background:#9ca8a1; }
.service-state.is-ready i { background:var(--agent-accent); box-shadow:0 0 0 4px rgba(23,107,79,.1); }
.service-state.is-degraded i { background:var(--agent-warn); }
.service-state.is-error i { background:var(--agent-danger); }
.intent-tag { border-color:var(--agent-line); color:var(--agent-muted); background:transparent; }
.new-session-button { box-shadow:none; }
.more-button { color:var(--agent-muted); }
.agent-layout { position:relative; display:grid; grid-template-columns:minmax(0, 1fr); flex:1; min-height:0; gap:12px; margin-top:12px; }
.agent-layout.has-session-drawer { grid-template-columns:280px minmax(0, 1fr); }
.conversation-panel { display:flex; min-width:0; min-height:0; height:100%; flex-direction:column; overflow:hidden; border:1px solid var(--agent-line); border-radius:16px; background:var(--ff-surface); }
.session-drawer, .evidence-drawer { z-index:20; display:flex; min-height:0; flex-direction:column; width:auto; padding:18px; overflow:hidden; border:1px solid var(--agent-line); border-radius:16px; background:rgba(255,255,255,.98); box-shadow:0 18px 50px rgba(26,45,35,.14); }
.session-drawer { position:relative; grid-column:1; height:100%; box-shadow:none; }
.evidence-drawer { position:absolute; top:0; right:0; bottom:0; width:360px; }
.drawer-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:14px; padding-bottom:14px; border-bottom:1px solid var(--agent-line); }
.drawer-heading > div { display:flex; flex-direction:column; gap:4px; min-width:0; }.drawer-heading strong { color:var(--agent-ink); font-size:16px; }.drawer-eyebrow { color:var(--agent-muted); font-size:12px; }
.session-search { margin-top:12px; }.session-search :deep(.el-input__wrapper) { box-shadow:none; border:1px solid var(--agent-line); background:var(--agent-soft); }
.session-filters { display:flex; gap:4px; padding:12px 0 9px; overflow:auto; border-bottom:1px solid var(--agent-line); }
.session-filters button { flex:none; padding:5px 8px; border:0; border-radius:6px; color:var(--agent-muted); background:transparent; cursor:pointer; font:inherit; font-size:11px; }.session-filters button:hover { color:var(--agent-ink); background:var(--agent-soft); }.session-filters button.active { color:var(--agent-ink); background:#e9efeb; font-weight:700; }
.session-list { display:flex; flex:1; flex-direction:column; gap:3px; padding-top:10px; overflow:auto; }
.session-item { position:relative; display:flex; flex-direction:column; gap:5px; padding:11px 10px; border:1px solid transparent; border-radius:8px; cursor:pointer; }.session-item:hover { background:var(--agent-soft); }.session-item.active { border-color:rgba(23,107,79,.22); background:#edf4f0; }
.session-item-top, .session-item-meta { display:flex; align-items:center; justify-content:space-between; gap:8px; min-width:0; }.session-title { overflow:hidden; color:var(--agent-ink); font-size:13px; font-weight:700; text-overflow:ellipsis; white-space:nowrap; }.session-preview { overflow:hidden; color:var(--agent-muted); font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.session-item-meta { color:#9aa59f; font-size:10px; }.session-item-meta time { font-variant-numeric:tabular-nums; }.session-state { flex:none; padding:2px 5px; border-radius:4px; color:#7d8982; background:#eef1ef; font-size:10px; }.session-state.is-draft { color:var(--agent-accent); background:#e7f2ec; }.session-state.is-failed, .session-state.is-error { color:var(--agent-danger); background:#fbebea; }
.session-item-actions { position:absolute; top:8px; right:6px; display:none; }.session-item:hover .session-item-actions, .session-item:focus-visible .session-item-actions, .session-item.active .session-item-actions { display:inline-flex; }.session-item-actions :deep(.el-button) { padding:3px; color:#829087; }
.session-empty, .drawer-empty { display:flex; flex-direction:column; align-items:center; gap:7px; padding:46px 12px; color:var(--agent-muted); text-align:center; }.session-empty small, .drawer-empty p { margin:0; color:#9aa59f; font-size:11px; }
.match-context-strip { display:flex; align-items:center; gap:11px; margin:15px 22px 0; padding:10px 12px; border:1px solid rgba(23,107,79,.18); border-radius:9px; background:#f0f6f2; }.context-mark { display:grid; flex:none; place-items:center; width:30px; height:30px; border-radius:7px; color:var(--agent-accent); background:#fff; }.context-copy { display:flex; flex:1; min-width:0; flex-direction:column; gap:2px; }.context-copy span, .context-copy small { color:var(--agent-muted); font-size:11px; }.context-copy strong { overflow:hidden; color:var(--agent-ink); font-size:13px; text-overflow:ellipsis; white-space:nowrap; }.context-copy em { margin:0 4px; color:var(--agent-accent); font-style:normal; }
.message-list { position:relative; flex:1; min-height:0; padding:30px clamp(16px, 7vw, 100px) 42px; overflow:auto; overscroll-behavior:contain; scroll-behavior:smooth; }
.task-launcher { max-width:720px; margin:42px auto 70px; }.task-launcher-intro { display:flex; align-items:flex-start; gap:13px; padding-bottom:22px; border-bottom:1px solid var(--agent-line); }.launcher-mark { display:grid; flex:none; place-items:center; width:38px; height:38px; border-radius:10px; color:var(--agent-accent); background:#eaf3ed; }.task-launcher h1 { margin:1px 0 6px; color:var(--agent-ink); font-size:23px; line-height:1.3; letter-spacing:-.035em; }.task-launcher p { margin:0; color:var(--agent-muted); font-size:13px; }.task-launcher-list { display:flex; flex-direction:column; margin-top:5px; }.task-launcher-list button { display:flex; align-items:center; justify-content:space-between; gap:18px; padding:15px 3px; border:0; border-bottom:1px solid var(--agent-line); color:var(--agent-ink); background:transparent; text-align:left; cursor:pointer; }.task-launcher-list button:hover:not(:disabled) { color:var(--agent-accent); }.task-launcher-list button:disabled { cursor:not-allowed; opacity:.55; }.task-launcher-list button > span { display:flex; flex-direction:column; gap:4px; }.task-launcher-list strong { font-size:14px; }.task-launcher-list small { color:var(--agent-muted); font-size:12px; }.task-launcher-list .el-icon { color:#9ca8a1; transform:rotate(-90deg); }
.message-row { display:flex; align-items:flex-start; gap:11px; max-width:900px; margin:0 auto 26px; }.message-row.is-user { flex-direction:row-reverse; margin-right:0; margin-left:auto; max-width:720px; }.message-avatar { display:grid; flex:none; place-items:center; width:28px; height:28px; margin-top:2px; border-radius:8px; color:var(--agent-accent); background:#eaf3ed; font-size:11px; font-weight:700; }.is-user .message-avatar { color:#fff; background:var(--agent-accent); }.message-bubble { min-width:0; padding:0; border:0; background:transparent; }.is-user .message-bubble { padding:10px 13px; border:1px solid rgba(23,107,79,.14); border-radius:11px 4px 11px 11px; background:#f0f6f2; }.message-meta { display:flex; align-items:center; gap:8px; margin-bottom:6px; color:var(--agent-muted); font-size:11px; }.message-meta strong { color:var(--agent-ink); }.message-meta span { color:var(--agent-accent); }.message-intent { color:var(--agent-muted)!important; }.message-content { margin:0; color:var(--agent-ink); font-size:14px; line-height:1.82; white-space:pre-wrap; word-break:break-word; }.message-content.markdown-body { white-space:normal; }.markdown-body :deep(p) { margin:0 0 10px; }.markdown-body :deep(p:last-child) { margin-bottom:0; }.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3), .markdown-body :deep(h4), .markdown-body :deep(h5), .markdown-body :deep(h6) { margin:15px 0 7px; color:var(--agent-ink); line-height:1.35; }.markdown-body :deep(ul), .markdown-body :deep(ol) { margin:7px 0 11px; padding-left:22px; }.markdown-body :deep(li) { margin:4px 0; }.markdown-body :deep(blockquote) { margin:10px 0; padding:5px 11px; border-left:3px solid var(--agent-accent); color:var(--agent-muted); background:#f6f8f6; }.markdown-body :deep(code) { padding:2px 5px; border-radius:4px; color:var(--agent-accent); background:#eaf3ed; font:.9em var(--ff-mono); }.markdown-body :deep(pre) { margin:10px 0; padding:11px 13px; overflow:auto; border-radius:8px; color:#e8f3ed; background:#19352b; }.markdown-body :deep(pre code) { padding:0; color:inherit; background:transparent; }.markdown-body :deep(a) { color:var(--agent-accent); text-decoration:underline; text-underline-offset:2px; }.markdown-body :deep(strong) { color:var(--agent-ink); }.markdown-body :deep(.markdown-table-wrap) { max-width:100%; overflow:auto; margin:10px 0; border:1px solid var(--agent-line); border-radius:7px; }.markdown-body :deep(table) { width:100%; min-width:420px; border-collapse:collapse; font-size:12px; }.markdown-body :deep(th), .markdown-body :deep(td) { padding:7px 9px; border-bottom:1px solid var(--agent-line); text-align:left; }.markdown-body :deep(th) { color:var(--agent-ink); background:#f6f8f6; }.markdown-body :deep(td) { color:var(--agent-muted); }
.answer-meta { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin-top:12px; color:#87928b; font-size:11px; }.quality-badge { display:inline-flex; align-items:center; gap:5px; color:#718078; }.quality-badge i { width:6px; height:6px; border-radius:50%; background:#9ca8a1; }.quality-badge.is-good { color:var(--agent-accent); }.quality-badge.is-good i { background:var(--agent-accent); }.quality-badge.is-warning { color:var(--agent-warn); }.quality-badge.is-warning i { background:var(--agent-warn); }.quality-badge.is-danger { color:var(--agent-danger); }.quality-badge.is-danger i { background:var(--agent-danger); }.evidence-link { padding:0; border:0; color:var(--agent-accent); background:none; cursor:pointer; font:inherit; }.uncertainty-note { display:flex; align-items:center; gap:6px; margin-top:10px; color:var(--agent-warn); font-size:11px; }
.message-artifacts { display:grid; gap:8px; margin-top:13px; }.artifact-card { padding:11px 12px; border:1px solid var(--agent-line); border-radius:9px; background:#fbfcfb; }.artifact-card strong { display:block; color:var(--agent-ink); font-size:13px; }.artifact-card small { display:block; margin-top:4px; color:var(--agent-muted); font-size:11px; }.artifact-label { display:block; margin-bottom:4px; color:var(--agent-muted); font-size:10px; }.artifact-match em { margin:0 4px; color:var(--agent-accent); font-style:normal; }.artifact-probs { display:flex; gap:12px; margin-top:7px; color:var(--agent-muted); font:11px var(--ff-mono); }.message-actions { display:flex; flex-wrap:wrap; gap:6px; margin-top:11px; }.message-feedback { display:flex; align-items:center; gap:2px; margin-top:10px; opacity:.8; }.message-feedback :deep(.el-button) { padding:3px 5px; color:var(--agent-muted); font-size:11px; }.typing-indicator { display:flex; gap:4px; padding:7px 0 2px; }.typing-indicator i { width:5px; height:5px; border-radius:50%; background:var(--agent-accent); animation:agent-bounce 1.1s infinite ease-in-out; }.typing-indicator i:nth-child(2) { animation-delay:.14s; }.typing-indicator i:nth-child(3) { animation-delay:.28s; }
.new-content-button { position:sticky; bottom:8px; display:block; margin:0 auto; padding:7px 11px; border:1px solid rgba(23,107,79,.22); border-radius:999px; color:var(--agent-accent); background:#f0f6f2; cursor:pointer; font:inherit; font-size:11px; box-shadow:0 4px 12px rgba(23,107,79,.1); }
.run-strip { display:flex; align-items:center; gap:14px; padding:9px clamp(16px, 7vw, 100px); border-top:1px solid var(--agent-line); background:#fbfcfb; }.run-strip-label { flex:none; color:var(--agent-muted); font-size:11px; }.run-steps { display:flex; align-items:center; gap:0; flex:1; }.run-steps span { display:flex; align-items:center; gap:5px; color:#a2aca6; font-size:11px; }.run-steps span:not(:last-child)::after { content:''; width:28px; height:1px; margin:0 9px; background:var(--agent-line); }.run-steps i { width:6px; height:6px; border-radius:50%; background:#b7c0ba; }.run-steps span.is-active { color:var(--agent-accent); }.run-steps span.is-active i { background:var(--agent-accent); animation:agent-pulse 1.2s infinite; }.run-steps span.is-done { color:#6f8177; }.run-steps span.is-done i { background:#6f8177; }
.composer { padding:12px clamp(16px, 7vw, 100px) 18px; border-top:1px solid var(--agent-line); background:var(--ff-surface); }.composer-scope { display:flex; align-items:center; gap:7px; margin-bottom:7px; color:var(--agent-muted); font-size:11px; }.scope-dot { display:inline-block; width:6px; height:6px; background:var(--agent-accent); }.composer-hint { margin-left:auto; color:#9ca8a1; font-size:10px; }.composer :deep(.el-textarea__inner) { padding:11px 12px; border-color:var(--agent-line); border-radius:9px; background:#fbfcfb; box-shadow:none; line-height:1.6; }.composer :deep(.el-textarea__inner:focus) { border-color:rgba(23,107,79,.52); box-shadow:0 0 0 2px rgba(23,107,79,.09); }.composer-actions { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-top:8px; }.composer-status { display:flex; align-items:center; gap:6px; color:var(--agent-muted); font-size:12px; }.composer-status.active { color:var(--agent-accent); }.composer-status.active .status-dot { background:var(--agent-accent); animation:agent-pulse 1.2s infinite; }
.evidence-content { flex:1; overflow:auto; padding-top:16px; }.evidence-summary { display:flex; flex-wrap:wrap; align-items:center; gap:9px; padding:11px 12px; border-radius:9px; background:#f5f7f5; color:var(--agent-muted); font-size:11px; }.evidence-summary strong { color:var(--agent-ink); font-size:12px; }.evidence-summary small { width:100%; color:#9aa59f; }.evidence-section { padding:17px 2px; border-bottom:1px solid var(--agent-line); }.evidence-section h3 { margin:0 0 10px; color:var(--agent-ink); font-size:12px; }.evidence-source { display:flex; gap:8px; padding:7px 0; }.source-status { flex:none; width:7px; height:7px; margin-top:5px; border-radius:50%; background:#9ca8a1; }.source-status.is-success { background:var(--agent-accent); }.source-status.is-warning { background:var(--agent-warn); }.source-status.is-danger { background:var(--agent-danger); }.evidence-source div { display:flex; flex-direction:column; gap:3px; }.evidence-source strong { color:var(--agent-ink); font-size:12px; }.evidence-source small { color:var(--agent-muted); font-size:11px; }.fact-list { display:flex; flex-direction:column; gap:6px; color:var(--agent-muted); font-size:11px; }.fact-list.is-warning { color:var(--agent-warn); }.evidence-section .tool-trace-row { display:flex; align-items:center; gap:7px; padding:5px 0; color:var(--agent-muted); font-size:11px; }.evidence-section .tool-trace-row small { margin-left:auto; color:#9aa59f; font-family:var(--ff-mono); }.evidence-note { display:flex; gap:8px; padding:16px 2px 2px; color:var(--agent-muted); }.evidence-note .el-icon { flex:none; color:var(--agent-accent); }.evidence-note p { margin:0; font-size:11px; line-height:1.7; }
@keyframes agent-bounce { 0%,60%,100% { transform:translateY(0); opacity:.45; } 30% { transform:translateY(-4px); opacity:1; } } @keyframes agent-pulse { 50% { opacity:.3; } }
@media (max-width: 900px) {
  .agent-main { padding:12px 16px 18px; }
  .agent-toolbar { min-height:52px; }
  .toolbar-actions .service-state { display:none; }
  .agent-layout, .agent-layout.has-session-drawer { display:block; min-height:0; }
  .conversation-panel { height:100%; min-height:0; }
  .session-drawer, .evidence-drawer { position:absolute; top:0; bottom:0; left:0; width:min(330px, calc(100vw - 32px)); }
  .session-drawer { height:auto; max-height:none; }
  .evidence-drawer { right:0; width:min(360px, calc(100vw - 32px)); }
  .message-list, .composer { padding-left:24px; padding-right:24px; }
}
@media (max-width: 620px) {
  .agent-main { padding:8px 10px 12px; }
  .toolbar-title strong { font-size:15px; }
  .toolbar-context { display:none; }
  .intent-tag { display:none; }
  .more-button { padding:5px; font-size:0; }
  .more-button .el-icon { margin:0; font-size:16px; }
  .new-session-button { padding:7px 9px; }
  .new-session-button span { display:none; }
  .message-list { padding:22px 13px 30px; }
  .task-launcher { margin:26px 4px 50px; }
  .task-launcher-intro { gap:10px; }
  .task-launcher h1 { font-size:20px; }
  .message-row, .message-row.is-user { max-width:100%; }
  .message-avatar { width:26px; height:26px; }
  .message-content { font-size:13px; }
  .run-strip { align-items:flex-start; flex-direction:column; gap:7px; padding:9px 13px; }
  .run-steps { width:100%; justify-content:space-between; }
  .run-steps span:not(:last-child)::after { width:12px; margin:0 4px; }
  .composer { padding:10px 13px 13px; }
  .composer-scope { align-items:flex-start; flex-wrap:wrap; }
  .composer-hint { width:100%; margin-left:13px; }
  .composer :deep(.el-textarea__inner) { min-height:78px!important; }
  .session-drawer, .evidence-drawer { right:0; width:auto; border-radius:0 0 14px 14px; max-height:72vh; }
  .evidence-drawer { top:auto; max-height:72vh; }
  .session-drawer { max-height:76vh; }
  .drawer-heading { padding-bottom:11px; }
}

.agent-teaser {
  max-width: 820px; margin: 0 auto; padding: 28px 22px;
  display:flex; flex-direction:column; gap:18px;
}
.agent-teaser-intro { display:flex; gap:14px; align-items:flex-start; }
.agent-teaser-intro h1 { margin:0 0 6px; font-size:22px; color:var(--ff-text-strong); }
.agent-teaser-intro p { margin:0; color:var(--ff-text-muted); font-size:13px; line-height:1.6; }
.agent-teaser-examples { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.agent-teaser-card {
  display:flex; flex-direction:column; gap:6px; text-align:left;
  padding:14px; border:1px solid var(--ff-border); border-radius:12px;
  background:var(--ff-surface-quiet); color:var(--ff-text); cursor:pointer;
}
.agent-teaser-card:hover, .agent-teaser-card:focus-visible { border-color:var(--ff-primary); outline:none; }
.agent-teaser-card strong { font-size:14px; }
.agent-teaser-card small { color:var(--ff-text-muted); font-size:12px; line-height:1.5; }
.agent-teaser-note { margin:0; color:var(--ff-text-faint); font-size:12px; }
@media (max-width: 680px) {
  .agent-teaser-examples { grid-template-columns:1fr; }
}
</style>
