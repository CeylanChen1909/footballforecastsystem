/**
 * Agent 页（自 Vue src/views/user/Agent.vue 885 行移植）。
 *
 * 对齐要点（契约 §1.4/§2.3、M-09）：
 *  - SSE 流式 POST /api/agent/chat/stream（agentApi.streamChat，90s 超时）；
 *    事件 stream_start / intent / chunk / reasoning_delta / reasoning /
 *    tool_start / tool_result / stream_end / error。
 *  - 消息用 createStore：流式 chunk 是原地 content += 变异，普通 signal
 *    数组里的对象属性变异不会触发重渲染；全部更新走
 *    setMessages(index, path, updater) 或 setMessages(index, {...}) 合并。
 *    注意：Solid 2.0 的 store setter 只有单函数形态 setState(fn)，1.x
 *    的路径形态在 2.0 里会抛 "fn is not a function"（2026-11-24 实测），
 *    因此这里用 utils/legacyStore 的 createLegacyStore（同名导入），
 *    调用点保持 1.x 形态不变。
 *  - 会话 localStorage football_agent_sessions_v1 上限 30 条；服务端
 *    listSessions 失败回退本地、成功返回空列表时清除陈旧本地会话。
 *  - 健康/工具并行拉取（health() + tools()）算 healthState
 *    （checking/ready/degraded/error）。
 *  - markdown 用脚手架已重建的 utils/markdown.js（renderMarkdown，
 *    XSS 转义后 innerHTML）；消息气泡 11px 不对称圆角保留
 *    （.is-user.message-bubble 的 11px 4px 11px 11px，见 agent.css）。
 *  - 证据抽屉移动端宽度：≤900px 时 min(360px, 100vw-32px)，≤620px 全宽
 *    底部抽屉 max-height 72vh（agent.css 媒体查询，与原版一致）。
 *  - --agent-* token 已并入全局 tokens（agent.css 生成时改写为 --ff-*，
 *    var(--agent- 出现 0 次）；主绿唯一 #126b50。
 *  - 未登录 teaser（含 2 个示例卡 + 登录按钮，auth.openAuthDialog('/agent')）。
 *  - el-dropdown → 手写 toggle 菜单；ElMessage → toast；window.prompt
 *    重命名保留（原版行为）。
 *  - 顶栏/页脚由 App.jsx 全局挂载，本页只写页面内容；.agent-page 高度为
 *    calc(100dvh - 64px)（顶栏在页外，见 agent.css 附录）。
 *  - Solid 2.0 注意：set 信号后立即读会读到旧值（暂缓写入），stream_end
 *    的 routeLabel/intentLabel 用事件本地值直接算，不读刚 set 的信号；
 *    effect 回调不返回 Promise（返回值会被当作 cleanup）。
 */
import { createEffect, createMemo, createSignal, For, Show, onCleanup, onSettled } from 'solid-js'
import { createLegacyStore as createStore } from '../../utils/legacyStore'
import { useNavigate, useSearchParams } from '@solidjs/router'
import { agentApi, analyticsApi } from '../../api'
import auth from '../../stores/auth'
import { toastSuccess, toastWarning } from '../../utils/toast'
import { renderMarkdown } from '../../utils/markdown'
import '../../styles/agent.css'
import '../../styles/page-common.css'

const STORAGE_KEY = 'football_agent_sessions_v1'

const taskPrompts = [
  { label: '生成今日简报', description: '列出重点比赛，并标记数据时间与不确定性', prompt: '请生成今日赛程简报：只列出有可靠数据的重点比赛，并说明数据时间和不确定性。' },
  { label: '比较两队状态', description: '用近 5 场真实赛程做攻防与走势对比', prompt: '请比较我指定的两支球队近5场状态，优先读取真实赛程数据，缺数据就明确说明。' },
  { label: '解释一场预测', description: '说明模型依据、缺失项与主要风险', prompt: '请解释这场比赛的统一预测结论：引用实际读取到的特征、数据来源和风险，不要重复概率。' },
  { label: '整理关注比赛', description: '按开赛时间整理接下来 24 小时的比赛', prompt: '请帮我整理关注比赛：按开赛时间列出接下来24小时的比赛，并标记预测是否已生成。' },
]
const runSteps = [
  { key: 'understand', label: '理解' },
  { key: 'retrieve', label: '读取' },
  { key: 'validate', label: '校验' },
  { key: 'answer', label: '输出' },
]
const sessionFilters = [
  { value: 'all', label: '全部' },
  { value: 'today', label: '今天' },
  { value: 'match', label: '有比赛' },
  { value: 'unfinished', label: '待处理' },
]
const toolLabels = {
  agent_summary: '助手状态',
  crawler_summary: '赛程数据',
  match_context: '比赛上下文',
  news_context: '新闻资讯',
  prediction: '预测模型',
  team_context: '球队资料',
  squad_context: '球队阵容',
}
const intentLabels = {
  schedule: '赛程查询',
  'match-analysis': '比赛分析',
  'team-roster': '球队阵容',
  'team-analysis': '球队分析',
  'news-analysis': '资讯分析',
  prediction: '预测解释',
  'small-talk': '闲聊',
  general: '综合问答',
}

const providerLabel = (provider) => ({ deepseek: 'DeepSeek', openrouter: 'OpenRouter', scnet: 'SCNet' }[provider] || provider || '模型通道')
const toolLabel = (tool) => toolLabels[tool] || tool
const evidenceStatusLabel = (status) =>
  ({
    ok: '可用', available: '可用', empty: '空数据', 'missing-input': '缺少参数', error: '读取失败', stale: '已过期', conflict: '上下文冲突',
    partial: '部分数据', 'not-configured': '未配置', not_configured: '未配置', 'quota-limited': '额度受限', quota_limited: '额度受限',
    'request-failed': '请求失败', request_failed: '请求失败', invalid: '参数无效',
  }[String(status || '').toLowerCase()] || '待确认')
const evidenceTagType = (status) =>
  ({
    ok: 'success', available: 'success', empty: 'info', 'missing-input': 'warning', error: 'danger', stale: 'warning', conflict: 'danger',
    partial: 'warning', 'not-configured': 'warning', not_configured: 'warning', 'quota-limited': 'warning', quota_limited: 'warning',
    'request-failed': 'danger', request_failed: 'danger', invalid: 'danger',
  }[String(status || '').toLowerCase()] || 'info')
