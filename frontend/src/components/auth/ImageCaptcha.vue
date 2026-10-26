<template>
  <div class="image-captcha" role="group" :aria-label="t('account.captcha.groupAria')">
    <button type="button" class="captcha-image-button" :title="t('account.captcha.refreshTitle')" @click="$emit('refresh')">
      <img v-if="image" :src="image" :alt="t('account.captcha.imageAlt')" />
      <span v-else class="captcha-placeholder">{{ t('account.captcha.loading') }}</span>
    </button>
    <el-input
      :model-value="answer"
      :placeholder="t('account.captcha.placeholder')"
      autocomplete="off"
      maxlength="5"
      @update:model-value="$emit('update:answer', $event.toUpperCase())"
      @keyup.enter="$emit('submit')"
    />
    <button type="button" class="captcha-refresh" @click="$emit('refresh')">{{ t('account.captcha.change') }}</button>
  </div>
</template>

<script setup>
import { useI18n } from '../../i18n'

defineProps({
  image: { type: String, default: '' },
  answer: { type: String, default: '' }
})
defineEmits(['refresh', 'update:answer', 'submit'])

const { t } = useI18n()
</script>

<style scoped>
.image-captcha { display:flex; align-items:center; gap:8px; width:100%; }
.captcha-image-button { width:132px; height:42px; flex:none; border:1px solid var(--ff-border); border-radius:7px; padding:0; overflow:hidden; background:var(--ff-surface-quiet); cursor:pointer; }
.captcha-image-button img { display:block; width:100%; height:100%; object-fit:cover; }
.captcha-placeholder { color:var(--ff-text-muted); font-size:12px; }
.image-captcha .el-input { min-width:0; flex:1; }
.captcha-refresh { flex:none; border:0; padding:4px 0; color:var(--ff-primary); background:transparent; font-size:12px; cursor:pointer; white-space:nowrap; }
.captcha-refresh:hover { text-decoration:underline; }
@media (max-width: 420px) {
  .image-captcha { flex-wrap:wrap; }
  .captcha-image-button { width:128px; }
  .image-captcha .el-input { flex:1; min-width:140px; }
}
</style>
