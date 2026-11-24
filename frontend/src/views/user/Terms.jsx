/**
 * Terms 页（自 Vue src/views/user/Terms.vue 52 行移植）。
 *
 * 纯静态页：文案一字不动。基础视觉：新增大标题（原版无 h1），目录与各条款
 * 包进卡片分区（.legal-section-card，legal.css 附录），其余样式沿用 r11。
 * 顶栏/页脚由 App.jsx 全局挂载，本页只写页面内容。
 */
import { useNavigate } from '@solidjs/router'
import '../../styles/legal.css'

export default function Terms() {
  const navigate = useNavigate()
  return (
    <div class="legal-page">
      <main id="app-main" class="legal-main" tabindex="-1">
        <h1 class="legal-title">使用条款</h1>
        <p class="legal-lead">使用 ChenFootball（chenfootball.asia）即表示你理解并同意以下条款。若不同意，请停止使用本站点服务。</p>
        <nav class="legal-toc" aria-label="条款目录">
          <a href="#terms-service">服务范围</a>
          <a href="#terms-account">账号与安全</a>
          <a href="#terms-content">内容与预测</a>
          <a href="#terms-acceptable">合理使用</a>
          <a href="#terms-liability">责任限制</a>
          <a href="#terms-changes">变更</a>
        </nav>
        <section class="legal-section-card">
          <h2 id="terms-service">服务范围</h2>
          <p>本站点提供足球赛程浏览、赛事资料、预测快照、收藏提醒与助手辅助查询等功能。部分能力需要登录；服务可用性受数据源、模型服务与运维窗口影响。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="terms-account">账号与安全</h2>
          <p>你应妥善保管账号与验证码。禁止冒用他人身份、绕过访问控制或滥用接口。注销与数据请求规则见隐私说明。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="terms-content">内容与预测</h2>
          <p>赛程、积分、队徽与预测结果可能来自第三方数据源或本地模型。预测概率与结论仅供参考，不构成投注建议。你不得将本站数据用于侵犯版权、批量爬取转售或其他违法用途。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="terms-acceptable">合理使用</h2>
          <p>请勿对站点进行攻击、恶意高频请求、干扰其他用户，或利用助手/接口进行自动化刷量。我们可能在必要时限流、暂时停用功能或拒绝服务。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="terms-liability">责任限制</h2>
          <p>在适用法律允许的范围内，因数据延迟、模型误差、服务中断或第三方故障导致的损失，ChenFootball 不承担超出直接服务本身的赔偿责任。请以官方赛果与你的独立判断为准。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="terms-changes">变更</h2>
          <p>我们可能更新本条款；重大变更会尽量在站点显著位置提示。继续使用即视为接受更新后的条款。隐私处理另见隐私说明。</p>
        </section>
        <div class="legal-actions">
          <button type="button" class="ff-btn ff-btn-plain" onClick={() => navigate('/matches')}>
            返回比赛
          </button>
          <button type="button" class="ff-btn ff-btn-ghost" onClick={() => navigate('/about')}>
            关于我们
          </button>
          <button type="button" class="ff-btn ff-btn-ghost" onClick={() => navigate('/privacy')}>
            隐私说明
          </button>
        </div>
      </main>
    </div>
  )
}
