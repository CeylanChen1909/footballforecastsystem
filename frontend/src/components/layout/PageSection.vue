<template>
  <section class="page-section ff-panel" :class="variantClass">
    <div v-if="$slots.header || title || subtitle || $slots.actions" class="section-head">
      <div>
        <div v-if="title" class="section-title">{{ title }}</div>
        <div v-if="subtitle" class="section-subtitle">{{ subtitle }}</div>
        <slot name="header" />
      </div>
      <div v-if="$slots.actions" class="section-actions">
        <slot name="actions" />
      </div>
    </div>
    <div class="section-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  variant: { type: String, default: 'default' },
})

const variantClass = computed(() => (props.variant ? `is-${props.variant}` : ''))
</script>

<style scoped>
.page-section {
  padding: 18px 20px;
  transition: border-color var(--ff-transition-fast), background-color var(--ff-transition-fast);
}

.page-section.is-hero {
  padding: 18px 20px;
}

.page-section.is-compact {
  padding: 14px 16px;
}

.page-section:hover {
  border-color: var(--ff-border-strong);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--ff-border);
}

.section-title {
  font-size: 15px;
  font-weight: 750;
  color: var(--ff-text-strong);
  letter-spacing: -0.01em;
}

.section-subtitle {
  margin-top: 5px;
  font-size: 11px;
  color: var(--ff-text-muted);
  line-height: 1.65;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.section-body {
  min-width: 0;
}
</style>
