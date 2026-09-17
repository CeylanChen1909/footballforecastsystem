# Element Plus CSS / JS strategy (Round 6)

## Context
This frontend registers Element Plus components manually (no `unplugin-vue-components`).

## Round 4–5
Replaced full `element-plus/dist/index.css` with on-demand `theme-chalk` imports.

## Round 6
Split registration and CSS by shell:

- **User shell** (`src/plugins/register-element-plus-user.js` + `src/styles/element-plus-on-demand.js`) — matches/home LCP path.
- **Admin shell** (`src/plugins/register-element-plus-admin.js` + `src/styles/element-plus-admin-on-demand.js`) — loaded from `AdminDashboard.vue` only.

Admin-only widgets moved out of the entry: Aside, DatePicker, Descriptions, Divider, InputNumber, Pagination, Switch, TimePicker, Timeline.

`vite.config.js` `manualChunks` keeps Vite preload helpers out of `admin-app` so the entry does not statically import admin CSS/JS. `scripts/lcp-html-order.mjs` strips `admin-app` / `agent-app` / `prediction-app` stylesheets from `dist/index.html`.

## Verify
`npm run build && npm run test:smoke && npm run test:perf`

## Rollback
Restore full registration in `main.js` and merge admin CSS back into `element-plus-on-demand.js` if an admin widget style is missing.

## Round 7
Table / Menu / Drawer left the user-shell entry:

- `register-element-plus-menu.js` — `AppTopNav.vue` (+ admin register)
- `register-element-plus-table.js` — `CompetitionHub.vue` (+ admin register)
- `register-element-plus-drawer.js` — `ChangelogButton.vue` (async on matches) (+ admin register)

Agent shell: `agent-app` chunk is only `Agent.vue`. `AgentLauncher` lives under `components/layout` and loads after idle; stores/api/utils go to `app-shared` so the entry never statically imports `agent-app`.

