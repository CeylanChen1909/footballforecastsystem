/**
 * Privacy 页（自 Vue src/views/user/Privacy.vue 44 行移植）。
 *
 * 纯静态页：文案一字不动。基础视觉：新增大标题（原版无 h1），目录与各条款
 * 包进卡片分区（.legal-section-card，legal.css 附录），其余样式沿用 r11。
 * “重新设置统计偏好”调用 clearAnalyticsConsent（与原版一致，无 toast）。
 * 顶栏/页脚由 App.jsx 全局挂载，本页只写页面内容。
 */
import { clearAnalyticsConsent } from '../../utils/privacyConsent'
import '../../styles/legal.css'

export default function Privacy() {
  const resetConsent = () => clearAnalyticsConsent()
  return (
    <div class="privacy-page">
      <main id="app-main" class="privacy-main" tabindex="-1">
        <h1 class="legal-title">隐私说明</h1>
        <p>ChenFootball 仅保存提供收藏、预测历史、提醒和账号安全所必需的数据。赛事资料以积分榜、俱乐部档案和可核验的球队数据为主，不抓取或再分发受版权保护的正文与视频。</p>
        <nav class="privacy-toc" aria-label="隐私说明目录">
          <a href="#privacy-data">保存的数据</a>
          <a href="#privacy-retention">保留与删除</a>
          <a href="#privacy-cookie">统计与 Cookie</a>
          <a href="#privacy-third-party">第三方服务</a>
          <a href="#privacy-rights">你的权利</a>
        </nav>
        <section class="legal-section-card">
          <h2 id="privacy-data">我们保存什么</h2>
          <p>账号标识、收藏、预测记录、提醒偏好和必要的安全审计日志。行为埋点只用于改进产品，不用于出售个人信息；助手对话正文不会用于匿名统计。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="privacy-retention">保留与删除</h2>
          <p>你可以在“个人中心 · 账户安全”中修改密码或注销账号。注销后账号立即停用，业务数据停止对外展示；安全日志最多保留 180 天，收藏和预测记录在注销后 30 天内清理。需要导出或更正数据时，可通过站点反馈或管理员渠道联系；当前未配置公开客服邮箱。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="privacy-cookie">统计与 Cookie</h2>
          <p>匿名统计默认关闭。你可以在页面底部选择“仅必要功能”或“允许匿名统计”，选择会保存在当前浏览器中；不影响浏览公开赛程和预测页面。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="privacy-third-party">第三方服务</h2>
          <p>赛事数据、模型和队徽/图片可能来自主爬虫源、模型服务和图片托管服务。我们不会把密码、验证码或完整 助手对话发送给统计服务；服务状态和覆盖范围会在页面中明确提示。</p>
        </section>
        <section class="legal-section-card">
          <h2 id="privacy-rights">你的权利与联系</h2>
          <p>你可以访问、修改、导出或删除账号资料，也可以撤回匿名统计同意。若发现数据错误、隐私问题或需要投诉，请通过站点反馈或管理员渠道联系（当前未公开独立客服邮箱）。我们会记录处理时间、处理结论和恢复方式。也可登录后在个人中心提交账户相关请求。</p>
        </section>
        <div class="privacy-actions">
          <button type="button" class="ff-btn ff-btn-plain" onClick={() => window.history.back()}>
            返回
          </button>
          <button type="button" class="ff-btn ff-btn-ghost" onClick={resetConsent}>
            重新设置统计偏好
          </button>
        </div>
      </main>
    </div>
  )
}