const dataQualityLabel = (level) => ({ high: '高', medium: '中', low: '低' }[String(level || '').toLowerCase()] || '待确认')
const formatMatchTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
const formatProbability = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? `${(number <= 1 ? number * 100 : number).toFixed(1)}%` : '--'
}
const formatPredictionSummary = (summary) => {
  if (!summary || typeof summary !== 'object') return ''
  const labels = { READY: '已生成', UNAVAILABLE: '不可用', PENDING: '生成中', FAILED: '失败', NOT_GENERATED: '未生成', NOT_READ: '读取失败' }
  return Object.entries(summary)
    .filter(([, count]) => Number(count) > 0)
    .map(([status, count]) => `${labels[status] || status} ${count}`)
    .join(' · ')
}
const formatEvidenceTime = (value) => {
  if (!value) return '未知时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
const formatRelativeTime = (value) => {
  if (!value) return ''
  const diff = Math.max(0, Date.now() - new Date(value).getTime())
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Date(value).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

export default function Agent() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [sessions, setSessions] = createSignal([])
  const [sessionKeyword, setSessionKeyword] = createSignal('')
  const [sessionId, setSessionId] = createSignal('')
  const [messages, setMessages] = createStore([])
  const [composerText, setComposerText] = createSignal('')
  const [sending, setSending] = createSignal(false)
  const [healthState, setHealthState] = createSignal('checking')
  const [tools, setTools] = createSignal([])
  const [currentIntent, setCurrentIntent] = createSignal('')
  const [lastLatency, setLastLatency] = createSignal(null)
  const [lastRoute, setLastRoute] = createSignal(null)
  const [runPhase, setRunPhase] = createSignal('')
  const [sessionDrawerVisible, setSessionDrawerVisible] = createSignal(typeof window === 'undefined' ? true : window.innerWidth > 900)
  const [evidenceDrawerVisible, setEvidenceDrawerVisible] = createSignal(false)
  const [selectedEvidenceMessage, setSelectedEvidenceMessage] = createSignal(null)
  const [sessionFilter, setSessionFilter] = createSignal('all')
  const [newContentAvailable, setNewContentAvailable] = createSignal(false)
  const [lastDataFreshness, setLastDataFreshness] = createSignal('')
  const [activeStreamId, setActiveStreamId] = createSignal('')
  const [providerHealthSummary, setProviderHealthSummary] = createSignal('')
  const [providerHealth, setProviderHealth] = createSignal([])
  const [globalModelLabel, setGlobalModelLabel] = createSignal('')
  const [moreMenuOpen, setMoreMenuOpen] = createSignal(false)
  const [msgMenuId, setMsgMenuId] = createSignal(null)
  let messageListEl = null
  let abortController = null
  let initialized = false

  const matchContext = createMemo(() => {
    const fixtureId = String(searchParams.fixtureId || '')
    const homeName = String(searchParams.homeName || '')
    const awayName = String(searchParams.awayName || '')
    const matchTime = String(searchParams.matchTime || '')
    const prediction = String(searchParams.prediction || '')
    if (!fixtureId && !homeName && !awayName) return null
    return { fixtureId, homeName: homeName || '主队', awayName: awayName || '客队', leagueName: String(searchParams.leagueName || ''), matchTime, prediction }
  })
  const currentSession = createMemo(() => sessions().find((item) => item.id === sessionId()))
  const currentSessionTitle = createMemo(() => currentSession()?.title || '新会话')
  const healthLabel = createMemo(() => ({ checking: '连接检测中', ready: '助手在线', degraded: '助手部分降级', error: '暂时离线' }[healthState()] || '状态未知'))
  const toolCount = createMemo(() => tools().length)
  const latestAssistant = createMemo(() => [...messages].reverse().find((item) => item.role === 'assistant' && !item.pending) || null)
  const userInitial = createMemo(() => String(auth.username() || '我').slice(0, 1).toUpperCase())
  const intentLabel = createMemo(() => intentLabels[currentIntent()] || currentIntent())
  const visibleSessions = createMemo(() => {
    const keyword = sessionKeyword().trim().toLowerCase()
    return sessions().filter((item) => {
      const textMatch = !keyword || `${item.title || ''} ${item.preview || ''}`.toLowerCase().includes(keyword)
      if (!textMatch) return false
      if (sessionFilter() === 'today') return new Date(item.updatedAt).toDateString() === new Date().toDateString()
      if (sessionFilter() === 'match') return Boolean(item.hasMatchContext)
      if (sessionFilter() === 'unfinished') return ['draft', 'failed'].includes(sessionState(item))
      return true
    })
  })
  const routeLabel = createMemo(() => {
    if (!lastRoute()) return ''
    const provider = providerLabel(lastRoute().provider)
    const model = lastRoute().model ? ` · ${lastRoute().model}` : ''
    const fallback = lastRoute().fallbackFrom ? ' · 已故障切换' : ''
    return `${provider}${model}${fallback}`
  })

  const routeLabelOf = (route) => {
    if (!route) return ''
    return `${providerLabel(route.provider)}${route.model ? ` · ${route.model}` : ''}${route.fallbackFrom ? ' · 已故障切换' : ''}`
  }
  const createSessionId = () => {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
    return `agent-${Date.now()}-${Math.random().toString(16).slice(2)}`
  }
  const persistSessions = () => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions().slice(0, 30)))
    } catch {
      /* storage may be disabled */
    }
  }
  const loadLocalSessions = () => {
    try {
      const raw = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
      setSessions(Array.isArray(raw) ? raw.filter((item) => item?.id) : [])
    } catch {
      setSessions([])
    }
  }
  const loadSessions = async () => {
    try {
      const remote = await agentApi.listSessions()
      const list = Array.isArray(remote) ? remote : remote?.items || remote?.data || []
      setSessions(
        list
          .filter((item) => item?.id)
          .map((item) => ({
            id: item.id,
            title: item.title || '未命名会话',
            preview: item.preview || '历史会话',
            updatedAt: item.updatedAt || new Date().toISOString(),
            status: item.status || 'complete',
            hasMatchContext: Boolean(item.hasMatchContext || item.context?.fixtureId),
          })),
      )
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
    const index = sessions().findIndex((item) => item.id === sessionId())
    const title = firstMessage ? firstMessage.replace(/\s+/g, ' ').slice(0, 24) : currentSession()?.title || '新会话'
    const preview = firstMessage ? firstMessage.replace(/\s+/g, ' ').slice(0, 48) : currentSession()?.preview || '开始一次新的足球对话'
    const item = { id: sessionId(), title, preview, updatedAt: now, status: sending() ? 'draft' : 'complete', hasMatchContext: Boolean(matchContext()) }
    setSessions((prev) => {
      const next = [...prev]
      if (index === -1) next.unshift(item)
      else next.splice(index, 1, { ...next[index], ...item })
      next.sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')))
      return next
    })
    persistSessions()
  }
  const updateSessionUrl = () => {
    const params = new URLSearchParams()
    Object.entries(searchParams).forEach(([key, value]) => {
      if (value != null && value !== '') params.set(key, String(value))
    })
    params.set('sessionId', sessionId())
    navigate(`/agent?${params.toString()}`, { replace: true })
  }
  const startNewSession = () => {
    setSessionId(createSessionId())
    setMessages([])
    setCurrentIntent('')
    setLastLatency(null)
    setLastRoute(null)
    setRunPhase('')
    setLastDataFreshness('')
    setSelectedEvidenceMessage(null)
    setEvidenceDrawerVisible(false)
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
  const normalizeMessages = (items) =>
    (Array.isArray(items) ? items : [])
      .filter((item) => item && ['user', 'assistant'].includes(item.role))
      .map((item, index) => ({
        id: `${sessionId()}-${index}`,
        role: item.role,
        content: item.role === 'assistant' ? normalizeAssistantText(item.content) : String(item.content || ''),
        ...(item.metadata || {}).reasoning ? { reasoning: String(item.metadata.reasoning) } : {},
        ...(Array.isArray(item.metadata?.evidence) ? { evidence: item.metadata.evidence } : {}),
        ...(Array.isArray(item.metadata?.facts) ? { facts: item.metadata.facts } : {}),
        ...(Array.isArray(item.metadata?.unknowns) ? { unknowns: item.metadata.unknowns } : {}),
        ...(item.metadata?.answerValidation ? { answerValidation: item.metadata.answerValidation } : {}),
        ...(item.metadata?.dataQuality ? { dataQuality: item.metadata.dataQuality } : {}),
        ...(item.metadata?.dataFreshness ? { dataFreshness: item.metadata.dataFreshness } : {}),
        ...(item.metadata?.intent ? { intentLabel: intentLabels[item.metadata.intent] || item.metadata.intent } : {}),
        ...(Array.isArray(item.metadata?.artifacts) ? { artifacts: item.metadata.artifacts } : {}),
        ...(Array.isArray(item.metadata?.actions) ? { actions: item.metadata.actions } : {}),
        ...(Array.isArray(item.metadata?.toolSteps) ? { toolEvents: item.metadata.toolSteps.map((tool) => ({ tool, type: 'tool_result', status: 'ok' })) } : {}),
      }))
  const applySnapshot = (snapshot) => {
    // 用本地数组算下标：set 后立即读 store 是旧值（见 sendMessage 注释）。
    const normalized = normalizeMessages(snapshot?.messages)
    setMessages(normalized)
    setCurrentIntent(snapshot?.metadata?.intent || '')
    const metadata = snapshot?.metadata || {}
    const route = metadata.provider ? { provider: metadata.provider, model: metadata.model, fallbackFrom: metadata.fallbackFrom } : null
    setLastRoute(route)
    const lastIndex = [...normalized].reverse().findIndex((item) => item.role === 'assistant')
    if (lastIndex >= 0) {
      const idx = normalized.length - 1 - lastIndex
      const patch = {}
      if (metadata.reasoning) patch.reasoning = String(metadata.reasoning)
      if (Array.isArray(metadata.evidence)) patch.evidence = metadata.evidence
      if (Array.isArray(metadata.facts)) patch.facts = metadata.facts
      if (Array.isArray(metadata.unknowns)) patch.unknowns = metadata.unknowns
      if (metadata.answerValidation) patch.answerValidation = metadata.answerValidation
      if (metadata.dataQuality) patch.dataQuality = metadata.dataQuality
      if (metadata.dataFreshness) patch.dataFreshness = metadata.dataFreshness
      if (metadata.intent) patch.intentLabel = intentLabels[metadata.intent] || metadata.intent
      if (Array.isArray(metadata.artifacts)) patch.artifacts = metadata.artifacts
      if (Array.isArray(metadata.actions)) patch.actions = metadata.actions
      if (Array.isArray(metadata.evidenceSources) && !normalized[idx].evidence?.length) patch.sources = metadata.evidenceSources
      if (route) patch.routeLabel = routeLabelOf(route)
      if (Object.keys(patch).length) setMessages(idx, patch)
    }
    setLastDataFreshness(metadata.dataFreshness || '')
  }
  const selectSession = async (id) => {
    if (!id || id === sessionId() || sending()) return
    setSessionId(id)
    setMessages([])
    updateSessionUrl()
    try {
      const snapshot = await agentApi.getConversation(id)
      applySnapshot(snapshot)
      await scrollToBottom()
    } catch {
      setSessions((prev) => prev.filter((item) => item.id !== id))
      persistSessions()
      toastWarning('会话记录暂时无法加载')
    }
  }
  const loadCurrentSession = async () => {
    const requested = String(searchParams.sessionId || '')
    if (!requested) {
      startNewSession()
      return
    }
    setSessionId(requested)
    if (!sessions().some((item) => item.id === requested)) {
      setSessions((prev) => [
        { id: requested, title: '未命名会话', preview: '历史会话', updatedAt: new Date().toISOString(), status: 'complete', hasMatchContext: Boolean(matchContext()) },
        ...prev,
      ])
      persistSessions()
    }
    try {
      const snapshot = await agentApi.getConversation(requested)
      applySnapshot(snapshot)
    } catch {
      setSessions((prev) => prev.filter((item) => item.id !== requested))
      persistSessions()
      setSessionId(createSessionId())
      updateSessionUrl()
      toastWarning('历史会话不存在或已过期，已创建新会话')
    }
  }
  const applyRoutePrompt = () => {
    const prompt = String(searchParams.prompt || '')
    if (prompt && !messages.length && !composerText()) setComposerText(prompt)
  }
  const isNearBottom = () => {
    if (!messageListEl) return true
    const { scrollTop, scrollHeight, clientHeight } = messageListEl
    return scrollHeight - (scrollTop + clientHeight) < 96
  }
  const handleMessageScroll = () => {
    if (isNearBottom()) setNewContentAvailable(false)
  }
  const scrollToBottom = async (force = false) => {
    await Promise.resolve()
    if (!messageListEl) return
    if (!force && !isNearBottom()) {
      setNewContentAvailable(true)
      return
    }
    messageListEl.scrollTop = messageListEl.scrollHeight
    setNewContentAvailable(false)
  }

  const sendMessage = async (rawText = composerText()) => {
    const text = String(rawText || '').trim()
    if (!text || sending()) return
    if (!sessionId()) startNewSession()

    const userMessage = { id: `${Date.now()}-user`, role: 'user', content: text }
    const assistantMessage = { id: `${Date.now()}-assistant`, role: 'assistant', content: '', pending: true }
    // 下标必须在追加时用本地值算：set 后立即读 store 是旧值（2.0 暂缓写入，
    // 未折叠前读到的是提交前的帧），直接用 messages.length - 1 会得到 -1，
    // 之后全部 chunk / stream_end 更新都打到不存在的下标上，助手永远空白
    // 且一直 pending（2026-11-24 实测）。updater 内的 prev 是草稿，读到
    // 的是追加前的真实长度。
    let asstIndex = -1
    setMessages((prev) => {
      asstIndex = prev.length + 1
      return [...prev, userMessage, assistantMessage]
    })
    setComposerText('')
    touchSession(text)
    setSending(true)
    setCurrentIntent('')
    setLastLatency(null)
    setLastRoute(null)
    setRunPhase('正在理解问题')
    setLastDataFreshness('')
    analyticsApi
      .track('agent_message_sent', { page: '/agent', entityType: 'agent_session', entityId: sessionId(), properties: { intent: 'pending', hasMatchContext: Boolean(matchContext()) } })
      .catch(() => {})
    await scrollToBottom()

    const payload = { message: text, history: [], sessionId: sessionId(), context: matchContext() || {} }
    abortController = new AbortController()
    const requestTimeout = window.setTimeout(() => abortController?.abort(), 90_000)
    let streamError = null
    let streamCompleted = false
    try {
      await agentApi.streamChat(payload, {
        signal: abortController.signal,
        onEvent: (event) => {
          if (event.type === 'stream_start') setActiveStreamId(event.streamId || '')
          if (event.type === 'intent') setCurrentIntent(event.intent || '')
          if (event.type === 'chunk') setMessages(asstIndex, 'content', (c) => `${c || ''}${String(event.content || '')}`)
          if (event.type === 'reasoning_delta') setMessages(asstIndex, 'reasoning', (r) => `${r || ''}${String(event.content || '')}`)
          if (event.type === 'reasoning') setMessages(asstIndex, 'reasoning', String(event.content || ''))
          if (event.type === 'tool_start' || event.type === 'tool_result') {
            setMessages(asstIndex, 'toolEvents', (arr) => [...(arr || []), event])
          }
          if (event.type === 'tool_start') setRunPhase(`正在读取${toolLabel(event.tool)}`)
          if (event.type === 'tool_result') {
            setRunPhase(
              event.status === 'error'
                ? `${toolLabel(event.tool)}读取失败`
                : event.dataStatus && !['AVAILABLE', 'PARTIAL'].includes(String(event.dataStatus).toUpperCase())
                  ? `${toolLabel(event.tool)}：${evidenceStatusLabel(event.dataStatus)}`
                  : `已读取${toolLabel(event.tool)}`,
            )
          }
          if (event.type === 'stream_end') {
            streamCompleted = true
            setLastLatency(Number(event.latencyMs) || null)
            const route = { provider: event.provider, model: event.model, fallbackFrom: event.fallbackFrom }
            setLastRoute(route)
            // 用事件本地值直接算，不读刚 set 的信号（2.0 暂缓写入）。
            setMessages(asstIndex, {
              routeLabel: routeLabelOf(route),
              intentLabel: intentLabels[event.intent] || intentLabel(),
              sources: Array.isArray(event.evidenceSources) ? event.evidenceSources : [],
              evidence: Array.isArray(event.evidence) ? event.evidence : [],
              facts: Array.isArray(event.facts) ? event.facts : [],
              unknowns: Array.isArray(event.unknowns) ? event.unknowns : [],
              answerValidation: event.answerValidation || null,
              dataQuality: event.dataQuality || null,
              artifacts: Array.isArray(event.artifacts) ? event.artifacts : [],
              actions: Array.isArray(event.actions) ? event.actions : [],
              dataFreshness: event.dataFreshness || '',
            })
            setLastDataFreshness(event.dataFreshness || '')
            setRunPhase(event.status && event.status !== 'ok' ? '模型通道异常' : '分析完成')
            analyticsApi
              .track('agent_message_completed', {
                page: '/agent',
                entityType: 'agent_session',
                entityId: sessionId(),
                properties: {
                  latencyMs: event.latencyMs,
                  provider: event.provider,
                  model: event.model,
                  status: event.status || 'ok',
                  streamMode: event.streamMode,
                  usage: event.usage || {},
                  toolCount: Array.isArray(event.toolSteps) ? event.toolSteps.length : 0,
                  dataQuality: event.dataQuality || {},
                  grounded: event.answerValidation?.grounded !== false,
                },
              })
              .catch(() => {})
          }
          if (event.type === 'error') {
            setRunPhase('处理失败')
            streamError = new Error(event.message || '助手处理失败')
          }
          void scrollToBottom()
        },
      })
      if (streamError) throw streamError
      if (!streamCompleted && !abortController.signal.aborted) {
        throw new Error('助手响应未完整结束')
      }
      const finalContent = normalizeAssistantText(messages[asstIndex]?.content)
      setMessages(asstIndex, 'content', finalContent || '助手没有返回可展示的内容，请换个问法再试。')
    } catch (error) {
      if (error?.name === 'AbortError') {
        if (!messages[asstIndex]?.content) setMessages(asstIndex, 'content', '本轮分析已停止。')
        setRunPhase('已停止')
      } else {
        if (!messages[asstIndex]?.content) setMessages(asstIndex, 'content', `助手暂时无法响应：${error?.message || '网络错误'}`)
        toastWarning('助手响应失败，请稍后重试')
        analyticsApi
          .track('agent_message_failed', { page: '/agent', entityType: 'agent_session', entityId: sessionId(), properties: { error: error?.message || 'network_error', partial: Boolean(messages[asstIndex]?.content) } })
          .catch(() => {})
      }
      streamError = error
    } finally {
      window.clearTimeout(requestTimeout)
      setMessages(asstIndex, 'pending', false)
      setSending(false)
      abortController = null
      setActiveStreamId('')
      if (!runPhase() || runPhase() === '分析完成') setRunPhase('')
      touchSession(text)
      await scrollToBottom()
    }
  }

  const cancelRequest = () => {
    if (activeStreamId()) agentApi.cancelStream(activeStreamId()).catch(() => {})
    abortController?.abort()
    analyticsApi.track('agent_message_cancelled', { page: '/agent', entityType: 'agent_session', entityId: sessionId() }).catch(() => {})
  }
  const deleteSession = async (session) => {
    if (!session?.id || sending()) return
    try {
      await agentApi.deleteSession(session.id)
      setSessions((prev) => prev.filter((item) => item.id !== session.id))
      if (session.id === sessionId()) startNewSession()
      toastSuccess('会话已删除')
    } catch {
      toastWarning('会话删除失败，请稍后重试')
    }
  }
  const renameSession = async (session) => {
    if (!session?.id || sending()) return
    const title = window.prompt('请输入新的会话标题', session.title || '新会话')
    if (!title || !title.trim()) return
    try {
      await agentApi.renameSession(session.id, title.trim())
      setSessions((prev) => prev.map((item) => (item.id === session.id ? { ...item, title: title.trim().slice(0, 64) } : item)))
      persistSessions()
    } catch {
      toastWarning('会话重命名失败，请稍后重试')
    }
  }
  const copyText = async (value) => {
    try {
      await navigator.clipboard.writeText(String(value || ''))
      toastSuccess('已复制')
    } catch {
      toastWarning('复制失败，请手动选择文本')
    }
  }
  const regenerateMessage = (index) => {
    if (sending()) return
    const message = messages[index]
    const previousUser = [...messages.slice(0, index)].reverse().find((item) => item.role === 'user')
    if (!previousUser) return
    setMessages(messages.slice(0, index))
    sendMessage(previousUser.content)
  }
  const sendFeedback = (message, value) => {
    analyticsApi.track('agent_feedback', { page: '/agent', entityType: 'agent_message', entityId: message.id, properties: { value, sessionId: sessionId() } }).catch(() => {})
    toastSuccess(value === 'helpful' ? '感谢反馈' : '已记录，我们会继续改进')
  }
  const runAgentAction = (action) => {
    if (!action) return
    if (action.type === 'open-match' && action.fixtureId) {
      const matchId = action.matchId || action.id || action.fixtureId
      if (!matchId) return
      const query = new URLSearchParams({
        homeName: action.homeTeamName || '',
        awayName: action.awayTeamName || '',
        leagueName: action.leagueName || '',
        matchTime: action.matchTime || '',
      })
      navigate(`/prediction/${matchId}?${query.toString()}`)
    } else if (action.type === 'open-team' && action.teamId) {
      navigate(`/team/${action.teamId}/squad`)
    } else if (action.type === 'select-team' && action.prompt) {
      sendMessage(action.prompt)
    }
  }
  const exportConversation = () => {
    if (!messages.length) return
    const text = messages.map((item) => `${item.role === 'assistant' ? '助手' : '我'}：\n${item.content || ''}`).join('\n\n')
    const blob = new Blob([`# ${currentSessionTitle()}\n\n${text}`], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${currentSessionTitle() || 'agent-session'}.md`
    link.click()
    URL.revokeObjectURL(url)
    analyticsApi.track('agent_export', { page: '/agent', entityType: 'agent_session', entityId: sessionId() }).catch(() => {})
  }
  const shareConversation = async () => {
    const shareText = `${currentSessionTitle()}\n${messages.at(-1)?.content || ''}`
    try {
      if (navigator.share) await navigator.share({ title: currentSessionTitle(), text: shareText, url: window.location.href })
      else await copyText(shareText)
    } catch {
      /* 用户取消分享 */
    }
  }
  const clearMessages = () => {
    if (sending()) return
    setMessages([])
    setCurrentIntent('')
    setLastLatency(null)
    setLastRoute(null)
    setRunPhase('')
    setLastDataFreshness('')
    touchSession('')
  }
  const useMatchPrompt = () => sendMessage('请结合当前比赛上下文，给出赛前研判、关键依据和主要风险。')
  const qualityMeta = (message) => {
    const level = String(message?.dataQuality?.level || '').toLowerCase()
    const statuses = (message?.evidence || []).map((item) => String(item?.status || '').toLowerCase())
    if (statuses.some((status) => ['error', 'request-failed', 'quota-limited', 'conflict'].includes(status))) return { label: '来源异常', tone: 'danger' }
    if (statuses.some((status) => ['empty', 'stale', 'partial', 'missing-input'].includes(status)) || message?.unknowns?.length) return { label: '部分数据', tone: 'warning' }
    if (level === 'high' || statuses.some((status) => ['ok', 'available'].includes(status))) return { label: '已核验', tone: 'good' }
    if (level === 'low') return { label: '数据不足', tone: 'warning' }
    return { label: '依据待确认', tone: 'neutral' }
  }
  const evidenceCount = (message) => {
    const toolsFromEvents = Array.isArray(message?.toolEvents) ? message.toolEvents.filter((item) => item.type === 'tool_result' || !item.type).map((item) => item.tool).filter(Boolean) : []
    const sources = Array.isArray(message?.evidence) ? message.evidence.map((item) => item.tool || item.source).filter(Boolean) : []
    return new Set([...toolsFromEvents, ...sources]).size
  }
  const openEvidence = (message) => {
    setSelectedEvidenceMessage(message || latestAssistant())
    setEvidenceDrawerVisible(true)
  }
  const runStepClass = (index) => {
    if (!sending()) return runPhase() === '分析完成' && index === 3 ? 'is-done' : ''
    const phase = String(runPhase() || '')
    const current = phase.includes('理解') ? 0 : phase.includes('读取') ? 1 : phase.includes('校验') ? 2 : 3
    return index < current ? 'is-done' : index === current ? 'is-active' : ''
  }
  const sessionState = (session) => {
    if (session?.status) return String(session.status).toLowerCase()
    if (session?.id === sessionId() && sending()) return 'draft'
    return 'complete'
  }
  const sessionStateLabel = (session) => ({ complete: '已完成', draft: '进行中', failed: '待重试', error: '失败' }[sessionState(session)] || '已完成')
  const handleConversationCommand = (command) => {
    setMoreMenuOpen(false)
    if (command === 'evidence') openEvidence(latestAssistant())
    if (command === 'export') exportConversation()
    if (command === 'share') shareConversation()
    if (command === 'clear') clearMessages()
  }
  const handleMessageCommand = (command, message, index) => {
    setMsgMenuId(null)
    if (command === 'regenerate') regenerateMessage(index)
    if (command === 'helpful' || command === 'not-helpful') sendFeedback(message, command)
  }
  const loadAgentStatus = async () => {
    try {
      const [health, availableTools] = await Promise.all([agentApi.health(), agentApi.tools()])
      const providers = health?.models?.providers || []
      setProviderHealth(providers)
      const configured = providers.filter((item) => item?.configured)
      const hasReady = configured.some((item) => ['正常', '未验证'].includes(item?.status))
      setHealthState(
        health?.status === 'checking'
          ? 'checking'
          : health?.status === 'ok' && (hasReady || configured.length === 0)
            ? 'ready'
            : health?.status === 'degraded'
              ? 'degraded'
              : 'error',
      )
      setProviderHealthSummary(configured.length ? configured.map((item) => `${item.provider === 'deepseek' ? 'DS' : item.provider === 'openrouter' ? 'OR' : item.provider === 'scnet' ? 'SC' : item.provider}:${item.status}`).join(' · ') : '未配置模型')
      const policy = health?.models?.configuredPolicy || {}
      const configuredProvider = String(policy.provider || 'auto').toLowerCase()
      const activeProvider =
        configuredProvider === 'openrouter'
          ? providers.some((item) => item.provider === 'openrouter' && item.configured)
            ? 'openrouter'
            : 'deepseek'
          : configuredProvider === 'scnet'
            ? providers.some((item) => item.provider === 'scnet' && item.configured)
              ? 'scnet'
              : providers.some((item) => item.provider === 'openrouter' && item.configured)
                ? 'openrouter'
                : 'deepseek'
            : configuredProvider === 'deepseek'
              ? 'deepseek'
              : providers.some((item) => item.provider === 'openrouter' && item.configured)
                ? 'openrouter'
                : providers.some((item) => item.provider === 'scnet' && item.configured)
                  ? 'scnet'
                  : 'deepseek'
      const activeProviderItem = providers.find((item) => item.provider === activeProvider)
      const activeModel = policy?.[activeProvider]?.model || activeProviderItem?.model
      setGlobalModelLabel(activeModel ? `${providerLabel(activeProvider)} · ${activeModel}` : '')
      setTools(Array.isArray(availableTools) ? availableTools : health?.tools || [])
    } catch {
      setHealthState('error')
    }
  }

  const initAgent = async () => {
    if (initialized) return
    initialized = true
    await loadSessions()
    await loadCurrentSession()
    applyRoutePrompt()
    await Promise.all([loadAgentStatus(), scrollToBottom()])
  }
  onSettled(() => {
    if (auth.token()) void initAgent()
  })
  // 登录态从 teaser 切到工作台时再初始化（Vue 版 onMounted 只跑一次，
  // 登录后切入工作台时会话为空；Solid 侧用 token 变化补一次）。
  createEffect(
    () => auth.token(),
    (token) => {
      if (token) void initAgent()
    },
  )
  onCleanup(() => {
    if (activeStreamId()) agentApi.cancelStream(activeStreamId()).catch(() => {})
    abortController?.abort()
  })

  const promptLogin = () => auth.openAuthDialog('/agent')

  return (
    <div class="agent-page ff-page-shell">
      <main id="app-main" class="agent-main" tabindex="-1">
        <Show
          when={auth.token()}
          fallback={
            <section class="agent-teaser ff-panel" aria-label="助手登录引导">
              <div class="agent-teaser-intro">
                <span class="launcher-mark" aria-hidden="true">
                  AI
                </span>
                <div>
                  <h1>登录后使用赛程助手</h1>
                  <p>用自然语言查询赛程、球队状态与预测依据。先给结论，再说明数据与不确定性。</p>
                </div>
              </div>
              <div class="agent-teaser-examples" aria-label="示例问题">
                <For each={taskPrompts.slice(0, 2)}>
                  {(task) => (
                    <button type="button" class="agent-teaser-card" onClick={promptLogin}>
                      <strong>{task.label}</strong>
                      <small>{task.description}</small>
                    </button>
                  )}
                </For>
              </div>
              <button type="button" class="ff-btn ff-btn-primary ff-btn-lg" onClick={promptLogin}>
                登录后开始提问
              </button>
              <p class="agent-teaser-note">浏览公开赛程与预测无需登录；助手会话需要账号以便保存历史。</p>
            </section>
          }
        >
          <section class="agent-shell">
            <header class="agent-toolbar">
              <div class="toolbar-leading">
                <button
                  type="button"
                  class="ff-btn ff-btn-ghost ff-btn-sm toolbar-icon"
                  aria-label={sessionDrawerVisible() ? '关闭会话列表' : '打开会话列表'}
                  onClick={() => setSessionDrawerVisible((v) => !v)}
                >
                  ☰
                </button>
                <div class="toolbar-title">
                  <strong>{currentSessionTitle()}</strong>
                  <Show when={matchContext()} fallback={<span class="toolbar-context">{healthLabel()}</span>}>
                    <span class="toolbar-context">
                      {matchContext().homeName} vs {matchContext().awayName}
                    </span>
                  </Show>
                </div>
              </div>
              <div class="toolbar-actions">
                <span class={`service-state is-${healthState()}`}>
                  <i />
                  {healthLabel()}
                </span>
                <Show when={currentIntent()}>
                  <span class="ff-tag is-info intent-tag">{intentLabel()}</span>
                </Show>
                <button type="button" class="ff-btn ff-btn-primary ff-btn-sm new-session-button" onClick={startNewSession}>
                  + 新会话
                </button>
                <div class="agent-menu-wrap">
                  <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm more-button" aria-label="更多操作" aria-expanded={moreMenuOpen()} onClick={() => setMoreMenuOpen((v) => !v)}>
                    更多 ▾
                  </button>
                  <Show when={moreMenuOpen()}>
                    <div class="agent-menu" role="menu">
                      <button type="button" role="menuitem" onClick={() => handleConversationCommand('evidence')}>
                        数据依据
                      </button>
                      <button type="button" role="menuitem" disabled={!messages.length} onClick={() => handleConversationCommand('export')}>
                        导出会话
                      </button>
                      <button type="button" role="menuitem" disabled={!messages.length} onClick={() => handleConversationCommand('share')}>
                        分享摘要
                      </button>
                      <button type="button" role="menuitem" disabled={!messages.length || sending()} onClick={() => handleConversationCommand('clear')}>
                        清空本轮
                      </button>
                    </div>
                  </Show>
                </div>
              </div>
            </header>

            <div class={`agent-layout${sessionDrawerVisible() ? ' has-session-drawer' : ''}`}>
              <Show when={sessionDrawerVisible()}>
                <aside class="session-drawer ff-panel" aria-label="会话列表">
                  <div class="drawer-heading">
                    <div>
                      <span class="drawer-eyebrow">会话</span>
                      <strong>继续你的分析</strong>
                    </div>
                    <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-label="关闭会话列表" onClick={() => setSessionDrawerVisible(false)}>
                      ✕
                    </button>
                  </div>
                  <input class="ff-input session-search" type="search" placeholder="搜索会话" aria-label="搜索会话" value={sessionKeyword()} onInput={(e) => setSessionKeyword(e.currentTarget.value)} />
                  <div class="session-filters" role="tablist" aria-label="会话筛选">
                    <For each={sessionFilters}>
                      {(filter) => (
                        <button type="button" role="tab" aria-selected={sessionFilter() === filter.value} class={sessionFilter() === filter.value ? 'active' : ''} onClick={() => setSessionFilter(filter.value)}>
                          {filter.label}
                        </button>
                      )}
                    </For>
                  </div>
                  <div class="session-list">
                    <For each={visibleSessions()} fallback={
                      <div class="session-empty">
                        <span>没有匹配的会话</span>
                        <small>换个关键词或开始新会话</small>
                      </div>
                    }>
                      {(session) => (
                        <div class={`session-item${session.id === sessionId() ? ' active' : ''}`} role="button" tabindex="0" onClick={() => selectSession(session.id)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectSession(session.id) } }}>
                          <div class="session-item-top">
                            <span class="session-title">{session.title || '未命名会话'}</span>
                            <span class={`session-state is-${sessionState(session)}`}>{sessionStateLabel(session)}</span>
                          </div>
                          <span class="session-preview">{session.preview || '开始一次新的足球对话'}</span>
                          <div class="session-item-meta">
                            <Show when={session.hasMatchContext}>
                              <span>比赛上下文</span>
                            </Show>
                            <time>{formatRelativeTime(session.updatedAt)}</time>
                          </div>
                          <span class="session-item-actions">
                            <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-label="重命名会话" onClick={(e) => { e.stopPropagation(); renameSession(session) }}>
                              ✎
                            </button>
                            <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-label="删除会话" onClick={(e) => { e.stopPropagation(); deleteSession(session) }}>
                              🗑
                            </button>
                          </span>
                        </div>
                      )}
                    </For>
                  </div>
                </aside>
              </Show>

              <section class="conversation-panel ff-panel">
                <Show when={matchContext()}>
                  <div class="match-context-strip">
                    <div class="context-mark" aria-hidden="true">
                      🏆
                    </div>
                    <div class="context-copy">
                      <span>当前比赛</span>
                      <strong>
                        {matchContext().homeName} <em>vs</em> {matchContext().awayName}
                      </strong>
                      <small>
                        {matchContext().leagueName || '联赛待定'}
                        <Show when={matchContext().matchTime}> · {formatMatchTime(matchContext().matchTime)}</Show>
                      </small>
                    </div>
                    <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" disabled={sending()} onClick={useMatchPrompt}>
                      开始分析
                    </button>
                  </div>
                </Show>

                <div ref={(el) => { messageListEl = el }} class="message-list" aria-live="polite" onScroll={handleMessageScroll}>
                  <Show when={!messages.length}>
                    <div class="task-launcher">
                      <div class="task-launcher-intro">
                        <span class="launcher-mark" aria-hidden="true">
                          AI
                        </span>
                        <div>
                          <h1>问一场比赛，或让助手帮你查清一件事</h1>
                          <p>先给结论，再说明数据依据与不确定性。</p>
                        </div>
                      </div>
                      <div class="task-launcher-list">
                        <For each={taskPrompts}>
                          {(task) => (
                            <button type="button" disabled={sending()} onClick={() => sendMessage(task.prompt)}>
                              <span>
                                <strong>{task.label}</strong>
                                <small>{task.description}</small>
                              </span>
                              <span aria-hidden="true">↗</span>
                            </button>
                          )}
                        </For>
                      </div>
                    </div>
                  </Show>

                  <For each={messages}>
                    {(message, index) => (
                      <article class={`message-row is-${message.role}`}>
                        <div class="message-avatar" aria-hidden="true">
                          {message.role === 'assistant' ? 'AI' : userInitial()}
                        </div>
                        <div class="message-bubble">
                          <div class="message-meta">
                            <strong>{message.role === 'assistant' ? '助手' : '你'}</strong>
                            <Show when={message.pending} fallback={<Show when={message.role === 'assistant' && message.intentLabel}><span class="message-intent">{message.intentLabel}</span></Show>}>
                              <span>{runPhase() || '正在分析'}</span>
                            </Show>
                          </div>
                          <Show when={message.content && message.role === 'assistant'} fallback={<Show when={message.content}><p class="message-content">{message.content}</p></Show>}>
                            <div class="message-content markdown-body" innerHTML={renderMarkdown(message.content)} />
                          </Show>
                          <Show when={message.pending}>
                            <div class="typing-indicator">
                              <i />
                              <i />
                              <i />
                            </div>
                          </Show>
                          <Show when={message.role === 'assistant' && !message.pending && (message.dataQuality || message.evidence?.length || message.toolEvents?.length || message.unknowns?.length)}>
                            <div class="answer-meta">
                              <span class={`quality-badge is-${qualityMeta(message).tone}`}>
                                <i />
                                {qualityMeta(message).label}
                              </span>
                              <Show when={message.evidence?.length || message.toolEvents?.length}>
                                <span>{evidenceCount(message)} 个数据来源</span>
                              </Show>
                              <Show when={message.dataFreshness || lastDataFreshness()}>
                                <span>更新于 {formatEvidenceTime(message.dataFreshness || lastDataFreshness())}</span>
                              </Show>
                              <button type="button" class="evidence-link" onClick={() => openEvidence(message)}>
                                查看依据
                              </button>
                            </div>
                          </Show>
                          <Show when={message.unknowns?.length}>
                            <div class="uncertainty-note">
                              <span>ⓘ {message.unknowns.length} 项数据尚无法核验</span>
                            </div>
                          </Show>
                          <Show when={message.artifacts?.length}>
                            <div class="message-artifacts">
                              <For each={message.artifacts}>
                                {(artifact) => (
                                  <div class={`artifact-card artifact-${artifact.type}`}>
                                    <Show when={artifact.type === 'match'} fallback={
                                      <Show when={artifact.type === 'prediction'} fallback={
                                        <Show when={artifact.type === 'squad'} fallback={
                                          <Show when={artifact.type === 'schedule'} fallback={
                                            <div class="artifact-team">
                                              <span class="artifact-label">球队资料</span>
                                              <strong>{artifact.teamName || '球队'}</strong>
                                              <small>近况 {artifact.recentMatchCount || 0} 场</small>
                                            </div>
                                          }>
                                            <div class="artifact-schedule">
                                              <span class="artifact-label">赛程</span>
                                              <strong>
                                                {artifact.total || 0} 场可用比赛
                                                <Show when={artifact.truncated}> · 已截断</Show>
                                              </strong>
                                              <small>
                                                {artifact.windowType === 'NEXT_24_HOURS' ? '接下来24小时' : artifact.leagueName || '主爬虫源'}
                                                <Show when={artifact.predictionSummary}> · {formatPredictionSummary(artifact.predictionSummary)}</Show>
                                              </small>
                                            </div>
                                          </Show>
                                        }>
                                          <div class="artifact-squad">
                                            <span class="artifact-label">球队阵容</span>
                                            <strong>
                                              {artifact.teamName || '球队'} · {artifact.playerCount || 0} 人
                                            </strong>
                                            <Show when={artifact.status && artifact.status !== 'AVAILABLE'} fallback={<small>可核验名单已读取</small>}>
                                              <small>{artifact.message || evidenceStatusLabel(String(artifact.status).toLowerCase())}</small>
                                            </Show>
                                          </div>
                                        </Show>
                                      }>
                                        <div class="artifact-prediction">
                                          <span class="artifact-label">统一预测</span>
                                          <strong>{artifact.predictionAvailable === false ? '暂不可预测' : artifact.resultLabel || '暂无结果'}</strong>
                                          <div class="artifact-probs">
                                            <span>主 {formatProbability(artifact.homeWinProb)}</span>
                                            <span>平 {formatProbability(artifact.drawProb)}</span>
                                            <span>客 {formatProbability(artifact.awayWinProb)}</span>
                                          </div>
                                          <Show when={artifact.featureStatus}>
                                            <small>{artifact.featureStatus}</small>
                                          </Show>
                                        </div>
                                      </Show>
                                    }>
                                      <div class="artifact-match">
                                        <span class="artifact-label">比赛</span>
                                        <strong>
                                          {artifact.homeTeamName} <em>vs</em> {artifact.awayTeamName}
                                        </strong>
                                        <small>
                                          {artifact.leagueName || '联赛待定'}
                                          <Show when={artifact.matchTime}> · {formatMatchTime(artifact.matchTime)}</Show>
                                        </small>
                                      </div>
                                    </Show>
                                  </div>
                                )}
                              </For>
                            </div>
                          </Show>
                          <Show when={message.actions?.length}>
                            <div class="message-actions">
                              <For each={message.actions}>
                                {(action) => (
                                  <button type="button" class="ff-btn ff-btn-plain ff-btn-sm" onClick={() => runAgentAction(action)}>
                                    {action.label}
                                  </button>
                                )}
                              </For>
                            </div>
                          </Show>
                          <Show when={message.role === 'assistant' && !message.pending && message.content}>
                            <div class="message-feedback">
                              <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={() => copyText(message.content)}>
                                复制
                              </button>
                              <div class="agent-menu-wrap">
                                <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-expanded={msgMenuId() === message.id} onClick={() => setMsgMenuId(msgMenuId() === message.id ? null : message.id)}>
                                  更多 ▾
                                </button>
                                <Show when={msgMenuId() === message.id}>
                                  <div class="agent-menu" role="menu">
                                    <button type="button" role="menuitem" onClick={() => handleMessageCommand('regenerate', message, index())}>
                                      重新生成
                                    </button>
                                    <button type="button" role="menuitem" onClick={() => handleMessageCommand('helpful', message, index())}>
                                      回答有帮助
                                    </button>
                                    <button type="button" role="menuitem" onClick={() => handleMessageCommand('not-helpful', message, index())}>
                                      需要改进
                                    </button>
                                  </div>
                                </Show>
                              </div>
                            </div>
                          </Show>
                        </div>
                      </article>
                    )}
                  </For>
                  <Show when={newContentAvailable()}>
                    <button class="new-content-button" type="button" onClick={() => scrollToBottom(true)}>
                      有新内容，跳到最新
                    </button>
                  </Show>
                </div>

                <Show when={sending()}>
                  <div class="run-strip" aria-live="polite">
                    <div class="run-strip-label">本轮分析</div>
                    <div class="run-steps">
                      <For each={runSteps}>
                        {(step, index) => (
                          <span class={runStepClass(index())}>
                            <i />
                            {step.label}
                          </span>
                        )}
                      </For>
                    </div>
                  </div>
                </Show>
                <form
                  class="composer"
                  onSubmit={(e) => {
                    e.preventDefault()
                    sendMessage()
                  }}
                >
                  <div class="composer-scope">
                    <span class="scope-dot" />
                    <span>{matchContext() ? '围绕当前比赛提问' : '可查询赛程、球队、阵容与预测依据'}</span>
                    <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
                  </div>
                  <textarea
                    class="ff-textarea"
                    rows="3"
                    maxlength="2000"
                    placeholder="例如：比较阿森纳和巴萨最近五场状态，说明数据时间和不确定性"
                    disabled={sending()}
                    value={composerText()}
                    onInput={(e) => setComposerText(e.currentTarget.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault()
                        sendMessage()
                      }
                    }}
                  />
                  <div class="composer-actions">
                    <span class={`composer-status${sending() ? ' active' : ''}`}>
                      <span class="status-dot" />
                      {sending() ? runPhase() || '正在分析' : '准备就绪'}
                    </span>
                    <span class="composer-count">
                      {composerText().length}/2000
                      <Show when={globalModelLabel()}> · {globalModelLabel()}</Show>
                      <Show when={providerHealthSummary()}> · {providerHealthSummary()}</Show>
                    </span>
                    <div>
                      <Show when={sending()} fallback={
                        <button type="submit" class="ff-btn ff-btn-primary" disabled={!composerText().trim()}>
                          发送
                        </button>
                      }>
                        <button type="button" class="ff-btn ff-btn-warning" onClick={cancelRequest}>
                          停止
                        </button>
                      </Show>
                    </div>
                  </div>
                </form>
              </section>

              <Show when={evidenceDrawerVisible()}>
                <aside class="evidence-drawer ff-panel" aria-label="数据依据">
                  <div class="drawer-heading">
                    <div>
                      <span class="drawer-eyebrow">证据</span>
                      <strong>数据依据</strong>
                    </div>
                    <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" aria-label="关闭数据依据" onClick={() => setEvidenceDrawerVisible(false)}>
                      ✕
                    </button>
                  </div>
                  <Show
                    when={selectedEvidenceMessage()}
                    fallback={
                      <div class="drawer-empty">
                        <p>完成一次分析后，这里会显示数据来源、更新时间和缺失项。</p>
                      </div>
                    }
                  >
                    {(msg) => (
                      <div class="evidence-content">
                        <div class="evidence-summary">
                          <span class={`quality-badge is-${qualityMeta(msg()).tone}`}>
                            <i />
                            {qualityMeta(msg()).label}
                          </span>
                          <strong>{evidenceCount(msg())} 个来源</strong>
                          <Show when={msg().dataFreshness || lastDataFreshness()}>
                            <small>更新于 {formatEvidenceTime(msg().dataFreshness || lastDataFreshness())}</small>
                          </Show>
                        </div>
                        <Show when={msg().evidence?.length}>
                          <section class="evidence-section">
                            <h3>读取结果</h3>
                            <For each={msg().evidence}>
                              {(source) => (
                                <div class="evidence-source">
                                  <span class={`source-status is-${evidenceTagType(source.status)}`} />
                                  <div>
                                    <strong>{source.label || source.source || toolLabel(source.tool)}</strong>
                                    <small>
                                      {evidenceStatusLabel(source.status)}
                                      <Show when={source.sourceUpdatedAt} fallback={<Show when={source.observedAt}> · 读取 {formatEvidenceTime(source.observedAt)}</Show>}>
                                        {' '}
                                        · 更新 {formatEvidenceTime(source.sourceUpdatedAt)}
                                      </Show>
                                    </small>
                                  </div>
                                </div>
                              )}
                            </For>
                          </section>
                        </Show>
                        <Show when={msg().facts?.length}>
                          <section class="evidence-section">
                            <h3>已核验</h3>
                            <div class="fact-list">
                              <For each={msg().facts}>
                                {(fact) => (
                                  <span>
                                    {fact.label || fact.tool} · {fact.recordCount ?? 0} 条
                                  </span>
                                )}
                              </For>
                            </div>
                          </section>
                        </Show>
                        <Show when={msg().unknowns?.length}>
                          <section class="evidence-section">
                            <h3>尚未核验</h3>
                            <div class="fact-list is-warning">
                              <For each={msg().unknowns}>
                                {(unknown) => (
                                  <span>
                                    {unknown.label || unknown.tool} · {unknown.message || evidenceStatusLabel(unknown.status)}
                                  </span>
                                )}
                              </For>
                            </div>
                          </section>
                        </Show>
                        <Show when={msg().toolEvents?.length}>
                          <section class="evidence-section">
                            <h3>读取过程</h3>
                            <For each={msg().toolEvents}>
                              {(trace) => (
                                <div class="tool-trace-row">
                                  <span class={`trace-dot is-${trace.status || 'running'}`} />
                                  <span>{trace.type === 'tool_start' ? `读取 ${toolLabel(trace.tool)}` : `${toolLabel(trace.tool)}${trace.status === 'error' ? '失败' : '完成'}`}</span>
                                  <Show when={trace.latencyMs}>
                                    <small>{trace.latencyMs}ms</small>
                                  </Show>
                                </div>
                              )}
                            </For>
                          </section>
                        </Show>
                      </div>
                    )}
                  </Show>
                  <div class="evidence-note">
                    <p>助手会区分已读取的数据与模型推断；没有读取到的数据会明确标注。模型通道由站点统一配置，用户不能在此切换。</p>
                  </div>
                </aside>
              </Show>
            </div>
          </section>
        </Show>
      </main>
    </div>
  )
}
