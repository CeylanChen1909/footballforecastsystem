/**
 * 队名 / 联赛名工具。
 *
 * 来源（2026-11-24 修复）：本文件词典与 6 个原件函数（getTeamDisplayName /
 * getTeamSearchTokens / expandSearchQueries / matchLocalSearch /
 * findLeagueAlias / normalizeTeamSearch）逐字取自 GitHub r11 原件
 * frontend/src/utils/teamNames.js（148 行，156 支球队，SHA d1fe2a3…）。
 * 契约 §10 称本文件“不存在”是错的——本地镜像漏拷所致。
 *
 * 另保留 Solid 迁移为 import 面补的 3 个函数（r11 里不存在）：
 * displayTeamName / displayLeagueName / getLeagueDisplayName，以及
 * PRODUCTION_LEAGUES（10 联赛过滤表）与 getTeamNameMode（读
 * localStorage `ff_team_name_mode`）。display* 的中英映射同样取自
 * 上述原件词典（含别名），不再用重建版 44 对昵称表。
 *
 * 原件语义要点（与重建版的差异，见 fix.md）：
 *  - getTeamDisplayName(name, mode)：mode !== 'zh' 时原样返回 source，
 *    不查英文名；zh 模式查词典，无命中回退原名。
 *  - normalizeTeamSearch：NFKD 去重音 + 小写 + 去非字母数字汉字，
 *    不去 FC/AFC/SC/CF/CLUB 后缀。
 *  - getTeamSearchTokens：返回原始名数组 [source, en, zh, ...aliases]，
 *    不做归一化。
 *  - expandSearchQueries：双向子串匹配全表（球队 + 8 联赛别名）展开，
 *    不截断。
 *  - LEAGUE_ALIASES 只有 8 个联赛（无欧冠/欧国联）；联赛过滤用的
 *    PRODUCTION_LEAGUES（10 个）与 display* 的联赛表另行保留。
 */

