import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElAlert, ElAside, ElAvatar, ElBadge, ElButton, ElCard, ElCheckbox, ElContainer, ElDatePicker, ElDescriptions, ElDescriptionsItem,
  ElCollapse, ElCollapseItem, ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu, ElForm, ElFormItem, ElHeader,
  ElEmpty, ElIcon, ElInput, ElInputNumber, ElMain, ElMenu, ElMenuItem, ElOption, ElPagination, ElProgress, ElRadioButton,
  ElRadioGroup, ElSegmented, ElSelect, ElSkeleton, ElSwitch, ElTabPane, ElTable, ElTableColumn, ElTabs, ElTag,
  ElTimePicker, ElTimeline, ElTimelineItem, ElTooltip, ElLoading
} from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import {
  ArrowDown, ArrowLeft, ArrowRight, Bell, Calendar, ChatLineSquare, CircleCheck, Connection, DataLine,
  Football, Histogram, Loading, Location, MagicStick, Message, Notebook, OfficeBuilding, Refresh, RefreshRight, Search,
  Setting, Star, StarFilled, SwitchButton, Tickets, TrendCharts, Trophy, User, VideoCamera, VideoPlay,
  WarningFilled
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)

const elementComponents = [
  ElAlert, ElAside, ElAvatar, ElBadge, ElButton, ElCard, ElCheckbox, ElContainer, ElDatePicker, ElDescriptions, ElDescriptionsItem,
  ElCollapse, ElCollapseItem, ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu, ElForm, ElFormItem, ElHeader,
  ElEmpty, ElIcon, ElInput, ElInputNumber, ElMain, ElMenu, ElMenuItem, ElOption, ElPagination, ElProgress, ElRadioButton,
  ElRadioGroup, ElSegmented, ElSelect, ElSkeleton, ElSwitch, ElTabPane, ElTable, ElTableColumn, ElTabs, ElTag,
  ElTimePicker, ElTimeline, ElTimelineItem, ElTooltip
]
for (const component of elementComponents) app.component(component.name, component)
const elementIcons = {
  ArrowDown, ArrowLeft, ArrowRight, Bell, Calendar, ChatLineSquare, CircleCheck, Connection, DataLine,
  Football, Histogram, Loading, Location, MagicStick, Message, Notebook, OfficeBuilding, Refresh, RefreshRight, Search,
  Setting, Star, StarFilled, SwitchButton, Tickets, TrendCharts, Trophy, User, VideoCamera, VideoPlay,
  WarningFilled
}
for (const [key, value] of Object.entries(elementIcons)) app.component(key, value)

app.use(createPinia())
app.use(router)
app.use(ElLoading)
app.mount('#app')
