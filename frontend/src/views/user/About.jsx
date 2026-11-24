/**
 * About 页（自 Vue src/views/user/About.vue 205 行移植）。
 *
 * 纯静态页：文案一字不动。基础视觉沿用 r11 样式（hero 大标题、产品简介双栏、
 * 信任要点、免责声明、联系区、底部操作），免责/联系两区在 Solid 侧包进
 * .legal-section-card 卡片分区（legal.css 附录）。
 * 顶栏/页脚由 App.jsx 全局挂载，本页只写页面内容。
 */
import '../../styles/legal.css'

export default function About() {
  return (
    <div class="legal-page">
      <main id="app-main" class="legal-main" tabindex="-1">
        <header class="about-hero">
          <p class="eyebrow">关于 ChenFootball</p>
          <p class="one-liner">按日看足球赛程，点进去看预测概率、数据覆盖与质量门槛——只做参考，不做投注建议。</p>
          <a class="hero-cta" href="/matches">
            去看赛程
          </a>
        </header>

        <section class="product-brief" aria-label="产品简介">
          <div class="brief-cols">
            <div class="brief-col">
              <h3>做什么</h3>
              <ul>
                <li>
                  按日列出已同步赛程（主入口：<a href="/matches">比赛赛程</a>）
                </li>
                <li>点开比赛查看预测概率、数据覆盖与质量门槛</li>
                <li>赛后可对照赛前预测快照</li>
                <li>登录后可收藏比赛、设置开赛提醒</li>
              </ul>
            </div>
            <div class="brief-col is-boundary">
              <h3>不做什么</h3>
              <ul>
                <li>
                  <strong>不提供投注建议</strong>
                </li>
                <li>不保证预测命中</li>
                <li>不鼓励、不支持任何违法博彩用途</li>
              </ul>
            </div>
          </div>
        </section>

        <dl class="trust-facts trust-grid" aria-label="信任要点">
          <div>
            <dt>公开赛程</dt>
            <dd>比赛列表与联赛积分来自主爬虫同步快照，页面会标注数据状态。</dd>
          </div>
          <div>
            <dt>预测边界</dt>
            <dd>概率与结论仅供参考；样本不足或未过质量门槛时会明确提示。</dd>
          </div>
          <div>
            <dt>隐私可控</dt>
            <dd>匿名统计可撤回；账号资料可在个人中心管理，详见隐私说明。</dd>
          </div>
        </dl>

        <section class="about-disclaimer legal-section-card" aria-label="免责声明">
          <h2>免责声明</h2>
          <p>所有预测、概率与助手解读仅供信息参考，不构成投注、投资或其他决策建议。比赛结果受多种不确定因素影响，请以官方赛果为准。</p>
        </section>

        <section class="about-contact legal-section-card" aria-label="联系与反馈">
          <h2>联系与反馈</h2>
          <p>
            当前未公开独立客服渠道（与隐私说明一致）。账号与隐私请求可在 <a href="/profile">个人中心</a> 处理；产品反馈可使用站内 <a href="/agent">助手</a>。
            法律文档见 <a href="/terms">使用条款</a> 与 <a href="/privacy">隐私说明</a>。
          </p>
        </section>

        <div class="legal-actions">
          <a class="action-primary" href="/matches">
            去看赛程
          </a>
          <a class="action-ghost" href="/privacy">
            隐私说明
          </a>
          <a class="action-ghost" href="/terms">
            使用条款
          </a>
        </div>
      </main>
    </div>
  )
}