// The crawler's primary source uses English club names. Keep the display and
// search aliases in one client-side dictionary so the match list can offer a
// predictable bilingual experience without changing the canonical IDs sent
// back to the API.
const TEAM_DIRECTORY = [
  ['Arsenal', '阿森纳', ['枪手']], ['Brentford', '布伦特福德'], ['Everton', '埃弗顿'],
  ['Hull City', '赫尔城'], ['Ipswich Town', '伊普斯维奇'], ['Leeds United', '利兹联'],
  ['Aston Villa', '阿斯顿维拉'], ['Bournemouth', '伯恩茅斯'], ['Brighton & Hove Albion', '布莱顿', ['Brighton']],
  ['Chelsea', '切尔西'], ['Fulham', '富勒姆'], ['Liverpool', '利物浦'],
  ['Manchester City', '曼城', ['曼彻斯特城', 'Man City']], ['Newcastle United', '纽卡斯尔联'],
  ['Sunderland', '桑德兰'], ['Nottingham Forest', '诺丁汉森林'], ['Crystal Palace', '水晶宫'],
  ['Manchester United', '曼联', ['曼彻斯特联', 'Man United']], ['Coventry City', '考文垂'],
  ['Tottenham Hotspur', '托特纳姆热刺', ['热刺', 'Tottenham']],

  ['Sevilla', '塞维利亚'], ['Alavés', '阿拉维斯', ['Alaves']], ['Espanyol', '西班牙人'],
  ['Atletico Madrid', '马德里竞技', ['马竞', 'Atleti']], ['Real Madrid', '皇家马德里', ['皇马']],
  ['Real Betis', '皇家贝蒂斯', ['贝蒂斯']], ['Racing de Santander', '桑坦德竞技'],
  ['Villarreal', '比利亚雷亚尔'], ['Deportivo de A Coruña', '拉科鲁尼亚'], ['Elche', '埃尔切'],
  ['Valencia', '瓦伦西亚'], ['Celta Vigo', '维戈塞尔塔'], ['Rayo Vallecano', '巴列卡诺'],
  ['Barcelona', '巴塞罗那', ['巴萨', 'Barça', 'Barca']], ['Osasuna', '奥萨苏纳'],
  ['Real Sociedad', '皇家社会'], ['Athletic Club', '毕尔巴鄂竞技'], ['Málaga', '马拉加'],
  ['Levante', '莱万特'], ['Getafe', '赫塔费'],

  ['Inter Milan', '国际米兰', ['国米']], ['Napoli', '那不勒斯'], ['Cagliari', '卡利亚里'],
  ['Como', '科莫'], ['Udinese', '乌迪内斯'], ['AC Milan', 'AC米兰', ['米兰']],
  ['Atalanta', '亚特兰大'], ['Bologna', '博洛尼亚'], ['Fiorentina', '佛罗伦萨'],
  ['Frosinone', '弗罗西诺内'], ['Juventus', '尤文图斯', ['尤文']], ['Lazio', '拉齐奥'],
  ['Lecce', '莱切'], ['Roma', '罗马'], ['Sassuolo', '萨索洛'], ['Torino', '都灵'],
  ['Venezia', '威尼斯'], ['Parma', '帕尔马'], ['Genoa', '热那亚'], ['Monza', '蒙扎'],

  ['Augsburg', '奥格斯堡'], ['Bayer 04 Leverkusen', '勒沃库森', ['Bayer Leverkusen']],
  ['Bayern Munich', '拜仁慕尼黑', ['拜仁']], ['Borussia Dortmund', '多特蒙德', ['多特']],
  ["Borussia M'gladbach", '门兴格拉德巴赫', ['门兴']], ['Eintracht Frankfurt', '法兰克福'],
  ['Elversberg', '艾尔弗斯贝格'], ['Köln', '科隆'], ['Freiburg', '弗赖堡'],
  ['Hamburger SV', '汉堡'], ['Hoffenheim', '霍芬海姆'], ['Mainz 05', '美因茨'],
  ['Paderborn', '帕德博恩'], ['RB Leipzig', 'RB莱比锡'], ['Schalke 04', '沙尔克04'],
  ['Stuttgart', '斯图加特'], ['Union Berlin', '柏林联合'], ['Werder Bremen', '云达不莱梅'],

  ['Marseille', '马赛'], ['Lens', '朗斯'], ['Olympique Lyonnais', '里昂', ['Lyon']],
  ['Brest', '布雷斯特'], ['Le Mans', '勒芒'], ['Lorient', '洛里昂'], ['Nice', '尼斯'],
  ['Paris FC', '巴黎FC'], ['Troyes', '特鲁瓦'], ['Angers', '昂热'], ['Le Havre', '勒阿弗尔'],
  ['Lille', '里尔'], ['Monaco', '摩纳哥'], ['Paris Saint-Germain', '巴黎圣日耳曼', ['PSG', '大巴黎']],
  ['Rennes', '雷恩'], ['Toulouse', '图卢兹'], ['Auxerre', '欧塞尔'], ['Strasbourg', '斯特拉斯堡'],

  ['AZ', '阿尔克马尔'], ['Go Ahead Eagles', '前进之鹰'], ['Groningen', '格罗宁根'],
  ['PSV', '埃因霍温', ['PSV埃因霍温', 'PSV Eindhoven']], ['Ajax', '阿贾克斯'], ['Feyenoord', '费耶诺德'],
  ['Sparta Rotterdam', '鹿特丹斯巴达'], ['Fortuna Sittard', '福图纳锡塔德'], ['Heerenveen', '海伦芬'],
  ['Excelsior', 'SBV精英'], ['NEC', '奈梅亨', ['NEC Nijmegen', 'Nijmegen']], ['Twente', '特温特'], ['Telstar', '特尔斯达'],
  ['PEC Zwolle', '兹沃勒'], ['Utrecht', '乌德勒支'], ['Willem II', '威廉二世'],
  ['Cambuur', '坎布尔'], ['ADO Den Haag', '海牙'],

  ['Sporting CP', '葡萄牙体育', ['里斯本竞技']], ['Marítimo', '马里迪莫'], ['Arouca', '阿罗卡'],
  ['Porto', '波尔图'], ['Nacional', '马德拉国民'], ['Santa Clara', '圣克拉拉'], ['Benfica', '本菲卡'],
  ['Gil Vicente', '吉尔维森特'], ['Rio Ave', '里奥阿维'], ['Estrela', '埃斯特雷拉'],
  ['Académico de Viseu', '维塞乌学院'], ['Sporting Braga', '布拉加'], ['Famalicão', '法马利康'],
  ['Moreirense', '莫雷伦斯'], ['Estoril', '埃斯托里尔'], ['Alverca', '阿维卡'],
  ['Vitória Guimarães', '吉马良斯'], ['Casa Pia', '卡萨皮亚'],

  ['Millwall', '米尔沃尔'], ['West Bromwich Albion', '西布罗姆维奇'], ['Charlton Athletic', '查尔顿'],
  ['Wolverhampton Wanderers', '狼队'], ['Queens Park Rangers', '女王公园巡游者', ['QPR']],
  ['Blackburn Rovers', '布莱克本'], ['Watford', '沃特福德'], ['Swansea City', '斯旺西'],
  ['Bolton Wanderers', '博尔顿'], ['Portsmouth', '朴茨茅斯'], ['Middlesbrough', '米德尔斯堡'],
  ['Cardiff City', '卡迪夫城'], ['Wrexham', '雷克瑟姆'], ['Birmingham City', '伯明翰'],
  ['Sheffield United', '谢菲尔德联'], ['West Ham United', '西汉姆联'], ['Derby County', '德比郡'],
  ['Burnley', '伯恩利'], ['Bristol City', '布里斯托尔城'], ['Lincoln City', '林肯城'],
  ['Preston North End', '普雷斯顿'], ['Stoke City', '斯托克城'], ['Norwich City', '诺维奇'],
  ['Southampton', '南安普顿']
].map(([en, zh, aliases = []]) => ({ en, zh, aliases }))

