/**
 * Prediction 页（自 Vue src/views/user/Prediction.vue 1462 行移植）。
 *
 * 对齐要点（契约 §1.4/§2.4）：
 *  - 与 /prediction/:fixtureId/detail 共用本页；仅当路径以 /detail 结尾且带
 *    resultLabel 查询参数时才从 query 水合快照（hydratePredictionFromQuery）。
 *  - fixtureId 非法/≤0 时预测状态直接为 UNAVAILABLE；无详情且无 query 上下文
 *    （homeName/awayName/matchTime 三者齐全才算有上下文）时判 fixtureNotFound。
 *  - 预测轮询：PENDING 时指数退避（delay=min(15000, 2500*2^floor(attempts/4))，
 *    hidden 标签页 10s），上限 24 次 → TIMEOUT；ERROR 停轮询。
 *  - 接口：crawlerApi.getMatchDetail（8s 超时 AbortController）、
 *    predictionApi.getCurrentByMatch（signal）、crawlerApi.getProxyH2H、
 *    favoriteApi.listMatches/addMatch/removeMatch、analyticsApi.track。
 *  - 提醒写回 slice(-100)（与 Matches 页的 slice(-50) 不一致——照搬原版）。
 *  - Element Plus 替换：el-card → div.ff-card；el-tag → span.ff-tag；
 *    el-collapse → 手写折叠；el-dropdown → 手写 popover；el-dialog → Modal；
 *    el-progress 由 ProbabilityPanel 内部实现；ElMessageBox.confirm → 共享
 *    ConfirmDialog（Kobalte，全量迁移组新增，恢复取消/关闭区分）。
 *  - 顶栏/页脚由 App.jsx 全局挂载，本页不嵌 AppTopNav。
 */
import { createEffect, createMemo, createSignal, For, Match, onSettled, Show, Switch } from 'solid-js'
import { useLocation, useNavigate, useParams, useSearchParams } from '@solidjs/router'
import { analyticsApi, crawlerApi, favoriteApi, predictionApi } from '../../api'
import auth from '../../stores/auth'
import { toastError, toastInfo, toastSuccess, toastWarning } from '../../utils/toast'
import { normalizeProbability, normalizeProbabilities, parseFeatureString } from '../../utils/prediction'
import { getMediaAssetUrl } from '../../utils/mediaAsset'
import PageState from '../../components/layout/PageState'
import PredictionProbabilityPanel from '../../components/prediction/PredictionProbabilityPanel'
import PredictionModelQuality from '../../components/prediction/PredictionModelQuality'
import PredictionFeatureContribution from '../../components/prediction/PredictionFeatureContribution'
import Modal from '../../components/ui/Modal'
import { createConfirm } from '../../components/ui/ConfirmDialog'
import { BellIcon, ChatIcon, CheckIcon, DataIcon, RefreshIcon, ShareIcon, StarIcon, TrendIcon, WarningIcon } from '../../components/ui/icons'
import '../../styles/prediction.css'
import '../../styles/page-common.css'

const BROWSER_NOTIFY_DISMISS_KEY = 'football_browser_notify_dismissed'

