<template>
  <button class="agent-launcher" type="button" :aria-label="t('components.agentLauncher.ariaLabel')" @click="router.push('/agent')">
    <el-icon :size="18"><ChatLineSquare /></el-icon>
    <span>{{ t('components.agentLauncher.label') }}</span>
  </button>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ChatLineSquare } from '@element-plus/icons-vue'
import { useI18n } from '../../i18n'

const router = useRouter()
const { t } = useI18n()
</script>

<style scoped>
.agent-launcher {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 30;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  color: var(--ff-text);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--ff-shadow-md);
  cursor: pointer;
  font: 500 12px/1 'Segoe UI', 'PingFang SC', sans-serif;
  transition: transform var(--ff-transition-fast), background var(--ff-transition-fast), box-shadow var(--ff-transition-fast), border-color var(--ff-transition-fast), color var(--ff-transition-fast);
}
.agent-launcher:hover {
  background: #fff;
  border-color: var(--ff-primary);
  color: var(--ff-primary);
}
.agent-launcher:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 3px; }
@media (max-width: 720px) {
  .agent-launcher { right: 14px; bottom: 14px; width: 42px; min-height: 42px; justify-content: center; padding: 0; }
  .agent-launcher span { display: none; }
}
</style>
