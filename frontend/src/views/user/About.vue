<template>
  <div class="legal-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="" :brand-icon="Football" active-path="/about" />
    <main id="app-main" class="legal-main" tabindex="-1">
      <header class="about-hero">
        <p class="eyebrow">关于 ChenFootball</p>
        <p class="one-liner">按日看足球赛程，点进去看预测概率、数据覆盖与质量门槛——只做参考，不做投注建议。</p>
        <router-link class="hero-cta" to="/matches">去看赛程</router-link>
      </header>

      <section class="product-brief" aria-label="产品简介">
        <div class="brief-cols">
          <div class="brief-col">
            <h3>做什么</h3>
            <ul>
              <li>按日列出已同步赛程（主入口：<router-link to="/matches">比赛赛程</router-link>）</li>
              <li>点开比赛查看预测概率、数据覆盖与质量门槛</li>
              <li>赛后可对照赛前预测快照</li>
              <li>登录后可收藏比赛、设置开赛提醒</li>
            </ul>
          </div>
          <div class="brief-col is-boundary">
            <h3>不做什么</h3>
            <ul>
              <li><strong>不提供投注建议</strong></li>
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

      <section class="about-disclaimer" aria-label="免责声明">
        <h2>免责声明</h2>
        <p>所有预测、概率与助手解读仅供信息参考，不构成投注、投资或其他决策建议。比赛结果受多种不确定因素影响，请以官方赛果为准。</p>
      </section>

      <section class="about-contact" aria-label="联系与反馈">
        <h2>联系与反馈</h2>
        <p>当前未公开独立客服渠道（与隐私说明一致）。账号与隐私请求可在 <router-link to="/profile">个人中心</router-link> 处理；产品反馈可使用站内 <router-link to="/agent">助手</router-link>。法律文档见 <router-link to="/terms">使用条款</router-link> 与 <router-link to="/privacy">隐私说明</router-link>。</p>
      </section>

      <div class="legal-actions">
        <router-link class="action-primary" to="/matches">去看赛程</router-link>
        <router-link class="action-ghost" to="/privacy">隐私说明</router-link>
        <router-link class="action-ghost" to="/terms">使用条款</router-link>
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { Football } from '@element-plus/icons-vue'
</script>

<style scoped>
.legal-main {
  max-width: 760px;
  margin: 0 auto;
  padding: 36px 24px 56px;
  color: var(--ff-text);
  line-height: 1.75;
}
.about-hero { margin: 0 0 22px; }
.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--ff-primary);
}
.one-liner {
  margin: 0 0 16px;
  color: var(--ff-text-strong);
  font-size: 20px;
  font-weight: 650;
  line-height: 1.45;
  max-width: 36em;
}
.hero-cta {
  display: inline-flex;
  align-items: center;
  min-height: 40px;
  padding: 0 16px;
  border-radius: 6px;
  background: var(--ff-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 650;
  text-decoration: none;
}
.hero-cta:hover { background: var(--ff-primary-hover); }
.hero-cta:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 2px; }

.product-brief {
  margin: 0 0 8px;
  padding: 18px 0 4px;
  border-top: 1px solid var(--ff-border);
}
.brief-cols {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 28px;
}
.brief-col h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--ff-text-strong);
}
.brief-col.is-boundary h3 { color: var(--ff-text); }
.brief-cols ul {
  margin: 0;
  padding-left: 1.05em;
  color: var(--ff-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.brief-cols li { margin: 5px 0; }
.brief-cols a {
  color: var(--ff-primary);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.legal-main h2 {
  margin: 28px 0 8px;
  color: var(--ff-text-strong);
  font-size: 16px;
  scroll-margin-top: 84px;
}
.legal-main p { color: var(--ff-text-muted); margin: 0; }
.about-disclaimer p,
.about-contact p { font-size: 13px; line-height: 1.65; }
.about-contact a {
  color: var(--ff-primary);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.trust-facts { margin: 18px 0 4px; border-top: 1px solid var(--ff-border); }
.trust-facts > div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid var(--ff-border);
}
.trust-facts dt { margin: 0; color: var(--ff-text-strong); font-size: 13px; font-weight: 700; }
.trust-facts dd { margin: 0; color: var(--ff-text-muted); font-size: 13px; line-height: 1.55; }

.legal-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 28px;
}
.action-primary,
.action-ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  padding: 0 14px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}
.action-primary { background: var(--ff-primary); color: #fff; }
.action-primary:hover { background: var(--ff-primary-hover); }
.action-ghost {
  border: 1px solid var(--ff-border);
  color: var(--ff-text);
  background: #fff;
}
.action-ghost:hover { border-color: var(--ff-primary); color: var(--ff-primary); }

@media (max-width: 680px) {
  .legal-main { padding: 24px 16px 40px; }
  .one-liner { font-size: 18px; }
  .brief-cols { grid-template-columns: 1fr; gap: 18px; }
  .trust-facts > div { grid-template-columns: 1fr; gap: 2px; }
}
</style>