const LEAGUE_ALIASES = [
  { name: '英超', aliases: ['Premier League', 'EPL', '英格兰超级联赛'] },
  { name: '西甲', aliases: ['La Liga', '西班牙甲级联赛'] },
  { name: '意甲', aliases: ['Serie A', '意大利甲级联赛'] },
  { name: '德甲', aliases: ['Bundesliga', '德国甲级联赛'] },
  { name: '法甲', aliases: ['Ligue 1', '法国甲级联赛'] },
  { name: '荷甲', aliases: ['Eredivisie', '荷兰甲级联赛'] },
  { name: '葡超', aliases: ['Primeira Liga', '葡萄牙超级联赛'] },
  { name: '英冠', aliases: ['Championship', '英格兰冠军联赛'] }
]

const normalizeTeamName = value => String(value || '')
  .normalize('NFKD')
  .replace(/[\u0300-\u036f]/g, '')
  .toLowerCase()
  .replace(/[^a-z0-9\u4e00-\u9fff]+/g, '')

const findTeamEntry = name => {
  const key = normalizeTeamName(name)
  if (!key) return null
  return TEAM_DIRECTORY.find(item => [item.en, item.zh, ...item.aliases].some(value => normalizeTeamName(value) === key)) || null
}

export const getTeamDisplayName = (name, mode = 'zh') => {
  const source = String(name || '')
  if (mode !== 'zh') return source
  return findTeamEntry(source)?.zh || source
}

export const getTeamSearchTokens = name => {
  const source = String(name || '')
  const entry = findTeamEntry(source)
  return entry ? [source, entry.en, entry.zh, ...entry.aliases] : [source]
}

/** Expand a user query into bilingual aliases for API search fallbacks. */
export const expandSearchQueries = query => {
  const source = String(query || '').trim()
  if (!source) return []
  const key = normalizeTeamName(source)
  const queries = new Set([source])
  for (const item of TEAM_DIRECTORY) {
    const names = [item.en, item.zh, ...item.aliases]
    if (names.some(value => {
      const normalized = normalizeTeamName(value)
      return normalized === key || normalized.includes(key) || key.includes(normalized)
    })) {
      names.forEach(value => { if (value) queries.add(value) })
    }
  }
  for (const league of LEAGUE_ALIASES) {
    const names = [league.name, ...league.aliases]
    if (names.some(value => {
      const normalized = normalizeTeamName(value)
      return normalized === key || normalized.includes(key) || key.includes(normalized)
    })) {
      names.forEach(value => { if (value) queries.add(value) })
    }
  }
  return [...queries]
}

export const matchLocalSearch = (haystack, query) => {
  const tokens = expandSearchQueries(query).map(normalizeTeamName).filter(Boolean)
  const text = normalizeTeamName(haystack)
  if (!text || !tokens.length) return false
  return tokens.some(token => text.includes(token) || token.includes(text))
}

export const findLeagueAlias = query => {
  const key = normalizeTeamName(query)
  if (!key) return null
  return LEAGUE_ALIASES.find(item => [item.name, ...item.aliases].some(value => {
    const normalized = normalizeTeamName(value)
    return normalized === key || normalized.includes(key) || key.includes(normalized)
  })) || null
}

export const normalizeTeamSearch = normalizeTeamName
export const PRODUCTION_LEAGUES = ['英超', '西甲', '意甲', '德甲', '法甲', '荷甲', '葡超', '英冠', '欧冠', '欧国联']

/**
 * 联赛展示表（10 行，供 displayLeagueName / getLeagueDisplayName 用；
 * 注意与原件 LEAGUE_ALIASES（8 行，仅供 findLeagueAlias /
 * expandSearchQueries 用）不是同一张表）。
 */
