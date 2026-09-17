<template>
  <div class="legal-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="使用条款" :brand-icon="Football" active-path="/terms" />
    <main id="app-main" class="legal-main" tabindex="-1">
      <p class="legal-lead">使用 ChenFootball（chenfootball.asia）即表示你理解并同意以下条款。若不同意，请停止使用本站点服务。</p>
      <nav class="legal-toc" aria-label="条款目录">
        <a href="#terms-service">服务范围</a>
        <a href="#terms-account">账号与安全</a>
        <a href="#terms-content">内容与预测</a>
        <a href="#terms-acceptable">合理使用</a>
        <a href="#terms-liability">责任限制</a>
        <a href="#terms-changes">变更</a>
      </nav>
      <h2 id="terms-service">服务范围</h2>
      <p>本站点提供足球赛程浏览、赛事资料、预测快照、收藏提醒与 Agent 辅助查询等功能。部分能力需要登录；服务可用性受数据源、模型服务与运维窗口影响。</p>
      <h2 id="terms-account">账号与安全</h2>
      <p>你应妥善保管账号与验证码。禁止冒用他人身份、绕过访问控制或滥用接口。注销与数据请求规则见隐私说明。</p>
      <h2 id="terms-content">内容与预测</h2>
      <p>赛程、积分、队徽与预测结果可能来自第三方数据源或本地模型。预测概率与结论仅供参考，不构成投注建议。你不得将本站数据用于侵犯版权、批量爬取转售或其他违法用途。</p>
      <h2 id="terms-acceptable">合理使用</h2>
      <p>请勿对站点进行攻击、恶意高频请求、干扰其他用户，或利用 Agent/接口进行自动化刷量。我们可能在必要时限流、暂时停用功能或拒绝服务。</p>
      <h2 id="terms-liability">责任限制</h2>
      <p>在适用法律允许的范围内，因数据延迟、模型误差、服务中断或第三方故障导致的损失，ChenFootball 不承担超出直接服务本身的赔偿责任。请以官方赛果与你的独立判断为准。</p>
      <h2 id="terms-changes">变更</h2>
      <p>我们可能更新本条款；重大变更会尽量在站点显著位置提示。继续使用即视为接受更新后的条款。隐私处理另见隐私说明。</p>
      <div class="legal-actions">
        <el-button type="primary" plain @click="$router.push('/matches')">返回比赛</el-button>
        <el-button plain @click="$router.push('/about')">关于我们</el-button>
        <el-button plain @click="$router.push('/privacy')">隐私说明</el-button>
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
.legal-main { max-width: 820px; margin: 0 auto; padding: 48px 24px; color: var(--ff-text); line-height: 1.85; }
.legal-lead { color: var(--ff-text-muted); margin-bottom: 8px; }
.legal-main h2 { margin: 26px 0 8px; color: var(--ff-primary); font-size: 18px; scroll-margin-top: 84px; }
.legal-main p { color: var(--ff-text-muted); }
.legal-toc { display:flex; flex-wrap:wrap; gap:6px; margin:22px 0 28px; padding:10px; border:1px solid var(--ff-border); border-radius:var(--ff-radius-md); background:var(--ff-surface-soft); }
.legal-toc a { padding:6px 9px; border-radius:999px; color:var(--ff-primary); background:var(--ff-primary-soft); font-size:11px; }
.legal-toc a:hover { text-decoration: underline; text-underline-offset: 2px; }
.legal-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:28px; }
@media (max-width: 680px) { .legal-main { padding:28px 16px 40px; } }
</style>
