/**
 * 更新日志按钮（自 Vue components/matches/ChangelogButton.vue 250 行移植）。
 *
 * 挂载位置：Vue 原版在 Matches 页的 AppTopNav `#actions` slot 里渲染；
 * Solid 侧顶栏由 App.jsx 全局挂载，故本组件由 AppTopNav 在 pathname ===
 * '/matches' 时渲染（见 AppTopNav.jsx），其余路由不出现——与原版一致。
 * r11 样式末尾的覆盖层把触发器改成 40px 无边框图标按钮、隐藏文字标签
 * （changelog.css 基础层末段），照搬；未读红点在原版模板里就没有渲染
 * （hasUnread 计算后未使用），不补。
 */
import { createEffect, createSignal, For, onSettled, Show } from 'solid-js'
import { changelogApi } from '../../api'
import '../../styles/changelog.css'

const STORAGE_KEY = 'football_changelog_last_seen'

const defaultEntries = [
  {
    id: '2026-08-25-account',
    date: '2026-08-25',
    tag: '账户',
    tone: 'account',
    title: '注册与账户资料体验更新',
    description: '优化邮箱验证码校验、头像同步和球队关注状态反馈。',
    details: ['验证码失败不会清空已填写内容', '个人资料头像会同步到顶部导航'],
  },
  {
    id: '2026-08-24-focus',
    date: '2026-08-24',
    tag: '赛程',
    tone: 'match',
    title: '比赛焦点改为动态推荐',
    description: '焦点比赛根据联赛、开赛距离和球队热度计算，不再绑定某个日期。',
    details: ['左侧焦点栏可快速打开比赛详情', '球队中文名、英文名支持切换和模糊搜索'],
  },
  {
    id: '2026-08-23-prediction',
    date: '2026-08-23',
    tag: '预测',
    tone: 'prediction',
    title: '预测状态表达更清晰',
    description: '区分已生成、生成中、数据不足和暂不可用，减少状态误读。',
    details: ['比赛时间和赛果状态统一按北京时间展示', '数据源延迟时保留明确的更新时间提示'],
  },
]

export default function ChangelogButton() {
  const [visible, setVisible] = createSignal(false)
  const [entries, setEntries] = createSignal(defaultEntries)

  const loadEntries = async () => {
    try {
      const remote = await changelogApi.list(10)
      if (Array.isArray(remote)) setEntries(remote)
    } catch {
      // 静态 fallback：旧部署等更新日志迁移与服务重启期间仍可用。
    }
  }

  const openChangelog = () => {
    setVisible(true)
    const first = entries()[0]
    if (first) localStorage.setItem(STORAGE_KEY, String(first.id))
  }

  // Esc 关闭 + 打开时锁定 body 滚动（与 Modal 同款做法；createEffect 双参）。
  createEffect(
    () => visible(),
    (open) => {
      if (!open) return
      const onKey = (e) => {
        if (e.key === 'Escape') setVisible(false)
      }
      document.addEventListener('keydown', onKey)
      const prevOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      return () => {
        document.removeEventListener('keydown', onKey)
        document.body.style.overflow = prevOverflow
      }
    },
  )

  // onSettled 挂载时拉一次远端列表（等价 onMounted(loadEntries)）。
  onSettled(() => {
    loadEntries()
  })

  return (
    <>
      <button
        type="button"
        class="changelog-trigger"
        aria-label="打开更新日志"
        title="更新日志"
        aria-controls="matches-changelog-drawer"
        onClick={openChangelog}
      >
        <svg class="changelog-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 2" stroke-linecap="round" />
        </svg>
        <span class="changelog-label">更新日志</span>
      </button>

      <Show when={visible()}>
        <div class="ff-dialog-overlay" onClick={() => setVisible(false)} />
        <div id="matches-changelog-drawer" class="changelog-drawer-panel" role="dialog" aria-modal="true" aria-label="更新日志">
          <div class="changelog-drawer-head">
            <div class="changelog-drawer-heading">
              <span class="changelog-drawer-kicker">CHANGELOG</span>
              <h2>更新日志</h2>
              <p>记录近期影响比赛浏览和预测体验的变化。</p>
            </div>
            <button type="button" class="ff-dialog-close" style="position:static" aria-label="关闭" onClick={() => setVisible(false)}>
              ×
            </button>
          </div>
          <div class="changelog-list" aria-live="polite">
            <Show when={entries().length === 0} fallback={
              <For each={entries()}>
                {(entry) => (
                  <article class="changelog-entry">
                    <div class="changelog-entry-marker" aria-hidden="true" />
                    <div class="changelog-entry-content">
                      <div class="changelog-entry-meta">
                        <time datetime={entry.date}>{entry.date}</time>
                        <span class={`changelog-entry-tag is-${entry.tone}`}>{entry.tag}</span>
                      </div>
                      <h3>{entry.title}</h3>
                      <p>{entry.description}</p>
                      <Show when={entry.details?.length}>
                        <ul class="changelog-entry-details">
                          <For each={entry.details}>{(detail) => <li>{detail}</li>}</For>
                        </ul>
                      </Show>
                    </div>
                  </article>
                )}
              </For>
            }>
              <div class="ff-empty">暂无更新日志</div>
            </Show>
          </div>
        </div>
      </Show>
    </>
  )
}
