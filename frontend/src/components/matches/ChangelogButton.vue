<template>
  <button
    type="button"
    class="changelog-trigger"
    aria-label="打开更新日志"
    aria-controls="matches-changelog-drawer"
    @click="openChangelog"
  >
    <span v-if="hasUnread" class="changelog-pulse" aria-hidden="true"></span>
    <el-icon class="changelog-icon" aria-hidden="true"><Clock /></el-icon>
    <span>更新日志</span>
  </button>

  <el-drawer
    id="matches-changelog-drawer"
    v-model="visible"
    class="changelog-drawer"
    direction="rtl"
    size="min(430px, 92vw)"
    append-to-body
  >
    <template #header>
      <div class="changelog-drawer-heading">
        <span class="changelog-drawer-kicker">CHANGELOG</span>
        <h2>更新日志</h2>
        <p>记录近期影响比赛浏览和预测体验的变化。</p>
      </div>
    </template>

    <div class="changelog-list" aria-live="polite">
      <el-empty v-if="!entries.length" description="暂无更新日志" :image-size="72" />
      <article v-for="entry in entries" :key="entry.id" class="changelog-entry">
        <div class="changelog-entry-marker" aria-hidden="true"></div>
        <div class="changelog-entry-content">
          <div class="changelog-entry-meta">
            <time :datetime="entry.date">{{ entry.date }}</time>
            <span class="changelog-entry-tag" :class="`is-${entry.tone}`">{{ entry.tag }}</span>
          </div>
          <h3>{{ entry.title }}</h3>
          <p>{{ entry.description }}</p>
          <ul v-if="entry.details?.length" class="changelog-entry-details">
            <li v-for="detail in entry.details" :key="detail">{{ detail }}</li>
          </ul>
        </div>
      </article>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Clock } from '@element-plus/icons-vue'
import { changelogApi } from '../../api'

const STORAGE_KEY = 'football_changelog_last_seen'
const visible = ref(false)
const lastSeen = ref(localStorage.getItem(STORAGE_KEY) || '')

const defaultEntries = [
  {
    id: '2026-08-25-account',
    date: '2026-08-25',
    tag: '账户',
    tone: 'account',
    title: '注册与账户资料体验更新',
    description: '优化邮箱验证码校验、头像同步和球队关注状态反馈。',
    details: ['验证码失败不会清空已填写内容', '个人资料头像会同步到顶部导航']
  },
  {
    id: '2026-08-24-focus',
    date: '2026-08-24',
    tag: '赛程',
    tone: 'match',
    title: '比赛焦点改为动态推荐',
    description: '焦点比赛根据联赛、开赛距离和球队热度计算，不再绑定某个日期。',
    details: ['左侧焦点栏可快速打开比赛详情', '球队中文名、英文名支持切换和模糊搜索']
  },
  {
    id: '2026-08-23-prediction',
    date: '2026-08-23',
    tag: '预测',
    tone: 'prediction',
    title: '预测状态表达更清晰',
    description: '区分已生成、生成中、数据不足和暂不可用，减少状态误读。',
    details: ['比赛时间和赛果状态统一按北京时间展示', '数据源延迟时保留明确的更新时间提示']
  }
]
const entries = ref(defaultEntries)

const hasUnread = computed(() => entries.value.length > 0 && (!lastSeen.value || lastSeen.value !== String(entries.value[0].id)))

const loadEntries = async () => {
  try {
    const remote = await changelogApi.list(10)
    if (Array.isArray(remote)) entries.value = remote
  } catch {
    // The static fallback keeps the entry useful while an older deployment
    // is waiting for the changelog migration and service restart.
  }
}

const openChangelog = () => {
  visible.value = true
  if (entries.value.length) {
    lastSeen.value = String(entries.value[0].id)
    localStorage.setItem(STORAGE_KEY, String(entries.value[0].id))
  }
}

onMounted(loadEntries)
</script>

