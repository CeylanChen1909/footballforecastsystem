/**
 * ElMenu / ElMenuItem — registered from AppTopNav / admin (r7).
 * Kept out of the user-shell entry so matches LCP does not sync-import element-menu
 * from main.js (AppTopNav still needs it on first matches paint via its own import).
 */
import { ElMenu, ElMenuItem } from 'element-plus'
import 'element-plus/es/components/menu/style/css'
import 'element-plus/es/components/menu-item/style/css'

let registered = false

export function registerElementPlusMenu(app) {
  if (registered || !app) return
  registered = true
  for (const component of [ElMenu, ElMenuItem]) {
    if (!app.component(component.name)) app.component(component.name, component)
  }
}
