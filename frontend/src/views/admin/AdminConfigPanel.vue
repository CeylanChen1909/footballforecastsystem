<template>
  <el-card class="panel-card" shadow="never">
    <template #header>
      <div class="panel-header"><span>系统配置</span><el-button type="primary" @click="$emit('save')">保存配置</el-button></div>
    </template>
    <PageState v-if="error" type="error" title="配置加载失败" :description="error" action-text="重试" @action="$emit('retry')" />
    <template v-if="!error">
      <el-divider content-position="left">Agent 全站模型策略</el-divider>
      <el-alert
        title="模型由管理员统一控制，普通用户不能切换。API Key 仍只从服务端环境变量读取，不会写入数据库或展示在页面。"
        type="info"
        :closable="false"
        show-icon
        class="agent-config-note"
      />
      <el-descriptions :column="1" border class="agent-config-descriptions">
        <el-descriptions-item label="默认模型通道">
          <el-select v-model="config['agent.model.provider']" placeholder="auto（自动选择）" clearable>
            <el-option label="自动选择（优先可用通道）" value="auto" />
            <el-option label="DeepSeek" value="deepseek" />
            <el-option label="OpenRouter" value="openrouter" />
            <el-option label="SCNet · GLM-5-Base" value="scnet" />
          </el-select>
        </el-descriptions-item>
        <el-descriptions-item label="DeepSeek Base URL">
          <el-input v-model="config['agent.model.deepseek.base-url']" placeholder="留空使用 DEEPSEEK_BASE_URL / 默认地址" />
        </el-descriptions-item>
        <el-descriptions-item label="DeepSeek 默认模型">
          <el-input v-model="config['agent.model.deepseek.model']" placeholder="留空使用 DEEPSEEK_MODEL" />
        </el-descriptions-item>
        <el-descriptions-item label="DeepSeek 模型白名单">
          <el-input v-model="config['agent.model.deepseek.models']" placeholder="多个模型用英文逗号分隔；留空不限制目录" />
        </el-descriptions-item>
        <el-descriptions-item label="OpenRouter Base URL">
          <el-input v-model="config['agent.model.openrouter.base-url']" placeholder="留空使用 OPENROUTER_BASE_URL / 默认地址" />
        </el-descriptions-item>
        <el-descriptions-item label="OpenRouter 默认模型">
          <el-input v-model="config['agent.model.openrouter.model']" placeholder="留空使用 OPENROUTER_MODEL" />
        </el-descriptions-item>
        <el-descriptions-item label="OpenRouter 模型白名单">
          <el-input v-model="config['agent.model.openrouter.models']" placeholder="多个模型用英文逗号分隔；留空使用内置目录" />
        </el-descriptions-item>
        <el-descriptions-item label="SCNet Base URL">
          <el-input v-model="config['agent.model.scnet.base-url']" placeholder="https://api.scnet.cn/api/llm/v1" />
        </el-descriptions-item>
        <el-descriptions-item label="SCNet 默认模型">
          <el-input v-model="config['agent.model.scnet.model']" placeholder="GLM-5-Base" />
        </el-descriptions-item>
        <el-descriptions-item label="SCNet 模型白名单">
          <el-input v-model="config['agent.model.scnet.models']" placeholder="多个模型用英文逗号分隔；留空使用 GLM-5-Base" />
        </el-descriptions-item>
        <el-descriptions-item label="允许模型思考摘要">
          <el-switch v-model="thinkingEnabled" active-value="true" inactive-value="false" />
        </el-descriptions-item>
        <el-descriptions-item label="通道故障自动切换">
          <el-switch v-model="fallbackEnabled" active-value="true" inactive-value="false" />
        </el-descriptions-item>
        <el-descriptions-item label="Temperature">
          <el-input v-model="config['agent.model.temperature']" placeholder="0.4" />
        </el-descriptions-item>
        <el-descriptions-item label="最大输出 Tokens">
          <el-input v-model="config['agent.model.max-tokens']" placeholder="1200" />
        </el-descriptions-item>
      </el-descriptions>
    </template>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import PageState from '../../components/layout/PageState.vue'

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
