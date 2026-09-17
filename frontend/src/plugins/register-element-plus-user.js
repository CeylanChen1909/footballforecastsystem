/**
 * User-shell Element Plus registration (commercial polish r6/r7).
 * Admin-only widgets: register-element-plus-admin.js (loaded with /admin).
 * Heavy route widgets (Table/Menu/Drawer): register-element-plus-{table,menu,drawer}.js (r7).
 */
import {
  ElAlert, ElAvatar, ElBadge, ElButton, ElCard, ElCheckbox, ElContainer,
  ElCollapse, ElCollapseItem, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElForm, ElFormItem, ElHeader, ElEmpty, ElIcon, ElInput, ElMain,
  ElOption, ElProgress, ElRadioButton, ElRadioGroup, ElSelect, ElSkeleton,
  ElTabPane, ElTabs, ElTag, ElTooltip, ElLoading
} from 'element-plus'
import '../styles/element-plus-on-demand.js'
import {
  ArrowDown, ArrowLeft, ArrowRight, Bell, Calendar, ChatLineSquare, CircleCheck, Connection, DataLine,
  Football, Loading, Location, Message, Notebook, Refresh, RefreshRight, Search,
  Setting, Star, StarFilled, SwitchButton, Tickets, TrendCharts, Trophy, User,
  WarningFilled
} from '@element-plus/icons-vue'

const elementComponents = [
  ElAlert, ElAvatar, ElBadge, ElButton, ElCard, ElCheckbox, ElContainer,
  ElCollapse, ElCollapseItem, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElForm, ElFormItem, ElHeader, ElEmpty, ElIcon, ElInput, ElMain,
  ElOption, ElProgress, ElRadioButton, ElRadioGroup, ElSelect, ElSkeleton,
  ElTabPane, ElTabs, ElTag, ElTooltip
]

const elementIcons = {
  ArrowDown, ArrowLeft, ArrowRight, Bell, Calendar, ChatLineSquare, CircleCheck, Connection, DataLine,
  Football, Loading, Location, Message, Notebook, Refresh, RefreshRight, Search,
  Setting, Star, StarFilled, SwitchButton, Tickets, TrendCharts, Trophy, User,
  WarningFilled
}

export function registerElementPlusUser(app) {
  for (const component of elementComponents) app.component(component.name, component)
  for (const [key, value] of Object.entries(elementIcons)) app.component(key, value)
  app.use(ElLoading)
}
