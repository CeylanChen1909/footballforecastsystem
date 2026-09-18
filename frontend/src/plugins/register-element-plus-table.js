/**
 * ElTable / ElTableColumn — CompetitionHub + admin only (r7).
 */
import { ElTable, ElTableColumn } from 'element-plus'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'

let registered = false

export function registerElementPlusTable(app) {
  if (registered || !app) return
  registered = true
  for (const component of [ElTable, ElTableColumn]) {
    if (!app.component(component.name)) app.component(component.name, component)
  }
}
