// The crawler's primary source uses English club names. Keep the display and
// search aliases in one client-side dictionary so the match list can offer a
// predictable bilingual experience without changing the canonical IDs sent
// back to the API.
import { locale, t } from '../i18n'

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
  ['Southampton', '南安普顿'],

  // UEFA Nations League — national sides (EN → ZH; aliases for common variants)
  ['France', '法国'], ['Portugal', '葡萄牙'], ['Spain', '西班牙'],
  ['England', '英格兰'], ['Germany', '德国'], ['Italy', '意大利'],
  ['Netherlands', '荷兰', ['Holland']], ['Belgium', '比利时'],
  ['Croatia', '克罗地亚'], ['Denmark', '丹麦'], ['Switzerland', '瑞士'],
  ['Scotland', '苏格兰'], ['Poland', '波兰'], ['Turkey', '土耳其', ['Türkiye', 'Turkiye']],
  ['Austria', '奥地利'], ['Serbia', '塞尔维亚'], ['Hungary', '匈牙利'],
  ['Wales', '威尔士'], ['Ukraine', '乌克兰'],
  ['Czechia', '捷克', ['Czech Republic', 'Czech Rep', 'Czech']],
  ['Kazakhstan', '哈萨克斯坦'], ['Israel', '以色列'], ['Gibraltar', '直布罗陀'],
  ['Iceland', '冰岛'], ['Lithuania', '立陶宛'], ['Montenegro', '黑山'],
  ['Albania', '阿尔巴尼亚'], ['Armenia', '亚美尼亚'], ['Slovakia', '斯洛伐克'],
  ['Bosnia and Herzegovina', '波黑', ['Bosnia-Herzegovina', 'Bosnia & Herzegovina', 'Bosnia', 'BiH']],
  ['Finland', '芬兰'], ['Romania', '罗马尼亚'], ['Sweden', '瑞典'],
  ['Norway', '挪威'], ['Ireland', '爱尔兰', ['Republic of Ireland', 'ROI']],
  ['Northern Ireland', '北爱尔兰'], ['Greece', '希腊'], ['Georgia', '格鲁吉亚'],
  ['Slovenia', '斯洛文尼亚'], ['North Macedonia', '北马其顿', ['Macedonia', 'FYROM']],
  ['Cyprus', '塞浦路斯'], ['Latvia', '拉脱维亚'], ['Estonia', '爱沙尼亚'],
  ['Belarus', '白俄罗斯', ['Belorussia']], ['Moldova', '摩尔多瓦'],
  ['Faroe Islands', '法罗群岛', ['Faroes']], ['Luxembourg', '卢森堡'],
  ['Malta', '马耳他'], ['Andorra', '安道尔'], ['San Marino', '圣马力诺'],
  ['Liechtenstein', '列支敦士登'], ['Kosovo', '科索沃'],
  ['Bulgaria', '保加利亚'], ['Azerbaijan', '阿塞拜疆'],

  // Frequent UCL participants not already covered as domestic clubs
  ['Club Brugge', '布鲁日', ['Club Bruges', 'Brugge']],
  ['Galatasaray', '加拉塔萨雷'],
  ['Olympiacos', '奥林匹亚科斯', ['Olympiakos']],
  ['Red Star Belgrade', '贝尔格莱德红星', ['Red Star', 'Crvena Zvezda', 'FK Crvena zvezda']],
  ['Slavia Prague', '布拉格斯拉维亚', ['Slavia Praha', 'SK Slavia Prague']]
].map(([en, zh, aliases = []]) => ({ en, zh, aliases }))

