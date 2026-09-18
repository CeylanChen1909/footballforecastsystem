/**
 * ElDialog — registered on demand (commercial polish r8).
 * Kept out of the user-shell entry so matches LCP does not sync-import element-dialog.
 * Call before opening AppTopNav search/notification dialogs or AuthDialog.
 */
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/overlay/style/css'

let registered = false

export function registerElementPlusDialog(app) {
  if (registered || !app) return
  registered = true
  if (!app.component(ElDialog.name)) app.component(ElDialog.name, ElDialog)
}
