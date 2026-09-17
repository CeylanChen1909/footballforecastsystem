<template>
  <button class="agent-launcher" type="button" aria-label="打开 Agent 工作台" @click="router.push('/agent')">
    <el-icon :size="21"><ChatLineSquare /></el-icon>
    <span>Agent</span>
  </button>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ChatLineSquare } from '@element-plus/icons-vue'

const router = useRouter()
</script>

<style scoped>
.agent-launcher {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 30;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 42px;
  padding: 0 14px;
  border: 1px solid rgba(15, 107, 77, .25);
  border-radius: 999px;
  color: #fff;
  background: var(--ff-primary);
  box-shadow: var(--ff-shadow-lg);
  cursor: pointer;
  font: 600 13px/1 'Segoe UI', 'PingFang SC', sans-serif;
  transition: transform var(--ff-transition-fast), background var(--ff-transition-fast), box-shadow var(--ff-transition-fast);
}
.agent-launcher:hover { background: var(--ff-primary-hover); }
.agent-launcher:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 3px; }
@media (max-width: 720px) {
  .agent-launcher { right: 14px; bottom: 14px; width: 42px; min-height: 42px; justify-content: center; padding: 0; }
  .agent-launcher span { display: none; }
}
</style>
