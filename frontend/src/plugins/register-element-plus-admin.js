/**
 * Admin-only Element Plus widgets (commercial polish r6).
 * Imported from AdminDashboard so user-shell entry does not pull date-picker /
 * descriptions / pagination / timeline / aside / switch / divider into matches LCP.
 */
import {
  ElAside, ElDatePicker, ElDescriptions, ElDescriptionsItem, ElDivider,
  ElInputNumber, ElPagination, ElSwitch, ElTimePicker, ElTimeline, ElTimelineItem
} from 'element-plus'
import '../styles/element-plus-admin-on-demand.js'

const adminComponents = [
  ElAside, ElDatePicker, ElDescriptions, ElDescriptionsItem, ElDivider,
  ElInputNumber, ElPagination, ElSwitch, ElTimePicker, ElTimeline, ElTimelineItem
]

let registered = false

export function registerElementPlusAdmin(app) {
  if (registered || !app) return
  registered = true
  for (const component of adminComponents) {
    if (!app.component(component.name)) app.component(component.name, component)
  }
}

/** @deprecated alias kept for any interim call sites */
export function ensureAdminElementPlus(app) {
  registerElementPlusAdmin(app)
}
