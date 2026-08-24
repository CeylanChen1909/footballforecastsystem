<template>
  <div class="privacy-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="隐私与数据说明" :brand-icon="Football" active-path="/privacy" />
    <main id="app-main" class="privacy-main" tabindex="-1">
      <p>ChenFootball 仅保存提供收藏、预测历史、提醒和账号安全所必需的数据。赛事资料以积分榜、俱乐部档案和可核验的球队数据为主，不抓取或再分发受版权保护的正文与视频。</p>
      <nav class="privacy-toc" aria-label="隐私说明目录"><a href="#privacy-data">保存的数据</a><a href="#privacy-retention">保留与删除</a><a href="#privacy-cookie">统计与 Cookie</a><a href="#privacy-third-party">第三方服务</a><a href="#privacy-rights">你的权利</a></nav>
      <h2 id="privacy-data">我们保存什么</h2>
      <p>账号标识、收藏、预测记录、提醒偏好和必要的安全审计日志。行为埋点只用于改进产品，不用于出售个人信息；Agent 对话正文不会用于匿名统计。</p>
      <h2 id="privacy-retention">保留与删除</h2>
      <p>你可以在“个人中心 · 账户安全”中修改密码或注销账号。注销后账号立即停用，业务数据停止对外展示；安全日志最多保留 180 天，收藏和预测记录在注销后 30 天内清理。需要导出或更正数据时，可通过页面反馈联系我们。</p>
      <h2 id="privacy-cookie">统计与 Cookie</h2>
      <p>匿名统计默认关闭。你可以在页面底部选择“仅必要功能”或“允许匿名统计”，选择会保存在当前浏览器中；不影响浏览公开赛程和预测页面。</p>
      <h2 id="privacy-third-party">第三方服务</h2>
      <p>赛事数据、模型和队徽/图片可能来自主爬虫源、模型服务和图片托管服务。我们不会把密码、验证码或完整 Agent 对话发送给统计服务；服务状态和覆盖范围会在页面中明确提示。</p>
      <h2 id="privacy-rights">你的权利与联系</h2>
      <p>你可以访问、修改、导出或删除账号资料，也可以撤回匿名统计同意。若发现数据错误、隐私问题或需要投诉，请在项目部署者提供的支持渠道提交工单；我们会记录处理时间、处理结论和恢复方式。</p>
      <div class="privacy-actions"><el-button type="primary" plain @click="$router.back()">返回</el-button><el-button plain @click="resetConsent">重新设置统计偏好</el-button></div>
    </main>
  </div>
</template>

<script setup>
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { Football } from '@element-plus/icons-vue'
import { clearAnalyticsConsent } from '../../utils/privacyConsent'

const resetConsent = () => clearAnalyticsConsent()
</script>

<style scoped>
.privacy-main { max-width: 820px; margin: 0 auto; padding: 48px 24px; color: var(--ff-text); line-height: 1.85; }
.privacy-title-row { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; margin-bottom:24px; }.privacy-title-row h1 { margin:8px 0 0; }.privacy-title-row small { color:var(--ff-text-faint); font:11px var(--ff-mono); white-space:nowrap; }
.privacy-main h1 { margin-bottom: 24px; color: var(--ff-ink); }
.privacy-main h2 { margin: 26px 0 8px; color: var(--ff-primary); font-size: 18px; }
.privacy-main p { color: var(--ff-text-muted); }
.privacy-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:28px; }
.privacy-toc { display:flex; flex-wrap:wrap; gap:6px; margin:22px 0 28px; padding:10px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-soft); }
.privacy-toc a { padding:6px 9px; border-radius:999px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:11px; }
.privacy-toc a:hover { text-decoration: underline; text-underline-offset: 2px; }
.privacy-main h2 { scroll-margin-top: 84px; }
@media (max-width: 680px) { .privacy-main { padding:28px 16px 40px; } .privacy-title-row { align-items:flex-start; flex-direction:column; gap:8px; } }
</style>