<style scoped>
.changelog-trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 11px;
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  background: var(--ff-surface);
  color: var(--ff-text-muted);
  font: 600 12px/1 var(--ff-sans, 'Segoe UI', sans-serif);
  cursor: pointer;
  transition: border-color var(--ff-transition-fast), color var(--ff-transition-fast), background var(--ff-transition-fast), transform var(--ff-transition-fast);
}

.changelog-trigger:hover {
  border-color: var(--ff-primary);
  background: var(--ff-primary-soft);
  color: var(--ff-primary);
  transform: translateY(-1px);
}

.changelog-trigger:focus-visible {
  outline: 2px solid var(--ff-primary);
  outline-offset: 2px;
}

.changelog-icon { font-size: 14px; }
.changelog-pulse { position: absolute; top: -3px; right: -3px; width: 8px; height: 8px; border: 2px solid var(--ff-surface); border-radius: 50%; background: #d04444; animation: changelog-pulse 1.8s ease-in-out infinite; }

@keyframes changelog-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(208, 68, 68, .28); }
  50% { box-shadow: 0 0 0 5px rgba(208, 68, 68, 0); }
}

.changelog-drawer-heading { padding-right: 14px; }
.changelog-drawer-kicker { color: var(--ff-primary); font: 700 10px/1 var(--ff-mono); letter-spacing: .16em; }
.changelog-drawer-heading h2 { margin: 8px 0 5px; color: var(--ff-text-strong); font-size: 21px; letter-spacing: -.03em; }
.changelog-drawer-heading p { margin: 0; color: var(--ff-text-muted); font-size: 12px; line-height: 1.6; }
.changelog-list { display: flex; flex-direction: column; gap: 0; padding: 2px 2px 20px 4px; }
.changelog-entry { position: relative; display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 12px; padding: 0 0 26px; }
.changelog-entry:not(:last-child)::before { content: ''; position: absolute; left: 7px; top: 13px; bottom: 0; width: 1px; background: var(--ff-border); }
.changelog-entry-marker { position: relative; z-index: 1; width: 15px; height: 15px; margin-top: 2px; border: 3px solid var(--ff-surface); border-radius: 50%; background: var(--ff-primary); box-shadow: 0 0 0 1px var(--ff-primary); }
.changelog-entry-meta { display: flex; align-items: center; gap: 9px; margin-bottom: 7px; }
.changelog-entry-meta time { color: var(--ff-text-faint); font: 11px/1 var(--ff-mono); }
.changelog-entry-tag { padding: 3px 7px; border-radius: 999px; background: var(--ff-primary-soft); color: var(--ff-primary); font-size: 10px; }
.changelog-entry-tag.is-account { background: color-mix(in srgb, #7c5cff 12%, transparent); color: #6a4de0; }
.changelog-entry-tag.is-match { background: color-mix(in srgb, #0f6b4d 12%, transparent); color: var(--ff-primary); }
.changelog-entry-tag.is-prediction { background: color-mix(in srgb, #c47b18 14%, transparent); color: #9a5c0c; }
.changelog-entry h3 { margin: 0 0 6px; color: var(--ff-text-strong); font-size: 14px; line-height: 1.45; }
.changelog-entry p { margin: 0; color: var(--ff-text-muted); font-size: 12px; line-height: 1.65; }
.changelog-entry-details { display: flex; flex-direction: column; gap: 5px; margin: 10px 0 0; padding: 0; list-style: none; }
.changelog-entry-details li { position: relative; padding-left: 13px; color: var(--ff-text-faint); font-size: 11px; line-height: 1.5; }
.changelog-entry-details li::before { content: ''; position: absolute; left: 0; top: .58em; width: 4px; height: 4px; border-radius: 50%; background: var(--ff-border-strong); }

:deep(.el-drawer__header) { margin-bottom: 4px; padding: 20px 20px 12px; border-bottom: 1px solid var(--ff-border); }
:deep(.el-drawer__body) { padding: 14px 20px 0; }
@media (prefers-reduced-motion: reduce) { .changelog-trigger, .changelog-pulse { animation: none; transition: none; } }
</style>
