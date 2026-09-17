# Element Plus CSS strategy (Round 4)

## Context
This frontend registers Element Plus components manually in `src/main.js` and does
**not** use `unplugin-vue-components` / auto-import.

## Change
Replaced full `element-plus/dist/index.css` (~350KB) with
`src/styles/element-plus-on-demand.js` (`theme-chalk/base.css` + per-component CSS
for registered widgets and Message / MessageBox / Loading / Overlay / Popper /
Scrollbar / OptionGroup / CheckboxGroup / Radio).

## Why not unplugin in r4
Auto-import would conflict with manual `app.component(...)` registration.
On-demand style imports cut CSS weight without changing runtime resolution.

## Verify
`npm run test:smoke` asserts no `dist/index.css` and on-demand module present.

## Rollback
Restore `import 'element-plus/dist/index.css'` in `main.js` if a style is missing.
