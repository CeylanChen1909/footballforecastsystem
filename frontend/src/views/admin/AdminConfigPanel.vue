<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header"><span>{{ t('admin.config.title') }}</span><el-button type="primary" @click="$emit('save')">{{ t('admin.config.save') }}</el-button></div>
    </template>
    <PageState v-if="error" type="error" :title="t('admin.config.loadFailed')" :description="error" :action-text="t('admin.common.retry')" @action="$emit('retry')" />
    <template v-if="!error">
      <el-divider content-position="left">{{ t('admin.config.agentPolicy') }}</el-divider>
      <el-alert
        :title="t('admin.config.note')"
        type="info"
        :closable="false"
        show-icon
        class="agent-config-note"
      />
      <el-descriptions :column="1" border class="agent-config-descriptions">
        <el-descriptions-item :label="t('admin.config.label.defaultProvider')">
          <el-select v-model="config['agent.model.provider']" :placeholder="t('admin.config.placeholder.auto')" clearable>
            <el-option :label="t('admin.config.option.auto')" value="auto" />
            <el-option label="DeepSeek" value="deepseek" />
            <el-option label="OpenRouter" value="openrouter" />
            <el-option label="SCNet · GLM-5-Base" value="scnet" />
          </el-select>
        </el-descriptions-item>
        <el-descriptions-item label="DeepSeek Base URL">
          <el-input v-model="config['agent.model.deepseek.base-url']" :placeholder="t('admin.config.placeholder.deepseekBase')" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.deepseekModel')">
          <el-input v-model="config['agent.model.deepseek.model']" :placeholder="t('admin.config.placeholder.deepseekModel')" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.deepseekModels')">
          <el-input v-model="config['agent.model.deepseek.models']" :placeholder="t('admin.config.placeholder.deepseekModels')" />
        </el-descriptions-item>
        <el-descriptions-item label="OpenRouter Base URL">
          <el-input v-model="config['agent.model.openrouter.base-url']" :placeholder="t('admin.config.placeholder.openrouterBase')" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.openrouterModel')">
          <el-input v-model="config['agent.model.openrouter.model']" :placeholder="t('admin.config.placeholder.openrouterModel')" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.openrouterModels')">
          <el-input v-model="config['agent.model.openrouter.models']" :placeholder="t('admin.config.placeholder.openrouterModels')" />
        </el-descriptions-item>
        <el-descriptions-item label="SCNet Base URL">
          <el-input v-model="config['agent.model.scnet.base-url']" placeholder="https://api.scnet.cn/api/llm/v1" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.scnetModel')">
          <el-input v-model="config['agent.model.scnet.model']" placeholder="GLM-5-Base" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.scnetModels')">
          <el-input v-model="config['agent.model.scnet.models']" :placeholder="t('admin.config.placeholder.scnetModels')" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.thinking')">
          <el-switch v-model="thinkingEnabled" active-value="true" inactive-value="false" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.fallback')">
          <el-switch v-model="fallbackEnabled" active-value="true" inactive-value="false" />
        </el-descriptions-item>
        <el-descriptions-item label="Temperature">
          <el-input v-model="config['agent.model.temperature']" placeholder="0.4" />
        </el-descriptions-item>
        <el-descriptions-item :label="t('admin.config.label.maxTokens')">
          <el-input v-model="config['agent.model.max-tokens']" placeholder="1200" />
        </el-descriptions-item>
      </el-descriptions>
    </template>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import PageState from '../../components/layout/PageState.vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()

const props = defineProps({
  config: { type: Object, required: true },
  error: { type: String, default: '' }
})
defineEmits(['save', 'retry'])

// The configuration API historically returned both booleans and the string
// values "true"/"false".  Element Plus requires model-value to match the
// switch values exactly, so bridge the external config to a stable string.
const switchValue = (key) => computed({
  get: () => String(props.config[key] ?? 'false') === 'true' ? 'true' : 'false',
  set: value => { props.config[key] = value === 'true' ? 'true' : 'false' }
})
const thinkingEnabled = switchValue('agent.model.thinking.enabled')
const fallbackEnabled = switchValue('agent.model.fallback.enabled')
</script>

<style scoped>
.panel-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.agent-config-note { margin-bottom: 14px; }
.agent-config-descriptions :deep(.el-input), .agent-config-descriptions :deep(.el-select) { max-width: 620px; width: 100%; }
</style>