function buildSearch(obj) {
  const sp = new URLSearchParams()
  Object.entries(obj || {}).forEach(([k, v]) => {
    if (v === '' || v == null) return
    sp.set(k, String(v))
  })
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export default function Prediction() {
  const params = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // 共享确认弹窗（全量迁移组新增；替代 window.confirm，恢复取消/关闭区分）。
  const { ask: askConfirm, ConfirmDialog } = createConfirm()

  let fixtureId = 0
  let homeId = ''
  let awayId = ''
  let homeName = '主队'
  let awayName = '客队'

  const queryText = (value, fallback = '') => {
    const raw = Array.isArray(value) ? value[0] : value
    if (raw == null || raw === '') return fallback
    try { return decodeURIComponent(String(raw)) } catch { return String(raw) }
  }

  const syncRouteContext = () => {
    fixtureId = Number.parseInt(params.fixtureId, 10)
    homeId = String(searchParams.homeId || searchParams.home || '')
    awayId = String(searchParams.awayId || searchParams.away || '')
    homeName = queryText(searchParams.homeName || searchParams.home, '主队')
    awayName = queryText(searchParams.awayName || searchParams.away, '客队')
  }
  syncRouteContext()

  const [fixtureData, setFixtureData] = createSignal({
    teams: {
      home: { id: homeId, name: homeName, logo: queryText(searchParams.homeLogo) },
      away: { id: awayId, name: awayName, logo: queryText(searchParams.awayLogo) },
    },
  })
  const [predictionResult, setPredictionResult] = createSignal(null)
  const [predictionStatus, setPredictionStatus] = createSignal('LOADING')
  const [fixtureNotFound, setFixtureNotFound] = createSignal(false)
  const [predictionGeneratedAt, setPredictionGeneratedAt] = createSignal('')
  const [predicting, setPredicting] = createSignal(false)
  const [loading, setLoading] = createSignal(false)
  const [matchLoadError, setMatchLoadError] = createSignal('')
  let predictionPollTimer = null
  let predictionPollAttempts = 0
  let predictionPollInFlight = false
  let predictionAbortController = null
  let matchAbortController = null
  let pageMounted = false
  let loadSequence = 0
  let predictionRequestSequence = 0
  const [isFavorite, setIsFavorite] = createSignal(false)
  const [favoriteLoading, setFavoriteLoading] = createSignal(false)
  const [advancedPanels, setAdvancedPanels] = createSignal([])
  const [reminderSet, setReminderSet] = createSignal(false)
  const [h2hVisible, setH2hVisible] = createSignal(false)
  const [h2hLoading, setH2hLoading] = createSignal(false)
  const [h2hData, setH2hData] = createSignal(null)
  const [moreOpen, setMoreOpen] = createSignal(false)
  const isLoggedIn = createMemo(() => Boolean(auth.token()))

  const predictionStatusTitle = createMemo(() => ({
    LOADING: '正在读取比赛与预测状态',
    PENDING: '统一预测准备中',
    UNAVAILABLE: '本场暂无可用预测',
    EMPTY: '暂无预测数据',
    ERROR: '暂时无法读取预测结果',
    FAILED: '预测快照生成失败',
    TIMEOUT: '预测准备时间较长',
  }[predictionStatus()] || '预测状态未知'))
  const predictionStatusDescription = createMemo(() => {
    if (fixtureNotFound()) return '这场比赛已经从当前赛程中移除，或链接中的比赛 ID 无效。请返回比赛列表重新选择。'
    if (predictionStatus() === 'PENDING') return '系统会自动检查最新快照，不需要重复提交或连续点击。'
    if (predictionStatus() === 'UNAVAILABLE' || predictionStatus() === 'EMPTY') return matchLoadError() || '可能是联赛未覆盖、历史样本不足或赛前数据尚未同步。不会展示占位概率；可先查看其他比赛。'
    if (predictionStatus() === 'TIMEOUT') return '后台可能仍在准备数据；你可以稍后重新打开本场比赛。'
    if (predictionStatus() === 'ERROR' || predictionStatus() === 'FAILED') return matchLoadError() || '请点击“重新检查”再次读取，或返回比赛列表查看其他场次。'
    return '请稍候，页面将自动加载结果。'
  })

  const sampleCoverage = createMemo(() => {
    const meta = predictionResult()?.featureMeta || {}
    const home = Number(meta.homeSampleSize)
    const away = Number(meta.awaySampleSize)
    return {
      home: Number.isFinite(home) ? home : null,
      away: Number.isFinite(away) ? away : null,
    }
  })
  const prematchUnconfigured = createMemo(() => {
    const snapshot = predictionResult()?.featureMeta?.prematchSnapshot
    const quality = snapshot?.dataQuality && typeof snapshot.dataQuality === 'object' ? snapshot.dataQuality : null
    if (!quality) return false
    return ['injuries', 'lineups', 'xgShots'].some(key => String(quality[key] || '').toUpperCase() === 'NOT_CONFIGURED')
  })
  const dataInsufficient = createMemo(() => {
    if (!predictionResult()) return false
    const status = String(predictionResult().featureStatus || '').toUpperCase()
    if (status === 'DEFAULTED' || predictionResult().featureComplete === false && status !== 'LIMITED') return true
    const { home, away } = sampleCoverage()
    if (home != null && away != null && (home + away) <= 3) return true
    if ((home != null && home === 0) || (away != null && away === 0)) return true
    if (prematchUnconfigured() && (home == null || home < 3 || away == null || away < 3)) return true
    return false
  })
  const coverageBadge = createMemo(() => {
    if (!predictionResult()) return null
    const { home, away } = sampleCoverage()
    if (home != null && away != null) {
      const total = home + away
      if (total <= 3 || home === 0 || away === 0) {
        return { type: 'is-warning', text: `样本 ${home}/${away} · 数据不足` }
      }
      if (home < 3 || away < 3 || String(predictionResult().featureStatus || '').toUpperCase() === 'LIMITED') {
        return { type: 'is-info', text: `样本 ${home}/${away} · 覆盖有限` }
      }
      return { type: 'is-success', text: `样本 ${home}/${away}` }
    }
    if (prematchUnconfigured()) return { type: 'is-warning', text: '赛前特征未配置' }
    if (predictionResult().featureComplete === false) return { type: 'is-warning', text: '特征不完整' }
    return null
  })
  const dataFreshnessLabel = createMemo(() => {
    const meta = predictionResult()?.featureMeta || {}
    const snapshot = meta.prematchSnapshot || {}
    const stamp = snapshot.dataFetchedAt || snapshot.fetchedAt || meta.featureAsOf || predictionGeneratedAt()
    if (!stamp) return ''
    return `数据新鲜度：${formatDate(stamp)}`
  })
  const probabilityHint = createMemo(() => {
    if (dataInsufficient()) return '数据不足，暂不建议作为依据'
    if (predictionHasWarning()) return '特征或模型质量不足，仅供参考'
    return '来自正式预测服务'
  })
  const predictionQualityTitle = createMemo(() => {
    if (!predictionResult()) return ''
    if (isFinished() && predictionHit() !== null) {
      return predictionHit() ? '赛前预测已命中，下面展示本场复盘' : '赛前预测未命中，下面展示本场复盘'
    }
    if (dataInsufficient()) {
      return '数据不足，暂不建议作为依据'
    }
    if (!normalizedProbabilities().valid) {
      return '接口概率已自动归一化：本场结果仅供参考'
    }
    if (predictionResult().featureComplete === false) {
      return predictionResult().featureStatus === 'DEFAULTED'
        ? '特征数据不足：本次结果仅为低可信度参考'
        : predictionResult().featureStatus === 'LIMITED'
          ? '历史样本有限：本场使用 ELO+Poisson 保守基线'
          : '部分特征缺失：本次结果仅供参考'
    }
    const margin = Number(predictionResult().decisionMargin ?? predictionResult().featureMeta?.decisionMargin)
    if (Number.isFinite(margin) && margin < 0.08) {
      return '三类概率接近：本场不做强结论，仅作为信息参考'
    }
    const accuracy = Number(predictionResult().featureMeta?.model_accuracy)
    if (Number.isFinite(accuracy) && accuracy < 0.55) {
      return `模型质量偏低：${resultLabelShort()}（时间外测试准确率 ${(accuracy * 100).toFixed(1)}%，暂不建议据此做强结论）`
    }
    return `预测已生成：${resultLabelShort()}`
  })
  const predictionHasWarning = createMemo(() => {
    if (!predictionResult()) return false
    if (!normalizedProbabilities().valid) return true
    if (predictionResult().featureComplete === false) return true
    const accuracy = Number(predictionResult().featureMeta?.model_accuracy)
    const logLoss = Number(predictionResult().featureMeta?.model_log_loss)
    const baselineLogLoss = Number(predictionResult().featureMeta?.model_baseline_log_loss)
    return (Number.isFinite(accuracy) && accuracy < 0.55)
      || (Number.isFinite(logLoss) && Number.isFinite(baselineLogLoss) && logLoss >= baselineLogLoss)
      || (Number.isFinite(Number(predictionResult().decisionMargin)) && Number(predictionResult().decisionMargin) < 0.08)
  })
  const modelQuality = createMemo(() => predictionResult()?.featureMeta || {})
  const prematchQuality = createMemo(() => {
    const snapshot = modelQuality()?.prematchSnapshot
    if (!snapshot || typeof snapshot !== 'object') return null
    const quality = snapshot.dataQuality && typeof snapshot.dataQuality === 'object' ? snapshot.dataQuality : {}
    const labels = { AVAILABLE: '已接入', EMPTY: '暂无数据', NOT_CONFIGURED: '未配置', UNSUPPORTED: '套餐不支持', REQUEST_FAILED: '请求失败' }
    const status = key => labels[String(quality[key] || 'NOT_CONFIGURED').toUpperCase()] || String(quality[key] || '未知')
    const fetchedAt = snapshot.dataFetchedAt || snapshot.fetchedAt
    const trackedKeys = ['injuries', 'lineups', 'xgShots']
    return {
      source: snapshot.source || quality.historySource,
      updatedAt: fetchedAt ? `更新于 ${formatDate(fetchedAt)}` : snapshot.cutoffTime ? `数据截止 ${formatDate(snapshot.cutoffTime)}` : '更新时间未知',
      status: trackedKeys.some(key => String(quality[key] || '').toUpperCase() === 'REQUEST_FAILED') ? 'REQUEST_FAILED' : 'AVAILABLE',
      items: [
        { key: 'injuries', label: '伤停', value: status('injuries') },
        { key: 'lineups', label: '首发', value: status('lineups') },
        { key: 'xgShots', label: 'xG / 射门', value: status('xgShots') },
      ],
    }
  })
  const prematchQualityLabel = createMemo(() => {
    if (!prematchQuality()) return ''
    const available = prematchQuality().items.filter(item => item.value === '已接入').length
    return available === prematchQuality().items.length ? '数据完整' : `已接入 ${available}/${prematchQuality().items.length}`
  })
  const prematchQualityType = createMemo(() => prematchQuality()?.status === 'REQUEST_FAILED' ? 'is-danger' : 'is-info')
  const riskSummary = createMemo(() => {
    const risks = []
    const accuracy = Number(predictionResult()?.featureMeta?.model_accuracy)
    const margin = Number(predictionResult()?.decisionMargin)
    if (predictionResult()?.featureComplete === false) risks.push(predictionResult()?.featureStatus === 'LIMITED' ? '历史样本有限，使用保守基线' : '部分输入数据缺失')
    if (Number.isFinite(margin) && margin < 0.08) risks.push('三类概率接近')
    if (Number.isFinite(accuracy) && accuracy < 0.55) risks.push('模型近期测试表现偏弱')
    if (modelQuality()?.model_quality_gate?.eligible === false) risks.push('模型尚未通过生产门槛')
    if (prematchQuality()) {
      const available = prematchQuality().items.filter(item => item.value === '已接入').length
      if (available < prematchQuality().items.length) risks.push(`赛前数据已接入 ${available}/${prematchQuality().items.length}`)
    }
    return risks.length ? risks : ['当前没有检测到额外风险']
  })
  const qualityValue = (key) => {
    const value = Number(modelQuality()?.[key])
    return Number.isFinite(value) ? value : null
  }
  const modelQualityVisible = createMemo(() => Object.keys(modelQuality() || {}).some(key => key.startsWith('model_')))
  const qualityIsReliable = createMemo(() => {
    if (modelQuality()?.model_quality_gate?.eligible === false) return false
    if (modelQuality()?.model_quality_gate?.eligible === true) return true
    const accuracy = qualityValue('model_accuracy')
    const logLoss = qualityValue('model_log_loss')
    const baseline = qualityValue('model_baseline_log_loss')
    const hasBaselineComparison = logLoss != null && baseline != null
    return accuracy != null && accuracy >= 0.55 && hasBaselineComparison && logLoss < baseline
  })

  const homeColor = '#0f6b4d'
  const drawColor = '#909399'
  const awayColor = '#b27a18'

  // 纯函数：必须放在 memo 区之前——Solid 2.0 的 memo 在创建时即求值，
  // 后面定义的 const 在此处引用会触发 TDZ（实测 fixtureStatusLabel 报错）。
  const statusText = (status) => ({
    NS: '未开赛', LIVE: '进行中', '1H': '上半场', HT: '中场', '2H': '下半场',
    FT: '已结束', CANCEL: '已取消', POSTP: '已推迟',
  }[String(status || '').toUpperCase()] || '未开赛')

  const statusType = createMemo(() => {
    const s = fixtureData()?.fixture?.status?.short
    if (s === 'FT') return 'is-success'
    if (s === 'LIVE' || s === '1H' || s === '2H') return 'is-danger'
    return 'is-info'
  })

  const matchStatus = createMemo(() => String(fixtureData()?.fixture?.status?.short || 'NS').toUpperCase())
  const isFinished = createMemo(() => ['FT', 'AET', 'PEN', 'FINISHED'].includes(matchStatus()))
  const hasScore = createMemo(() => {
    const home = fixtureData()?.goals?.home
    const away = fixtureData()?.goals?.away
    const valid = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))
    return isFinished() && valid(home) && valid(away)
  })
  const actualResultLabel = createMemo(() => {
    if (!hasScore()) return ''
    const home = Number(fixtureData().goals.home)
    const away = Number(fixtureData().goals.away)
    return home > away ? '主胜' : home < away ? '客胜' : '平局'
  })
  const predictionHit = createMemo(() => {
    if (!actualResultLabel() || !predictionResult()?.resultLabel) return null
    const actual = actualResultLabel() === '主胜' ? 'HOME_WIN' : actualResultLabel() === '客胜' ? 'AWAY_WIN' : 'DRAW'
    return actual === predictionResult().resultLabel
  })
  const fixtureStatusLabel = createMemo(() => {
    const long = fixtureData()?.fixture?.status?.long
    return long || statusText(matchStatus())
  })
  const pageKicker = createMemo(() => isFinished() ? '赛后复盘' : '赛前预测')
  const modelDisplayName = createMemo(() => {
    const version = String(predictionResult()?.modelVersion || '').toLowerCase()
    if (version.includes('elo-calibrated')) return '校准 ELO 模型'
    if (version.includes('hybrid')) return '混合模型'
    if (version.includes('xgboost')) return 'XGBoost 模型'
    if (version.includes('poisson')) return '进球分布模型'
    if (version) return '赛前预测模型'
    return '预测模型'
  })

  const normalizedProbabilities = createMemo(() => {
    return normalizeProbabilities([
      predictionResult()?.homeWinProb,
      predictionResult()?.drawProb,
      predictionResult()?.awayWinProb,
    ])
  })

  const resultClass = createMemo(() => {
    const r = predictionResult()?.resultLabel
    if (r === 'HOME_WIN') return 'result-home'
    if (r === 'AWAY_WIN') return 'result-away'
    return 'result-draw'
  })
  const resultLabelShort = createMemo(() => {
    const r = predictionResult()?.resultLabel
    if (r === 'HOME_WIN') return '主胜'
    if (r === 'AWAY_WIN') return '客胜'
    return '平局'
  })
  const confidenceValue = createMemo(() => {
    if (!predictionResult()) return 'N/A'
    if (dataInsufficient()) return 'N/A'
    const margin = Number(predictionResult().decisionMargin ?? predictionResult().featureMeta?.decisionMargin)
    if (Number.isFinite(margin)) return `${Math.round(Math.max(0, margin) * 100)}%`
    const values = [predictionResult().homeWinProb, predictionResult().drawProb, predictionResult().awayWinProb]
      .map(normalizeProbability).filter(value => value !== null).sort((a, b) => b - a)
    return values.length > 1 ? `${Math.round((values[0] - values[1]) * 100)}%` : 'N/A'
  })
  const confidenceLabel = createMemo(() => {
    if (!predictionResult()) return predictionStatus() === 'LOADING' ? '正在读取统一快照' : '等待统一快照'
    if (dataInsufficient()) return '数据不足，暂不建议作为依据'
    const label = predictionResult()?.confidenceLabel || predictionResult()?.featureMeta?.confidenceLabel || '概率分离程度'
    return `${label}（未校准，不代表命中率）`
  })
  const statusActionLabel = createMemo(() => ({
    PENDING: '自动检查中',
    ERROR: '重新读取结果',
    FAILED: '重新读取结果',
    TIMEOUT: '重新读取结果',
    UNAVAILABLE: '查看其他比赛',
  }[predictionStatus()] || '查看生成状态'))
  const primaryDecisionText = createMemo(() => {
    if (isFinished()) {
      if (predictionHit() === true) return `赛前判断与最终赛果一致，概率仅代表赛前信息下的倾向。`
      if (predictionHit() === false) return `赛前判断与最终赛果不同，下面保留原始概率用于复盘。`
      return '比赛已经结束，以下内容保留赛前预测快照。'
    }
    if (dataInsufficient()) return '数据不足，暂不建议作为依据；下方概率仅作低可信度参考。'
    return `模型更偏向${resultLabelShort()}，当前概率分离度为 ${confidenceValue()}；请结合下方风险提示阅读。`
  })

  const featureLabelMap = {
    elo_diff: 'ELO差值',
    win_rate_diff: '近期胜率差',
    goal_diff: '场均进球差',
    h2h_balance: '历史交锋平衡',
    rest_days_diff: '休息天数差',
    home_elo: '主队ELO',
    away_elo: '客队ELO',
    home_win_rate: '主队胜率',
    away_win_rate: '客队胜率',
    home_avg_goals: '主队场均进球',
    away_avg_goals: '客队场均进球',
    home_avg_loss: '主队场均失球',
    away_avg_loss: '客队场均失球',
    home_days_rest: '主队休息天数',
    away_days_rest: '客队休息天数',
    h2h_home_wins: '历史交锋主胜',
    h2h_draws: '历史交锋平局',
    h2h_away_wins: '历史交锋客胜',
    homeWinProb: '主胜概率',
    drawProb: '平局概率',
    awayWinProb: '客胜概率',
    modelVersion: '模型版本',
  }

  const featureContrib = createMemo(() => {
    if (!predictionResult()) return null
    const topFeatures = Array.isArray(predictionResult().topFeatures) ? predictionResult().topFeatures : []

    const parsedTopFeatures = topFeatures.map((item, index) => {
      const parsed = typeof item === 'string' ? parseFeatureString(item) : null
      if (parsed) {
        const feature = parsed.feature || `feature_${index + 1}`
        const label = parsed.label || featureLabelMap[feature] || feature
        const importance = Number(parsed.importance ?? 0)
        const valueText = parsed.value !== undefined ? `${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''}` : `${importance || Math.max(0, 100 - index * 18)}%`
        return {
          feature,
          label,
          importance: Number.isFinite(importance) && importance > 0 ? importance : null,
          detail: parsed.value !== undefined
            ? `${label}当前值：${parsed.value}${parsed.unit ? ` ${parsed.unit}` : ''} · 这是模型参考输入，不代表因果关系`
            : `${label}作为模型参考输入，仅展示相对排序，不代表因果关系`,
          valueText,
          percent: 0,
        }
      }

      if (typeof item === 'string') {
        return {
          feature: item,
          label: featureLabelMap[item] || item,
          importance: null,
          detail: `${featureLabelMap[item] || item}作为模型参考输入，仅展示相对排序，不代表因果关系`,
          valueText: `排名 ${index + 1}`,
          percent: 0,
        }
      }

      const feature = item?.feature || item?.name || item?.label || `feature_${index + 1}`
      const label = item?.label || featureLabelMap[feature] || feature
      const importance = Number(item?.importance ?? item?.value ?? 0)
      const unit = item?.unit ? String(item.unit) : ''
      return {
        feature,
        label,
        importance: Number.isFinite(importance) && importance > 0 ? importance : null,
        detail: item?.value !== undefined
          ? `${label}当前值：${item.value}${unit ? ` ${unit}` : ''} · 这是模型参考输入，不代表因果关系`
          : `${label}作为模型参考输入，仅展示相对排序，不代表因果关系`,
        valueText: item?.value !== undefined ? String(item.value) : (importance > 0 ? `${importance}` : `排名 ${index + 1}`),
        percent: 0,
      }
    })

    if (parsedTopFeatures.length) {
      const maxImportance = Math.max(...parsedTopFeatures.map(item => Number(item.importance) || 0), 0)
      return parsedTopFeatures.map((item, index) => ({
        ...item,
        percent: maxImportance > 0 && item.importance
          ? Math.max(8, Math.round((item.importance / maxImportance) * 100))
          : Math.max(8, 100 - index * 18),
      }))
    }
    // 没有特征输入时不把预测输出概率伪装成“特征贡献”。
    return []
  })

  const normalizeMatchDetail = (payload) => {
    const raw = Array.isArray(payload)
      ? payload[0]
      : (payload?.response?.[0] || payload?.data?.response?.[0] || payload?.data || payload)
    if (!raw || typeof raw !== 'object') return null
    if (raw.fixture && raw.teams) return raw
    if (!raw.homeTeamName && !raw.awayTeamName && !raw.matchTime) return null
    const status = raw.status || 'NS'
    return {
      fixture: {
        id: raw.fixtureId || raw.externalMatchId || raw.id || fixtureId,
        status: { short: status, long: statusText(status) },
        venue: { name: raw.venue || '' },
        date: raw.matchTime || '',
      },
      league: { id: raw.leagueId || '', name: raw.leagueName || '', round: raw.round || '' },
      teams: {
        home: { id: raw.homeTeamId || '', name: raw.homeTeamName || '主队', logo: raw.homeTeamLogo || '' },
        away: { id: raw.awayTeamId || '', name: raw.awayTeamName || '客队', logo: raw.awayTeamLogo || '' },
      },
      goals: { home: raw.homeScore ?? null, away: raw.awayScore ?? null },
    }
  }

  const hydratePredictionFromQuery = () => {
    if (!location.pathname.endsWith('/detail') || !searchParams.resultLabel) return
    const probabilities = [searchParams.homeWinProb, searchParams.drawProb, searchParams.awayWinProb].map(value => Number(value))
    if (probabilities.some(value => !Number.isFinite(value) || value < 0) || probabilities.every(value => value === 0)) return
    setPredictionResult({
      fixtureId,
      modelVersion: searchParams.modelVersion || 'baseline-elo-v1',
      homeWinProb: Number(searchParams.homeWinProb),
      drawProb: Number(searchParams.drawProb),
      awayWinProb: Number(searchParams.awayWinProb),
      resultLabel: searchParams.resultLabel,
      explanation: searchParams.explanation || '',
      featureComplete: searchParams.featureComplete !== 'false',
      featureStatus: searchParams.featureStatus || 'COMPLETE',
      fallbackReason: searchParams.fallbackReason || '',
      decisionMargin: searchParams.decisionMargin ? Number(searchParams.decisionMargin) : null,
      confidenceLabel: searchParams.confidenceLabel || '',
      featureMeta: searchParams.modelAccuracy ? { model_accuracy: Number(searchParams.modelAccuracy) } : {},
    })
  }

  const applyPredictionSnapshot = (snapshot) => {
    if (!snapshot || String(snapshot.status || '').toUpperCase() !== 'READY' || !snapshot.resultLabel) return false
    const probabilities = [snapshot.homeWinProb, snapshot.drawProb, snapshot.awayWinProb].map(value => Number(value))
    if (probabilities.some(value => !Number.isFinite(value) || value < 0) || probabilities.every(value => value === 0)) return false
    setPredictionResult({
      ...snapshot,
      fixtureId,
      topFeatures: Array.isArray(snapshot.topFeatures) ? snapshot.topFeatures : [],
      featureMeta: snapshot.featureMeta && typeof snapshot.featureMeta === 'object' ? snapshot.featureMeta : {},
      featureComplete: snapshot.featureComplete !== false && Number(snapshot.featureComplete) !== 0,
    })
    setPredictionGeneratedAt(snapshot.generatedAt || '')
    setPredictionStatus('READY')
    const home = snapshot.homeTeamName || snapshot.home_team_name
    const away = snapshot.awayTeamName || snapshot.away_team_name
    if (home || away || snapshot.matchTime || snapshot.homeTeamLogo || snapshot.awayTeamLogo) {
      setFixtureData({
        ...fixtureData(),
        fixture: { ...fixtureData()?.fixture, date: fixtureData()?.fixture?.date || snapshot.matchTime },
        teams: {
          home: { ...fixtureData()?.teams?.home, name: home || fixtureData()?.teams?.home?.name, logo: snapshot.homeTeamLogo || fixtureData()?.teams?.home?.logo },
          away: { ...fixtureData()?.teams?.away, name: away || fixtureData()?.teams?.away?.name, logo: snapshot.awayTeamLogo || fixtureData()?.teams?.away?.logo },
        },
        league: { ...fixtureData()?.league, name: snapshot.leagueName || fixtureData()?.league?.name },
      })
    }
    return true
  }

  const stopPredictionPolling = () => {
    predictionRequestSequence += 1
    if (predictionPollTimer) {
      window.clearTimeout(predictionPollTimer)
      predictionPollTimer = null
    }
    predictionAbortController?.abort()
    predictionAbortController = null
    predictionPollInFlight = false
    predictionPollAttempts = 0
  }

  // Solid 2.0 暂缓写入：setPredictionStatus 后立即读信号仍是旧值。
  // loadCachedPrediction 刚 set 完就调 queue 时必须用本地状态直传，
  // 否则首轮 PENDING 永远排不上定时器、轮询在第 1 次后就停了
  // （2026-11-24 实测：拦截恒返 PENDING，364s 内只发了 1 次请求）。
  const queuePredictionPolling = (statusOverride) => {
    const status = statusOverride ?? predictionStatus()
    if (status !== 'PENDING' || predictionPollTimer || predictionPollInFlight) return
    if (predictionPollAttempts >= 24) {
      setPredictionStatus('TIMEOUT')
      return
    }
    const delay = document.hidden ? 10000 : Math.min(15000, 2500 * (2 ** Math.floor(predictionPollAttempts / 4)))
    predictionPollTimer = window.setTimeout(async () => {
      predictionPollTimer = null
      predictionPollAttempts += 1
      await loadCachedPrediction({ silent: true })
      if (predictionStatus() === 'PENDING') queuePredictionPolling()
    }, delay)
  }

  const loadCachedPrediction = async ({ silent = false } = {}) => {
    if (!Number.isFinite(fixtureId) || fixtureId <= 0) {
      setPredictionStatus('UNAVAILABLE')
      stopPredictionPolling()
      return
    }
    predictionPollInFlight = true
    predictionAbortController?.abort()
    const requestId = ++predictionRequestSequence
    const controller = new AbortController()
    predictionAbortController = controller
    // 本地状态直传：见 queuePredictionPolling 注释，set 后立即读信号是旧值。
    let nextStatus = ''
    try {
      const snapshot = await predictionApi.getCurrentByMatch(fixtureId, { signal: controller.signal })
      if (requestId !== predictionRequestSequence || controller.signal.aborted) return ''
      if (applyPredictionSnapshot(snapshot)) {
        stopPredictionPolling()
        analyticsApi.track('prediction_viewed', {
          page: location.pathname + location.search,
          entityType: 'match',
          entityId: fixtureId,
          properties: { modelVersion: predictionResult()?.modelVersion || '' },
        }).catch(() => {})
        return 'READY'
      }
      nextStatus = String(snapshot?.status || 'PENDING').toUpperCase()
      setPredictionStatus(nextStatus)
      if (!predictionResult() && nextStatus === 'UNAVAILABLE') {
        const reason = snapshot?.fallbackReason || snapshot?.featureMeta?.gateReason
        setMatchLoadError(reason ? `数据不足，暂不预测：${reason}` : '本场暂时没有可用预测，请稍后重试。')
      }
      if (nextStatus === 'PENDING') queuePredictionPolling(nextStatus)
      else if (nextStatus !== 'LOADING') stopPredictionPolling()
      return nextStatus
    } catch (error) {
      if (requestId !== predictionRequestSequence) return ''
      if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError') return ''
      setPredictionStatus('ERROR')
      if (!predictionResult()) setMatchLoadError('预测结果暂时不可用，请稍后刷新。')
      stopPredictionPolling()
      return 'ERROR'
    } finally {
      if (requestId === predictionRequestSequence) {
        if (predictionAbortController === controller) predictionAbortController = null
        predictionPollInFlight = false
        if (!silent && nextStatus === 'PENDING') queuePredictionPolling(nextStatus)
      }
    }
  }

  const loadMatch = async () => {
    const sequence = ++loadSequence
    setLoading(true)
    setMatchLoadError('')
    setFixtureNotFound(false)
    try {
      const homeTeam = fixtureData()?.teams?.home || {}
      const awayTeam = fixtureData()?.teams?.away || {}
      const queryLeague = queryText(searchParams.leagueName)
      const fallback = {
        ...fixtureData(),
        fixture: {
          id: fixtureId,
          status: { short: 'NS', long: '未开赛' },
          venue: { name: '' },
          date: queryText(searchParams.matchTime),
        },
        league: { id: queryText(searchParams.leagueId), name: queryLeague, round: queryText(searchParams.round) },
        teams: {
          home: { ...homeTeam, id: homeId || homeTeam.id, name: homeTeam.name || homeName, logo: homeTeam.logo || queryText(searchParams.homeLogo) },
          away: { ...awayTeam, id: awayId || awayTeam.id, name: awayTeam.name || awayName, logo: awayTeam.logo || queryText(searchParams.awayLogo) },
        },
        goals: { home: null, away: null },
      }
      setFixtureData(fallback)

      // Defaults such as “主队 / 客队” are presentation placeholders, not proof
      // that the deep link carries a real fixture context. Only preserve the
      // fallback card when the URL actually contains both teams and kickoff time.
      const hasQueryContext = Boolean(
        queryText(searchParams.homeName || searchParams.home)
        && queryText(searchParams.awayName || searchParams.away)
        && queryText(searchParams.matchTime),
      )
      if (Number.isFinite(fixtureId) && fixtureId > 0) {
        matchAbortController?.abort()
        const controller = new AbortController()
        matchAbortController = controller
        const timeoutId = window.setTimeout(() => controller.abort(), 8000)
        try {
          const remote = normalizeMatchDetail(await crawlerApi.getMatchDetail(fixtureId, { signal: controller.signal }))
          if (sequence !== loadSequence) return
          if (remote) {
            setFixtureData({
              ...fallback,
              ...remote,
              fixture: { ...fallback.fixture, ...remote.fixture, status: { ...fallback.fixture.status, ...remote.fixture?.status }, venue: { ...fallback.fixture.venue, ...remote.fixture?.venue } },
              league: { ...fallback.league, ...remote.league },
              teams: { home: { ...fallback.teams.home, ...remote.teams?.home }, away: { ...fallback.teams.away, ...remote.teams?.away } },
              goals: { ...fallback.goals, ...remote.goals },
            })
          } else if (!hasQueryContext) {
            setFixtureNotFound(true)
            setPredictionStatus('UNAVAILABLE')
            setMatchLoadError('未找到该场比赛的详细信息。')
          }
        } catch (error) {
          if (controller.signal.aborted && sequence !== loadSequence) return
          if (!hasQueryContext) {
            setFixtureNotFound(true)
            setPredictionStatus('UNAVAILABLE')
            setMatchLoadError(error?.name === 'AbortError' ? '比赛详情加载超时，无法确认这场比赛。' : '比赛详情加载失败，无法确认这场比赛。')
          }
        } finally {
          window.clearTimeout(timeoutId)
          if (matchAbortController === controller) matchAbortController = null
        }
      }
      if (sequence !== loadSequence) return
      hydratePredictionFromQuery()
      if (!fixtureNotFound()) await loadCachedPrediction()
    } finally {
      if (sequence === loadSequence) setLoading(false)
    }
  }

  const doPredict = async () => {
    setPredicting(true)
    try {
      // 手动刷新是一次新的轮询周期，避免上一次超时计数阻止后续重试。
      stopPredictionPolling()
      const status = await loadCachedPrediction()
      if (status === 'READY') toastSuccess('已加载本场统一预测结果')
      else if (status === 'PENDING') toastInfo('预测正在生成，请稍后刷新')
    } catch (e) {
      setPredictionStatus('ERROR')
      toastWarning('预测结果暂不可用，请稍后重试')
    } finally {
      setPredicting(false)
    }
  }

  const showH2H = async () => {
    const homeTeamName = fixtureData()?.teams?.home?.name || homeName
    const awayTeamName = fixtureData()?.teams?.away?.name || awayName
    if (!homeTeamName || !awayTeamName) return
    setH2hVisible(true)
    setH2hLoading(true)
    try {
      const res = await crawlerApi.getProxyH2H(homeTeamName, awayTeamName, 10)
      setH2hData(res?.data || res)
    } catch (e) {
      setH2hData({
        recentMatches: [],
        summary: { homeWins: 0, draws: 0, awayWins: 0 },
        homeTeamName,
        awayTeamName,
        source: 'fallback',
      })
    } finally {
      setH2hLoading(false)
    }
  }

  const goTeamSquad = (teamId, side) => {
    const team = fixtureData()?.teams?.[side] || {}
    const target = team.name || (teamId ? String(teamId) : '')
    if (!target) return
    navigate(`/team/${encodeURIComponent(target)}/squad${buildSearch({
      name: target,
      teamId: String(team.id || teamId || ''),
      logo: team.logo || '',
      league: fixtureData()?.league?.name || queryText(searchParams.leagueName, ''),
      season: String(new Date().getFullYear()),
    })}`)
  }

  const backToMatches = () => {
    const query = {}
    ;['returnDate', 'returnLeague', 'returnKeyword', 'returnFavorites', 'returnPage'].forEach(key => {
      if (searchParams[key] != null && searchParams[key] !== '') query[key] = String(searchParams[key])
    })
    navigate(Object.keys(query).length ? `/matches${buildSearch(query)}` : '/matches')
  }

  const readMatchReminders = () => {
    try { return JSON.parse(localStorage.getItem('football_match_reminders') || '[]') } catch { return [] }
  }
  const writeMatchReminders = (items) => localStorage.setItem('football_match_reminders', JSON.stringify(items.slice(-100)))
  const syncReminderState = () => {
    setReminderSet(readMatchReminders().some(item => String(item?.fixtureId || '') === String(fixtureId)))
  }
  const toggleReminder = async () => {
    if (isFinished() || !fixtureId) return
    const items = readMatchReminders()
    if (reminderSet()) {
      writeMatchReminders(items.filter(item => String(item?.fixtureId || '') !== String(fixtureId)))
      setReminderSet(false)
      toastSuccess('已关闭本场提醒')
      return
    }
    const matchTime = fixtureData()?.fixture?.date || searchParams.matchTime || ''
    if (!matchTime || !parseBusinessDate(matchTime)) {
      toastInfo('当前比赛时间尚未同步，暂时无法设置提醒')
      return
    }
    writeMatchReminders([...items.filter(item => String(item?.fixtureId || '') !== String(fixtureId)), {
      fixtureId: String(fixtureId),
      title: `${fixtureData()?.teams?.home?.name || homeName} vs ${fixtureData()?.teams?.away?.name || awayName}`,
      matchTime,
      notified: false,
    }])
    setReminderSet(true)
    if (typeof Notification !== 'undefined' && Notification.permission === 'default') Notification.requestPermission().catch(() => {})
    toastSuccess('已设置开赛提醒')
  }
  const handleDecisionCommand = (command) => {
    setMoreOpen(false)
    if (command === 'share') shareReport()
    if (command === 'agent') openAgent()
    if (command === 'reminder') toggleReminder()
  }

  const normalizeFavoriteList = (payload) => {
    const data = payload?.data ?? payload
    if (Array.isArray(data)) return data
    if (Array.isArray(data?.items)) return data.items
    if (Array.isArray(data?.records)) return data.records
    return []
  }

  const loadFavoriteState = async () => {
    if (!isLoggedIn() || !fixtureId) {
      setIsFavorite(false)
      return
    }
    try {
      const favorites = normalizeFavoriteList(await favoriteApi.listMatches())
      setIsFavorite(favorites.some(item => String(item?.matchId || item?.id || item?.fixtureId || '') === String(fixtureId)))
    } catch {
      // 收藏状态失败不影响预测报告，保持未选中并允许用户重试。
      setIsFavorite(false)
    }
  }

  // 原版 ElMessageBox.confirm：点“取消”按钮才记 dismissed，点 × 关闭不记。
  // 全量迁移组改用共享 ConfirmDialog 恢复该区分（ask 返回 true/false/null）。
  const maybeAskBrowserNotifyAfterFavorite = async () => {
    if (typeof Notification === 'undefined') return
    if (Notification.permission !== 'default') return
    if (localStorage.getItem(BROWSER_NOTIFY_DISMISS_KEY) === '1') return
    const answer = await askConfirm({
      title: '开启浏览器通知',
      message: '收藏后可在开赛前收到站内提醒。是否同时开启浏览器通知？可稍后在通知中心处理。',
      confirmText: '开启通知',
      cancelText: '暂不开启',
    })
    if (answer === true) {
      await Notification.requestPermission()
    } else if (answer === false) {
      localStorage.setItem(BROWSER_NOTIFY_DISMISS_KEY, '1')
    }
  }

  const toggleFavorite = async () => {
    if (!isLoggedIn()) {
      auth.openAuthDialog(location.pathname + location.search, 'login')
      return
    }
    if (!fixtureId || favoriteLoading()) return
    setFavoriteLoading(true)
    try {
      if (isFavorite()) {
        await favoriteApi.removeMatch(fixtureId)
        setIsFavorite(false)
        toastSuccess('已取消收藏比赛')
      } else {
        const home = fixtureData()?.teams?.home?.name || homeName
        const away = fixtureData()?.teams?.away?.name || awayName
        await favoriteApi.addMatch(fixtureId, `${home} vs ${away}`, {
          leagueName: fixtureData()?.league?.name || queryText(searchParams.leagueName, ''),
          matchTime: fixtureData()?.fixture?.date || queryText(searchParams.matchTime, ''),
        })
        setIsFavorite(true)
        toastSuccess('比赛收藏成功，开赛前将站内提醒')
        maybeAskBrowserNotifyAfterFavorite()
      }
    } catch (error) {
      toastError(error?.message || '收藏操作失败，请稍后重试')
    } finally {
      setFavoriteLoading(false)
    }
  }

  const shareReport = async () => {
    const title = `${fixtureData()?.teams?.home?.name || homeName} vs ${fixtureData()?.teams?.away?.name || awayName} · 预测报告`
    const url = window.location.href
    try {
      if (navigator.share) {
        await navigator.share({ title, text: '查看这场比赛的统一预测与赛前数据覆盖', url })
      } else if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(url)
        toastSuccess('报告链接已复制')
      } else {
        window.prompt('复制报告链接', url)
      }
      analyticsApi.track('prediction_shared', { page: location.pathname + location.search, entityType: 'match', entityId: fixtureId }).catch(() => {})
    } catch (error) {
      if (error?.name !== 'AbortError') toastInfo('分享已取消')
    }
  }

  const handleVisibilityChange = () => {
    if (!document.hidden && predictionStatus() === 'PENDING' && !predictionPollTimer && !predictionPollInFlight) {
      queuePredictionPolling()
    }
  }

  const openAgent = () => {
    const homeTeam = fixtureData()?.teams?.home || {}
    const awayTeam = fixtureData()?.teams?.away || {}
    navigate(`/agent${buildSearch({
      fixtureId: String(fixtureId),
      homeTeamId: homeTeam.id || homeId || '',
      awayTeamId: awayTeam.id || awayId || '',
      homeName: homeTeam.name || homeName,
      awayName: awayTeam.name || awayName,
      leagueName: fixtureData()?.league?.name || searchParams.leagueName || '',
      matchTime: fixtureData()?.fixture?.date || searchParams.matchTime || '',
      resultLabel: predictionResult()?.resultLabel || '',
      homeWinProb: predictionResult()?.homeWinProb ?? '',
      drawProb: predictionResult()?.drawProb ?? '',
      awayWinProb: predictionResult()?.awayWinProb ?? '',
      explanation: predictionResult()?.explanation || '',
      contextSummary: `${modelDisplayName()}；${prematchQualityLabel() || '赛前数据覆盖未知'}；${isFinished() ? `实际结果${actualResultLabel() || '未知'}` : '比赛尚未结束'}`,
    })}`)
  }

  const parseBusinessDate = value => {
    if (!value) return null
    const text = String(value).trim().replace(' ', 'T')
    const normalized = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}+08:00`
    const date = new Date(normalized)
    return Number.isNaN(date.getTime()) ? null : date
  }
  const formatDate = (dateStr) => {
    if (!dateStr) return ''
    const date = parseBusinessDate(dateStr)
    if (!date) return '时间待定'
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    }).format(date) + '（北京时间）'
  }

  // 状态分支（Vue 模板是 v-else-if 链，只能渲染其一；Solid 侧用单一 memo
  // + Switch 保持互斥，否则 matchLoadError 与 ERROR 状态会同时出两块）。
  const stateBranch = createMemo(() => {
    if (fixtureNotFound()) return 'notfound'
    if (loading() && !predictionResult() && !matchLoadError()) return 'skeleton'
    if (matchLoadError() && !predictionResult()) return 'matchError'
    if (!predictionResult() && predictionStatus() === 'LOADING') return 'loading'
    if (!predictionResult() && ['ERROR', 'FAILED', 'TIMEOUT'].includes(predictionStatus())) return 'statusError'
    if (!predictionResult() && predictionStatus() === 'UNAVAILABLE') return 'unavailable'
    if (!predictionResult() && predictionStatus() === 'PENDING') return 'pending'
    return 'ready'
  })

  const togglePanel = (name) => {
    setAdvancedPanels(panels => panels.includes(name) ? panels.filter(p => p !== name) : [...panels, name])
  }

  // “更多”菜单打开时，点外部关闭（与 MatchCard 的下拉菜单同款）。
  createEffect(
    () => moreOpen(),
    (open) => {
      if (!open) return
      const onDocClick = (e) => {
        if (!e.target.closest?.('.more-actions')) setMoreOpen(false)
      }
      const onKey = (e) => { if (e.key === 'Escape') setMoreOpen(false) }
      document.addEventListener('click', onDocClick)
      document.addEventListener('keydown', onKey)
      return () => {
        document.removeEventListener('click', onDocClick)
        document.removeEventListener('keydown', onKey)
      }
    },
  )

  onSettled(() => {
    pageMounted = true
    document.addEventListener('visibilitychange', handleVisibilityChange)
    syncReminderState()
    void (async () => {
      await loadMatch()
      await loadFavoriteState()
      syncReminderState()
    })()
    return () => {
      pageMounted = false
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      stopPredictionPolling()
      matchAbortController?.abort()
    }
  })

  // Vue watch(() => route.fullPath)：同组件内切到另一场比赛时重载。
  // Solid Router 切参时组件可能复用，用 location 变化 + 上次路径守卫模拟。
  let lastFullPath = location.pathname + location.search
  createEffect(
    () => location.pathname + location.search,
    (fullPath) => {
      if (fullPath === lastFullPath) return
      lastFullPath = fullPath
      if (!pageMounted) return
      stopPredictionPolling()
      setPredictionResult(null)
      setPredictionGeneratedAt('')
      setPredictionStatus('LOADING')
      setMatchLoadError('')
      syncRouteContext()
      setIsFavorite(false)
      setReminderSet(false)
      setFixtureData({
        teams: {
          home: { id: homeId, name: homeName, logo: queryText(searchParams.homeLogo) },
          away: { id: awayId, name: awayName, logo: queryText(searchParams.awayLogo) },
        },
      })
      void (async () => {
        await loadMatch()
        await loadFavoriteState()
        syncReminderState()
      })()
    },
  )

  return (
    <div class="prediction-page">
      <div class="prediction-main">
        <div class="prediction-inner">
          <section class="report-heading ff-appear">
            <div class="report-heading-main">
              <span class="ff-kicker">{pageKicker()}</span>
              <Show when={fixtureNotFound()} fallback={<h1>{fixtureData()?.teams?.home?.name || '主队'} <em>vs</em> {fixtureData()?.teams?.away?.name || '客队'}</h1>}>
                <h1>未找到这场比赛</h1>
              </Show>
              <Show when={fixtureNotFound()} fallback={<p>{fixtureData()?.league?.name || '比赛分析'} · {formatDate(fixtureData()?.fixture?.date) || '时间待定'}</p>}>
                <p>请返回比赛列表重新选择有效的比赛链接。</p>
              </Show>
            </div>
            <div class="report-heading-meta">
              <span class={`ff-tag ${statusType()}`}>{fixtureStatusLabel()}</span>
              <div class="report-confidence">
                <span>概率分离度</span>
                <strong>{confidenceValue()}</strong>
                <small>{confidenceLabel()}</small>
                <Show when={coverageBadge()}>
                  <span class={`ff-tag coverage-badge ${coverageBadge().type}`}>{coverageBadge().text}</span>
                </Show>
                <Show when={dataFreshnessLabel()}>
                  <small class="freshness-label">{dataFreshnessLabel()}</small>
                </Show>
              </div>
            </div>
          </section>

          <Switch>
            <Match when={stateBranch() === 'notfound'}>
              <PageState
                title="未找到这场比赛"
                description="这场比赛已经从当前赛程中移除，或链接中的比赛 ID 无效。请返回比赛列表重新选择。"
                actionText="返回比赛列表"
                onAction={backToMatches}
              />
            </Match>
            <Match when={stateBranch() === 'skeleton'}>
              <div class="prediction-skeleton" role="status" aria-live="polite" aria-busy="true" aria-label="正在加载比赛与预测">
                <PageState type="loading" title="正在加载比赛与预测" description="正在读取赛程详情与统一预测快照…" size={32} />
                <div class="prediction-skeleton-card" aria-hidden="true">
                  <i class="sk-line sk-wide" />
                  <div class="sk-prob-grid"><i /><i /><i /></div>
                  <i class="sk-line sk-mid" />
                </div>
              </div>
            </Match>
            <Match when={stateBranch() === 'matchError'}>
              <PageState
                type="error"
                title="比赛详情加载失败"
                description={matchLoadError()}
                actionText="重试"
                onAction={loadMatch}
              />
            </Match>
            <Match when={stateBranch() === 'loading'}>
              <PageState
                type="loading"
                title={predictionStatusTitle()}
                description={predictionStatusDescription()}
                size={32}
              />
            </Match>
            <Match when={stateBranch() === 'statusError'}>
              <PageState
                type="error"
                title={predictionStatusTitle()}
                description={predictionStatusDescription()}
                actionText="重新检查"
                onAction={doPredict}
              />
            </Match>
            <Match when={stateBranch() === 'unavailable'}>
              <PageState
                title="本场暂无可用预测"
                description={predictionStatusDescription()}
                actionText="查看其他比赛"
                onAction={backToMatches}
              />
            </Match>
            <Match when={stateBranch() === 'pending'}>
              <PageState
                type="loading"
                title={predictionStatusTitle()}
                description={predictionStatusDescription()}
                size={32}
              />
            </Match>
          </Switch>

          <Show when={predictionResult()}>
            <div class="ff-card decision-card">
              <div class="ff-card-body">
                <div class="decision-card-head">
                  <div>
                    <span class="section-eyebrow">{isFinished() ? '赛后复盘' : '统一预测结论'}</span>
                    <h2>{resultLabelShort()}</h2>
                    <p>{primaryDecisionText()}</p>
                  </div>
                  <div class="decision-actions">
                    <button type="button" class="ff-btn ff-btn-ghost favorite-action" disabled={favoriteLoading()} onClick={toggleFavorite}>
                      <StarIcon size={14} />{isFavorite() ? '已收藏' : '收藏比赛'}
                    </button>
                    <div class="more-actions">
                      <button type="button" class="ff-btn ff-btn-ghost more-action" aria-label="更多操作" onClick={() => setMoreOpen(!moreOpen())}>更多</button>
                      <Show when={moreOpen()}>
                        <div class="more-menu" role="menu">
                          <button type="button" role="menuitem" onClick={() => handleDecisionCommand('share')}><ShareIcon size={14} />分享报告</button>
                          <button type="button" role="menuitem" onClick={() => handleDecisionCommand('agent')}><ChatIcon size={14} />让助手解读</button>
                          <Show when={!isFinished()}>
                            <button type="button" role="menuitem" onClick={() => handleDecisionCommand('reminder')}><BellIcon size={14} />{reminderSet() ? '关闭开赛提醒' : '设置开赛提醒'}</button>
                          </Show>
                        </div>
                      </Show>
                    </div>
                  </div>
                </div>

                <div class={`decision-lead ${resultClass()}`}>
                  <div class="decision-lead-icon">{isFinished() ? (predictionHit() === false ? <WarningIcon size={28} /> : <CheckIcon size={28} />) : <TrendIcon size={28} />}</div>
                  <div><strong>{isFinished() ? actualResultLabel() || '比赛已结束' : `模型倾向：${resultLabelShort()}`}</strong><span>{confidenceLabel()} · {predictionGeneratedAt() ? `生成于 ${formatDate(predictionGeneratedAt())}` : '统一预测快照'}</span></div>
                </div>

                <PredictionProbabilityPanel
                  homeName={fixtureData()?.teams?.home?.name || '主队'}
                  awayName={fixtureData()?.teams?.away?.name || '客队'}
                  probabilities={normalizedProbabilities()}
                  warning={predictionHasWarning() || dataInsufficient()}
                  hint={probabilityHint()}
                  homeColor={homeColor}
                  drawColor={drawColor}
                  awayColor={awayColor}
                />

                <Show when={isFinished() && actualResultLabel()}>
                  <div class={`review-summary ${predictionHit() === true ? 'review-hit' : predictionHit() === false ? 'review-miss' : ''}`}>
                    <div><span>实际结果</span><strong>{actualResultLabel()}</strong></div>
                    <div><span>赛前预测</span><strong>{resultLabelShort()}</strong></div>
                    <Show when={predictionHit() !== null}>
                      <span class={`ff-tag ${predictionHit() ? 'is-success' : 'is-danger'}`}>{predictionHit() ? '预测命中' : '预测未命中'}</span>
                    </Show>
                  </div>
                </Show>

                <div class={`decision-notice ${predictionHasWarning() ? 'notice-caution' : 'notice-positive'}`}>
                  <span class="notice-dot" /><strong>{predictionQualityTitle()}</strong><span>{riskSummary().join(' · ')}</span>
                </div>

                <Show when={prematchQuality()}>
                  <div class="prematch-quality-panel" aria-label="赛前数据覆盖">
                    <div class="quality-panel-head"><div><strong>赛前数据覆盖</strong><small>{prematchQuality().source || '本地赛前快照'} · {prematchQuality().updatedAt || '更新时间未知'}</small></div><span class={`ff-tag ${prematchQualityType()}`}>{prematchQualityLabel()}</span></div>
                    <div class="prematch-quality-grid"><For each={prematchQuality().items}>{(item) => <div><span>{item.label}</span><strong>{item.value}</strong></div>}</For></div>
                  </div>
                </Show>

                <div class="ff-collapse advanced-panels">
                  <Show when={modelQualityVisible()}>
                    <div>
                      <button type="button" class={`ff-collapse-head ${advancedPanels().includes('quality') ? 'is-open' : ''}`} onClick={() => togglePanel('quality')}>
                        模型质量与适用范围<span class="ff-collapse-arrow">▾</span>
                      </button>
                      <Show when={advancedPanels().includes('quality')}>
                        <div class="ff-collapse-body">
                          <PredictionModelQuality quality={modelQuality()} visible={modelQualityVisible()} reliable={qualityIsReliable()} />
                        </div>
                      </Show>
                    </div>
                  </Show>
                  <Show when={featureContrib()?.length}>
                    <div>
                      <button type="button" class={`ff-collapse-head ${advancedPanels().includes('factors') ? 'is-open' : ''}`} onClick={() => togglePanel('factors')}>
                        影响较大的输入指标<span class="ff-collapse-arrow">▾</span>
                      </button>
                      <Show when={advancedPanels().includes('factors')}>
                        <div class="ff-collapse-body">
                          <PredictionFeatureContribution items={featureContrib()} />
                        </div>
                      </Show>
                    </div>
                  </Show>
                </div>
              </div>
            </div>
          </Show>

          {/* 比赛信息放在结论之后，避免用户先读技术细节。 */}
          <Show when={!fixtureNotFound()}>
            <div class={`ff-card match-info-card ${loading() ? 'is-loading' : ''}`}>
              <div class="ff-card-body">
                <div class="card-header">
                  <span>比赛信息</span>
                  <div class="header-actions">
                    <Show when={loading()}><span class="ff-tag is-info">读取中…</span></Show>
                    <button type="button" class="ff-btn ff-btn-ghost ff-btn-sm" onClick={showH2H}><DataIcon size={14} />查看交锋</button>
                  </div>
                </div>
                <div class="match-detail">
                  <div class="team-block" role="button" tabindex="0" aria-label={`查看${fixtureData()?.teams?.home?.name || '主队'}阵容`} onClick={() => goTeamSquad(homeId, 'home')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); goTeamSquad(homeId, 'home') } }}>
                    <Show when={fixtureData()?.teams?.home?.logo} fallback={<div class="logo-placeholder">主</div>}>
                      <img src={getMediaAssetUrl(fixtureData().teams.home.logo)} alt={`${fixtureData()?.teams?.home?.name || '主队'}队徽`} class="big-logo" width="80" height="80" decoding="async" />
                    </Show>
                    <div class="team-name-lg">{fixtureData()?.teams?.home?.name || '主队'}</div><small>查看阵容</small>
                  </div>
                  <div class="vs-block">
                    <Show when={hasScore()}><div class="final-score">{fixtureData().goals.home} - {fixtureData().goals.away}</div></Show>
                    <div class="vs-label">VS</div>
                    <div class="match-date">{formatDate(fixtureData()?.fixture?.date)}</div>
                    <Show when={fixtureData()?.fixture?.venue?.name}><div class="match-venue">{fixtureData().fixture.venue.name}</div></Show>
                  </div>
                  <div class="team-block" role="button" tabindex="0" aria-label={`查看${fixtureData()?.teams?.away?.name || '客队'}阵容`} onClick={() => goTeamSquad(awayId, 'away')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); goTeamSquad(awayId, 'away') } }}>
                    <Show when={fixtureData()?.teams?.away?.logo} fallback={<div class="logo-placeholder">客</div>}>
                      <img src={getMediaAssetUrl(fixtureData().teams.away.logo)} alt={`${fixtureData()?.teams?.away?.name || '客队'}队徽`} class="big-logo" width="80" height="80" decoding="async" />
                    </Show>
                    <div class="team-name-lg">{fixtureData()?.teams?.away?.name || '客队'}</div><small>查看阵容</small>
                  </div>
                </div>
              </div>
            </div>
          </Show>

          {/* 历史交锋弹窗 */}
          <Modal open={h2hVisible()} title="历史交锋记录" onClose={() => setH2hVisible(false)}>
            <Show
              when={!h2hLoading()}
              fallback={<div class="loading-state"><div class="h2h-skeleton"><i /><i /><i /><i /><i /></div></div>}
            >
              <Show when={h2hData()}>
                <div class="h2h-content">
                  <div class="h2h-summary">
                    <div class="summary-team home">
                      <Show when={fixtureData()?.teams?.home?.logo}>
                        <img src={getMediaAssetUrl(fixtureData().teams.home.logo)} alt={`${fixtureData()?.teams?.home?.name || '主队'}队徽`} class="summary-logo" />
                      </Show>
                      <span>{fixtureData()?.teams?.home?.name}</span>
                    </div>
                    <div class="summary-stats">
                      <div class="stat-box">
                        <span class="stat-num">{h2hData().summary?.homeWins || 0}</span>
                        <span class="stat-desc">主队胜</span>
                      </div>
                      <div class="stat-box">
                        <span class="stat-num">{h2hData().summary?.draws || 0}</span>
                        <span class="stat-desc">平局</span>
                      </div>
                      <div class="stat-box">
                        <span class="stat-num">{h2hData().summary?.awayWins || 0}</span>
                        <span class="stat-desc">客队胜</span>
                      </div>
                    </div>
                    <div class="summary-team away">
                      <Show when={fixtureData()?.teams?.away?.logo}>
                        <img src={getMediaAssetUrl(fixtureData().teams.away.logo)} alt={`${fixtureData()?.teams?.away?.name || '客队'}队徽`} class="summary-logo" />
                      </Show>
                      <span>{fixtureData()?.teams?.away?.name}</span>
                    </div>
                  </div>
                  <div class="h2h-matches">
                    <For each={h2hData().recentMatches || []}>
                      {(match, idx) => (
                        <div class="h2h-match">
                          <span class="h2h-date">{match.date}</span>
                          <span class="h2h-teams">{match.homeTeam} vs {match.awayTeam}</span>
                          <span class="h2h-score">{match.homeScore} - {match.awayScore}</span>
                        </div>
                      )}
                    </For>
                    <Show when={!h2hData().recentMatches?.length}>
                      <div class="h2h-empty">暂无历史交锋记录</div>
                    </Show>
                  </div>
                </div>
              </Show>
            </Show>
          </Modal>

          {/* 页面底部操作：只保留一个主要动作，避免反复点击误解。 */}
          <Show when={!fixtureNotFound()}>
            <div class="action-area">
              <div class="action-buttons">
                <Show
                  when={predictionResult()}
                  fallback={
                    <button type="button" class="ff-btn ff-btn-primary predict-btn" disabled={predicting()} onClick={() => predictionStatus() === 'UNAVAILABLE' ? backToMatches() : doPredict()}>
                      <TrendIcon size={16} />{statusActionLabel()}
                    </button>
                  }
                >
                  <button type="button" class="ff-btn ff-btn-primary predict-btn" disabled={predicting()} onClick={doPredict}><RefreshIcon size={16} />检查最新快照</button>
                </Show>
                <button type="button" class="ff-btn ff-btn-ghost" onClick={backToMatches}>返回比赛列表</button>
              </div>
              <div class="login-tip">预测结果按比赛统一生成；{isLoggedIn() ? '可收藏比赛并设置提醒。' : '登录后可收藏比赛并设置提醒。'}</div>
            </div>
          </Show>
        </div>
      </div>
      <ConfirmDialog />
    </div>
  )
}
