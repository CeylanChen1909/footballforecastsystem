# Element Plus CSS strategy (Round 4)

## Decision
The project **does not** use `unplugin-vue-components` / auto-import. Components are
registered manually in `src/main.js`.

## Change
Replaced `import 'element-plus/dist/index.css'` (~350KB full theme) with
`src/styles/element-plus-on-demand.js`, which imports base + per-component CSS
for every registered widget plus Message / MessageBox / Loading / Overlay /
Popper / Scrollbar.

## Why not unplugin
Adding unplugin while keeping manual `app.component(...)` registration risks
double registration and a larger dependency surface for a polish round. On-demand
style imports match the existing manual-register model without changing runtime
component resolution.

## Rollback
If a missing widget style appears, either:
1. Add `import 'element-plus/es/components/<name>/style/css'` to the on-demand file, or
2. Temporarily restore `import 'element-plus/dist/index.css'` in `main.js`.