const LEAGUE_TABLE = [
  { zh: '英超', en: 'Premier League', aliases: ['EPL', '英格兰超级联赛', '英超联赛'] },
  { zh: '西甲', en: 'La Liga', aliases: ['LaLiga', '西班牙甲级联赛'] },
  { zh: '意甲', en: 'Serie A', aliases: ['意大利甲级联赛'] },
  { zh: '德甲', en: 'Bundesliga', aliases: ['德国甲级联赛'] },
  { zh: '法甲', en: 'Ligue 1', aliases: ['法国甲级联赛'] },
  { zh: '荷甲', en: 'Eredivisie', aliases: ['荷兰甲级联赛'] },
  { zh: '葡超', en: 'Primeira Liga', aliases: ['葡萄牙超级联赛'] },
  { zh: '英冠', en: 'Championship', aliases: ['英格兰冠军联赛'] },
  { zh: '欧冠', en: 'UEFA Champions League', aliases: ['UCL', '欧洲冠军联赛'] },
  { zh: '欧国联', en: 'UEFA Nations League', aliases: ['UNL', '欧洲国家联赛'] },
]

const HAS_CJK = /[一-鿿]/

// 由原件 TEAM_DIRECTORY 派生的双向映射（含别名：中文别名 -> 英文全名，
// 英文别名 -> 中文全名），供 displayTeamName 用。
const ZH_TO_EN = new Map()
const EN_TO_ZH = new Map()
for (const item of TEAM_DIRECTORY) {
  ZH_TO_EN.set(item.zh, item.en)
  EN_TO_ZH.set(item.en, item.zh)
  for (const alias of item.aliases) {
    if (HAS_CJK.test(alias)) ZH_TO_EN.set(alias, item.en)
    else EN_TO_ZH.set(alias, item.zh)
  }
}

/** 当前队名显示模式（Matches 页的 teamNameMode 与本模块读同一键）。 */
export function getTeamNameMode() {
  try {
    return window.localStorage.getItem('ff_team_name_mode') === 'en' ? 'en' : 'zh'
  } catch {
    return 'zh'
  }
}

/**
 * 按当前显示模式返回展示名；无 display 值时回退 canonical；
 * 两者都空返回 ''（调用方另有 '主队'/'客队' 回退）。
 * display 是后端提供的本地化值（teamDisplayName/displayName/nameEn）。
 * （Solid 迁移补的函数，r11 原件无此函数；词典取自原件。）
 */
export function displayTeamName(canonicalName, displayName) {
  const canonical = String(canonicalName || '').trim()
  const display = String(displayName || '').trim()
  if (!canonical && !display) return ''
  const mode = getTeamNameMode()
  if (mode === 'en') {
    if (display && !HAS_CJK.test(display)) return display
    const fromDict = ZH_TO_EN.get(canonical) || ZH_TO_EN.get(display)
    if (fromDict) return fromDict
    if (display) return display
    return canonical
  }
  const fromDict = EN_TO_ZH.get(canonical) || EN_TO_ZH.get(display)
  if (fromDict) return fromDict
  if (display && HAS_CJK.test(display)) return display
  if (canonical && HAS_CJK.test(canonical)) return canonical
  return display || canonical
}

function findLeagueRow(name) {
  const key = String(name || '').trim()
  if (!key) return null
  const normalized = normalizeTeamSearch(key)
  return (
    LEAGUE_TABLE.find(
      (row) =>
        row.zh === key ||
        row.en.toLowerCase() === key.toLowerCase() ||
        row.aliases.some((a) => a.toLowerCase() === key.toLowerCase()) ||
        normalizeTeamSearch(row.en) === normalized ||
        row.aliases.some((a) => normalizeTeamSearch(a) === normalized),
    ) || null
  )
}

/**
 * 联赛版 displayTeamName。中文模式下返回中文 canonical 名本身
 * （联赛过滤用它与 PRODUCTION_LEAGUES 比对，必须稳定可比对）。
 * （Solid 迁移补的函数，r11 原件无此函数。）
 */
export function displayLeagueName(name, displayName) {
  const key = String(name || '').trim()
  const display = String(displayName || '').trim()
  if (!key && !display) return ''
  const row = findLeagueRow(key) || findLeagueRow(display)
  const mode = getTeamNameMode()
  if (row) return mode === 'en' ? row.en : row.zh
  if (mode === 'en') {
    if (display && !HAS_CJK.test(display)) return display
    return display || key
  }
  if (key && HAS_CJK.test(key)) return key
  return display || key
}

/** 单参数版：displayLeagueName(name) 无 display 参数的形态。 */
export function getLeagueDisplayName(name) {
  return displayLeagueName(name)
}

export default {
  displayTeamName,
  displayLeagueName,
  getLeagueDisplayName,
  getTeamDisplayName,
  getTeamSearchTokens,
  normalizeTeamSearch,
  expandSearchQueries,
  findLeagueAlias,
  matchLocalSearch,
  getTeamNameMode,
}