const LEAGUE_ALIASES = [
  { name: '英超', aliases: ['Premier League', 'EPL', '英格兰超级联赛'] },
  { name: '西甲', aliases: ['La Liga', '西班牙甲级联赛'] },
  { name: '意甲', aliases: ['Serie A', '意大利甲级联赛'] },
  { name: '德甲', aliases: ['Bundesliga', '德国甲级联赛'] },
  { name: '法甲', aliases: ['Ligue 1', '法国甲级联赛'] },
  { name: '荷甲', aliases: ['Eredivisie', '荷兰甲级联赛'] },
  { name: '葡超', aliases: ['Primeira Liga', '葡萄牙超级联赛'] },
  { name: '英冠', aliases: ['Championship', '英格兰冠军联赛'] },
  { name: '欧冠', aliases: ['UEFA Champions League', 'Champions League', 'UCL', '欧洲冠军联赛', 'bbc-champions-league'] },
  { name: '欧国联', aliases: ['UEFA Nations League', 'Nations League', 'UNL', '欧洲国家联赛', 'bbc-nations-league'] }
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

const firstDisplayText = (...values) => values
  .map(value => (value == null ? '' : String(value).trim()))
  .find(Boolean) || ''

const isEnglishLabel = value => /[A-Za-z]/.test(String(value || '')) && !/[\u4e00-\u9fff]/.test(String(value || ''))
const hasChineseLabel = value => /[\u4e00-\u9fff]/.test(String(value || ''))

// Display fields are optional English labels from the backend. Use them when
// they match the active UI language; otherwise fall back to the existing
// client-side translation so a Chinese UI never shows English-only labels.
const displayForLocale = (...values) => {
  const display = firstDisplayText(...values)
  if (!display) return ''
  if (locale.value === 'en') return hasChineseLabel(display) ? '' : display
  return hasChineseLabel(display) ? display : ''
}

export const getTeamDisplayName = (name, mode = 'zh', displayName = '') => {
  const source = String(name || '')
  const display = displayForLocale(displayName)
  if (display) return display
  if (mode !== 'zh') return source
  return findTeamEntry(source)?.zh || source
}

/** Locale-aware display name: backend display field first, then the local dictionary. */
export const displayTeamName = (name, displayName = '') => {
  const source = String(name || '')
  const display = displayForLocale(displayName)
  if (display) return display
  return locale.value === 'zh' ? getTeamDisplayName(source, 'zh') : source
}

/** Display a player/team/person name from optional backend display fields. */
export const displayPersonName = (name, displayName = '') => displayTeamName(name, displayName)

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

/** Map English / slug league labels to Chinese canonical names for filters and headers. */
export const getLeagueDisplayName = name => {
  const source = String(name || '')
  if (!source) return source
  return findLeagueAlias(source)?.name || source
}

/**
 * Locale-aware league label. Keep getLeagueDisplayName() canonical for
 * filters/query params; use this helper only when rendering text.
 */
export const displayLeagueName = (name, displayName = '') => {
  const source = String(name || '')
  const display = displayForLocale(displayName)
  if (display) return display
  if (!source) return source
  const entry = findLeagueAlias(source)
  const canonical = entry?.name || source
  if (locale.value === 'zh') return canonical
  return entry?.aliases?.find(isEnglishLabel) || source
}

const POSITION_KEYS = [
  { key: 'goalkeeper', test: /goalkeeper|keeper|门将/i },
  { key: 'defender', test: /defender|defence|defense|back|后卫/i },
  { key: 'midfielder', test: /midfielder|midfield|中场|前腰|后腰/i },
  { key: 'forward', test: /forward|attacker|striker|winger|前锋|边锋|中锋/i }
]

/** Prefer a backend positionLabel, otherwise translate the raw position. */
export const displayPositionName = (position, positionLabel = '') => {
  const display = displayForLocale(positionLabel)
  if (display) return display
  const source = String(position || '').trim()
  if (!source) return ''
  const entry = POSITION_KEYS.find(item => item.test.test(source))
  return entry ? t(`prediction.teamSquad.${entry.key}`) : source
}

export const getPositionLabel = displayPositionName

export const normalizeTeamSearch = normalizeTeamName
