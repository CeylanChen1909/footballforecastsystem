#!/usr/bin/env node
/**
 * Post-build static HTML meta shells for crawlers / share bots.
 * Does NOT execute the Vue app — only duplicates dist/index.html with
 * route-specific title/description/canonical/og/twitter tags.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

function sanitizeManifestLinks(html) {
  let out = String(html).replace(/\s*<link[^>]+rel=["']manifest["'][^>]*>\s*/gi, "\n    ");
  const link = '<link rel="manifest" href="/site.webmanifest" />';
  if (/rel=["']apple-touch-icon["']/i.test(out)) {
    out = out.replace(/(<link[^>]+rel=["']apple-touch-icon["'][^>]*>)/i, `$1\n    ${link}`);
  } else {
    out = out.replace(/<head>/i, `<head>\n    ${link}`);
  }
  return out;
}

const distDir = join(__dirname, "..", "dist");
const indexPath = join(distDir, "index.html");

const SITE_ORIGIN = "https://chenfootball.asia";
const DEFAULT_TITLE = "ChenFootball - 足球赛程与预测";
const DEFAULT_DESCRIPTION =
  "ChenFootball 提供足球赛程、赛事资料与智能预测，帮助你快速了解比赛信息与分析结果。";

const ROUTES = [
  {
    path: "/",
    title: DEFAULT_TITLE,
    description: DEFAULT_DESCRIPTION,
  },
  {
    path: "/matches",
    title: "比赛赛程 - ChenFootball",
    description: "浏览今日与近期足球赛程，查看联赛筛选、收藏与开赛提醒。",
  },
  {
    path: "/competitions",
    title: "赛事资料 - ChenFootball",
    description: "查看联赛积分榜、参赛俱乐部与球队资料。",
  },
  {
    path: "/privacy",
    title: "隐私政策 - ChenFootball",
    description: "了解 ChenFootball 如何保存、使用与删除账号及赛程相关数据。",
  },
  {
    path: "/about",
    title: "关于我们 - ChenFootball",
    description: "了解 ChenFootball 的产品定位、数据来源、模型边界与免责声明。",
  },
  {
    path: "/terms",
    title: "使用条款 - ChenFootball",
    description: "查阅 ChenFootball 使用条款、账号规则、合理使用与责任限制。",
  },
  {
    path: "/agent",
    title: "Agent - ChenFootball",
    description: "用自然语言查询赛程、球队状态与预测依据。",
  },
];

function replaceMeta(html, { path, title, description }) {
  const canonical = path === "/" ? `${SITE_ORIGIN}/` : `${SITE_ORIGIN}${path}`;
  const ogUrl = path === "/" ? SITE_ORIGIN : `${SITE_ORIGIN}${path}`;

  let out = html;
  out = out.replace(/<title>[^<]*<\/title>/i, `<title>${title}</title>`);

  const swapName = (name, content) => {
    const re = new RegExp(
      `<meta\\s+name="${name}"\\s+content="[^"]*"\\s*/?>`,
      "i"
    );
    if (re.test(out)) {
      out = out.replace(re, `<meta name="${name}" content="${content}" />`);
    } else {
      out = out.replace(
        /<\/head>/i,
        `    <meta name="${name}" content="${content}" />\n  </head>`
      );
    }
  };

  const swapProp = (prop, content) => {
    const re = new RegExp(
      `<meta\\s+property="${prop}"\\s+content="[^"]*"\\s*/?>`,
      "i"
    );
    if (re.test(out)) {
      out = out.replace(re, `<meta property="${prop}" content="${content}" />`);
    } else {
      out = out.replace(
        /<\/head>/i,
        `    <meta property="${prop}" content="${content}" />\n  </head>`
      );
    }
  };

  swapName("description", description);
  swapName("twitter:title", title);
  swapName("twitter:description", description);
  swapProp("og:title", title);
  swapProp("og:description", description);
  swapProp("og:url", ogUrl);

  const canonRe = /<link\s+rel="canonical"\s+href="[^"]*"\s*\/?>/i;
  if (canonRe.test(out)) {
    out = out.replace(canonRe, `<link rel="canonical" href="${canonical}" />`);
  } else {
    out = out.replace(
      /<\/head>/i,
      `    <link rel="canonical" href="${canonical}" />\n  </head>`
    );
  }

  // Lightweight crawler-visible body hint (SPA still mounts over #app).
  const noscript = `<noscript><p>${title} — ${description}</p></noscript>`;
  if (!out.includes("<noscript>")) {
    out = out.replace(
      /<div id="app"><\/div>/i,
      `<div id="app"></div>\n    ${noscript}`
    );
  } else {
    out = out.replace(/<noscript>[\s\S]*?<\/noscript>/i, noscript);
  }

  return out;
}

function main() {
  if (!existsSync(indexPath)) {
    console.error(`[prerender-meta] missing ${indexPath}; run vite build first`);
    process.exit(1);
  }

  const base = readFileSync(indexPath, "utf8");

  for (const route of ROUTES) {
    const html = replaceMeta(base, route);
    if (route.path === "/") {
      writeFileSync(indexPath, sanitizeManifestLinks(html), "utf8");
      console.log(`[prerender-meta] wrote ${indexPath}`);
      continue;
    }
    const outDir = join(distDir, route.path.replace(/^\//, ""));
    mkdirSync(outDir, { recursive: true });
    const outPath = join(outDir, "index.html");
    writeFileSync(outPath, sanitizeManifestLinks(html), "utf8");
    console.log(`[prerender-meta] wrote ${outPath}`);
  }
}

main();
