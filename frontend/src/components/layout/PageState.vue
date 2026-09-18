<template>
  <div class="state-shell" :class="`is-${type}`" :role="type === 'error' ? 'alert' : type === 'loading' ? 'status' : undefined" :aria-live="type === 'error' || type === 'loading' ? 'polite' : undefined">
    <div class="state-block">
      <span class="state-mark" aria-hidden="true">{{ type === 'loading' ? '' : type === 'error' ? '!' : '—' }}</span>
      <div class="state-copy">
        <p class="state-title">{{ title }}</p>
        <p v-if="description" class="state-desc">{{ description }}</p>
        <el-button v-if="actionText && type !== 'loading'" :type="type === 'error' ? 'primary' : 'primary'" :plain="type !== 'error'" @click="$emit('action')">{{ actionText }}</el-button>
      </div>
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
.state-shell { min-width: 0; max-width: 100%; }
.state-block {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  text-align: left;
  min-height: 0;
  padding: 14px 14px 14px 12px;
  border: 1px solid var(--ff-border);
  border-left: 3px solid var(--ff-primary);
  border-radius: 6px;
  background: var(--ff-surface);
  color: var(--ff-text);
}
.is-error .state-block { border-left-color: var(--ff-danger); }
.is-loading .state-block { border-left-color: var(--ff-text-faint); }
.state-mark {
  flex: none;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  display: grid;
  place-items: center;
  border-radius: 4px;
  background: var(--ff-surface-quiet);
  color: var(--ff-primary);
  font: 700 13px/1 var(--ff-mono);
}
.is-error .state-mark { color: var(--ff-danger); background: #f8ecea; }
.is-loading .state-mark {
  background: transparent;
  color: transparent;
  border: 2px solid var(--ff-border);
  border-top-color: var(--ff-primary);
  border-radius: 50%;
  animation: ffStateSpin .8s linear infinite;
}
@keyframes ffStateSpin { to { transform: rotate(360deg); } }
.state-copy { min-width: 0; }
.state-title { margin: 0; font-size: 14px; font-weight: 700; color: var(--ff-text-strong); }
.state-desc { margin: 4px 0 0; color: var(--ff-text-muted); font-size: 12px; line-height: 1.55; max-width: 46ch; }
.state-copy :deep(.el-button) { margin-top: 10px; min-height: 36px; }
@media (prefers-reduced-motion: reduce) {
  .is-loading .state-mark { animation: none; border-top-color: var(--ff-text-faint); }
}
</style>
