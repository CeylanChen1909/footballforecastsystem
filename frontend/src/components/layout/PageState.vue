<template>
  <div class="state-shell ff-panel" :class="`is-${type}`" :role="type === 'error' ? 'alert' : type === 'loading' ? 'status' : undefined" :aria-live="type === 'error' || type === 'loading' ? 'polite' : undefined">
    <div v-if="type === 'loading'" class="state-block loading-state">
      <div class="state-icon ff-brand-accent">
        <el-icon class="is-loading" :size="size"><Loading /></el-icon>
      </div>
      <p class="state-title">{{ title }}</p>
      <p v-if="description" class="state-desc">{{ description }}</p>
    </div>

    <div v-else-if="type === 'error'" class="state-block error-state">
      <div class="state-icon error-icon"><el-icon :size="size"><WarningFilled /></el-icon></div>
      <p class="state-title">{{ title }}</p>
      <p v-if="description" class="state-desc">{{ description }}</p>
      <el-button v-if="actionText" type="primary" @click="$emit('action')">{{ actionText }}</el-button>
    </div>

    <div v-else class="state-block empty-state">
      <div class="state-icon empty-icon"><el-icon :size="size"><Tickets /></el-icon></div>
      <p class="state-title">{{ title }}</p>
      <p v-if="description" class="state-desc">{{ description }}</p>
      <el-button v-if="actionText" type="primary" plain @click="$emit('action')">{{ actionText }}</el-button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  type: { type: String, default: 'empty' },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  actionText: { type: String, default: '' },
  size: { type: Number, default: 48 },
})

defineEmits(['action'])
</script>

<style scoped>
.state-shell {
  padding: 18px 16px;
  box-shadow: none;
}

.state-block {
  text-align: center;
  color: #7b8796;
  min-height: 136px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
}

.state-icon {
  width: 52px;
  height: 52px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 10px;
  transition: transform var(--ff-transition);
}

.empty-icon {
  background: var(--ff-bg-alt);
  color: var(--ff-text-faint);
}

.error-icon {
  background: rgba(239, 68, 68, 0.10);
  color: #ef4444;
}

.state-title {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 700;
  color: var(--ff-text-strong);
}

.state-desc {
  margin-top: 8px;
  color: var(--ff-text-muted);
  font-size: 12px;
  line-height: 1.65;
  max-width: 420px;
}

.loading-state .state-title {
  color: var(--ff-text-strong);
}

.loading-state .state-icon {
  animation: none;
}

@keyframes ffPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}
</style>
