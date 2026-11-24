/**
 * 占位页通用骨架（脚手架阶段）。
 * 样板页组迁移真实页面时，用真实内容替换本组件的调用处；
 * 页面文件本身保留（路由 lazy 指向不变）。
 */

import PageSection from '../../components/layout/PageSection'
import PageState from '../../components/layout/PageState'
import { t } from '../../i18n'

export default function PlaceholderPage(props) {
  return (
    <div style="display:grid;gap:16px">
      <PageSection title={props.title} subtitle={props.subtitle} variant={props.variant || 'default'}>
        <PageState type="empty" title={t('common.scaffoldPlaceholder')} description={t('common.scaffoldPlaceholderDesc')} actionText={t('common.browseMatches')} onAction={() => {}} />
        {props.extra}
      </PageSection>
      <div class="ff-placeholder-grid">
        <div class="ff-placeholder-row">
          <span class="ff-live-dot" aria-hidden="true" />
          <span>直播圆点脉冲（动效档 300ms 内，减弱动效时静止）</span>
          <span class="ff-placeholder-meta">· --ff-live</span>
        </div>
        <div class="ff-skeleton" style="height:64px" aria-hidden="true" />
        <div class="ff-skeleton" style="height:64px" aria-hidden="true" />
      </div>
      <p class="ff-placeholder-meta">
        占位内容 · <a href="/matches">返回比赛</a>
      </p>
    </div>
  )
}
