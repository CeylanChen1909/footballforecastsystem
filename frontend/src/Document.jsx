/**
 * 文档壳（替代旧版 index.html）。
 *
 * Solid 2.0 start mode 下没有 index.html：插件以本文件为静态文档壳，
 * 构建时输出 dist/client/index.html，dev 时以流式文档壳挂载应用。
 * 本文件只产出静态壳、不带应用 JS；head 内容沿用旧版 index.html
 * （标题、meta、OG/Twitter、manifest、canonical），以下两处按 2026 设计定稿修正：
 *  - 首屏壳颜色改用设计 tokens 的墨色/页面底色，旧版 navy #0f172a 不再出现；
 *  - <head> 内联主题启动脚本（防闪白）：在任何 CSS/JS 之前按
 *    localStorage `football_theme` 或系统 prefers-color-scheme 定 data-theme，
 *    并同步 theme-color；与 src/stores/theme.js 用同一 key 与同一色值。
 */

// 与 src/stores/theme.js 保持一致：暗色 #0f1512（tokens 文件注明值），亮色为页面底色。
const THEME_BOOT_SCRIPT = `(function(){try{var s=localStorage.getItem('football_theme');var d=(s==='light'||s==='dark')?s:(window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');document.documentElement.setAttribute('data-theme',d);var m=document.querySelector('meta[name="theme-color"]');if(m)m.setAttribute('content',d==='dark'?'#0f1512':'#f7f8f7');}catch(e){}})();`

const CRITICAL_CSS = `html,body{min-height:100%;margin:0}body{background:#f7f8f7;color:#344139;font-family:'Segoe UI','PingFang SC','Microsoft YaHei','Helvetica Neue',Helvetica,Arial,sans-serif;-webkit-font-smoothing:antialiased}[data-theme="dark"] body{background:#0f1512;color:#e6ebe7}.ff-crit-nav{height:64px;background:#fff;border-bottom:1px solid #dfe5e1}[data-theme="dark"] .ff-crit-nav{background:#182420;border-bottom-color:#2a3a32}.ff-crit-skel{display:grid;gap:10px;padding:16px;max-width:1100px;margin:0 auto}.ff-crit-skel i{display:block;height:88px;border-radius:12px;background:linear-gradient(90deg,#e8ece9 25%,#f3f5f3 37%,#e8ece9 63%);background-size:400% 100%;animation:ffCritSk 1.2s ease-in-out infinite}@keyframes ffCritSk{0%{background-position:100% 0}100%{background-position:-100% 0}}@media (prefers-reduced-motion:reduce){.ff-crit-skel i{animation:none}}`

export default function Document(props) {
  return (
    <html lang="zh-CN">
      <head>
        <meta charset="UTF-8" />
        <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
        <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />
        <link rel="manifest" href="/site.webmanifest" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
        <title>ChenFootball - 足球赛程与预测</title>
        <meta
          name="keywords"
          content="足球赛程, 比赛预测, 预测概率, 数据覆盖, football fixtures, match predictions, soccer schedule"
        />
        <meta
          name="description"
          content="ChenFootball 按日列出已同步赛程，点开看预测概率、数据覆盖与质量门槛。不提供投注建议。"
        />
        <meta name="theme-color" content="#f7f8f7" />
        <link rel="dns-prefetch" href="//chenfootball.asia" />
        <meta name="application-name" content="ChenFootball" />
        <meta name="apple-mobile-web-app-capable" content="yes" />
        <meta name="apple-mobile-web-app-status-bar-style" content="default" />
        <meta name="apple-mobile-web-app-title" content="ChenFootball" />
        <link rel="canonical" href="https://chenfootball.asia/" />
        <meta property="og:title" content="ChenFootball - 足球赛程与预测" />
        <meta
          property="og:description"
          content="ChenFootball 按日列出已同步赛程，点开看预测概率、数据覆盖与质量门槛。不提供投注建议。"
        />
        <meta property="og:url" content="https://chenfootball.asia" />
        <meta property="og:type" content="website" />
        <meta property="og:locale" content="zh_CN" />
        <meta property="og:site_name" content="ChenFootball" />
        <meta property="og:image" content="https://chenfootball.asia/og.png" />
        <meta property="og:image:width" content="1200" />
        <meta property="og:image:height" content="630" />
        <meta property="og:image:alt" content="ChenFootball 足球赛程与预测" />
        <meta name="twitter:card" content="summary_large_image" />
        <meta name="twitter:title" content="ChenFootball - 足球赛程与预测" />
        <meta
          name="twitter:description"
          content="ChenFootball 按日列出已同步赛程，点开看预测概率、数据覆盖与质量门槛。不提供投注建议。"
        />
        <meta name="twitter:image" content="https://chenfootball.asia/og.png" />
        <meta name="twitter:image:alt" content="ChenFootball 足球赛程与预测" />
        <script innerHTML={THEME_BOOT_SCRIPT} />
        <style innerHTML={CRITICAL_CSS} />
        {/* favicon 不 preload：浏览器经 icon 链接自行取，preload 会报“预加载后未使用”。 */}
      </head>
      <body>
        {props.children}
        <noscript>
          <p>ChenFootball - 足球赛程与预测 — 请启用 JavaScript 以浏览赛程与预测。</p>
        </noscript>
      </body>
    </html>
  )
}
