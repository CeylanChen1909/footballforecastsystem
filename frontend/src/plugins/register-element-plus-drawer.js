/**
 * ElDrawer — ChangelogButton (async on matches) + admin (r7).
 */
import { ElDrawer } from 'element-plus'
import 'element-plus/es/components/drawer/style/css'

let registered = false

export function registerElementPlusDrawer(app) {
  if (registered || !app) return
  registered = true
  if (!app.component(ElDrawer.name)) app.component(ElDrawer.name, ElDrawer)
}
