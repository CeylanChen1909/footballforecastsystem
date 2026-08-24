<template>
  <div v-if="visible" class="legal-gate" role="dialog" aria-modal="true" aria-labelledby="legal-gate-title">
    <section class="legal-card">
      <header class="legal-header">
        <span class="legal-kicker">首次访问确认</span>
        <h2 id="legal-gate-title">用户协议与免责声明</h2>
        <p>请阅读到底部并确认后，才能继续使用 ChenFootball。</p>
      </header>

      <div ref="contentRef" class="legal-content" tabindex="0" @scroll="onScroll">
        <h3>一、服务说明</h3>
        <p>本平台的开发目的为技术学习，所提供足球赛程、球队资料、统计信息和模型生成的比赛分析，仅作为信息整理与技术展示服务。部分内容来自公开网页、第三方接口或模型推断，可能存在延迟、缺失、错误或更新不及时。</p>
        <h3>二、免责声明</h3>
        <p>预测结果、概率、排名、伤停和阵容信息均不构成投资建议、博彩建议、交易建议或任何形式的结果保证，并强烈禁止使用本网站数据进行博彩等违法活动。比赛结果受临场因素影响，平台不保证预测准确，也不对用户依据平台信息作出的决定承担责任。</p>
        <h3>三、数据与外部来源</h3>
        <p>赛程、队徽、球员资料和其他内容可能由第三方来源提供。平台会尽量标注来源和更新时间，但无法保证第三方服务持续可用或信息完整。请以赛事组织方、俱乐部和官方渠道发布的信息为准。</p>
        <h3>四、用户责任</h3>
        <p>你应当遵守所在地法律法规和第三方平台规则，不得利用本平台进行违法活动、攻击服务、批量注册、滥用接口、绕过限流或传播侵权、欺诈、恶意及其他违规内容。账号凭据由你自行保管。</p>
        <h3>五、隐私与账号</h3>
        <p>平台会按隐私与数据说明处理注册邮箱、昵称、登录安全信息、收藏和使用记录。你可以在个人中心查看、更正或注销账号；注销后，相关私人数据会按平台保留和清理规则处理。</p>
        <h3>六、协议变更</h3>
        <p>当服务范围、数据来源或法律要求发生变化时，平台可能更新本协议。重大更新会重新要求确认。若你不同意更新内容，应停止使用相关服务。</p>
        <p class="legal-end">—— 已阅读至末尾 ——</p>
      </div>

      <div class="legal-footer">
        <label class="legal-check">
          <input v-model="confirmed" type="checkbox" :disabled="!reachedEnd" />
          <span>我已知情并同意用户协议与免责声明</span>
        </label>
        <p v-if="!reachedEnd" class="legal-hint">请先在上方内容区域滚动至底部</p>
        <p class="legal-links">协议版本：20260824-v1；隐私与数据说明已包含在上方条款中。</p>
        <button type="button" class="legal-submit" :disabled="!confirmed || saving" @click="accept">
          {{ saving ? '保存中…' : '确认并进入网站' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi } from '../../api'
import { useUserStore } from '../../stores/user'
import { saveLegalConsent } from '../../utils/legalConsent'

const props = defineProps({ visible: { type: Boolean, default: false } })
const emit = defineEmits(['accepted'])
const userStore = useUserStore()
const contentRef = ref(null)
const reachedEnd = ref(false)
const confirmed = ref(false)
const saving = ref(false)

const onScroll = event => {
  const el = event.target
  reachedEnd.value = el.scrollTop + el.clientHeight >= el.scrollHeight - 12
}

const syncBodyLock = value => { document.body.style.overflow = value ? 'hidden' : '' }
watch(() => props.visible, value => {
  syncBodyLock(value)
  if (value) {
    reachedEnd.value = false
    confirmed.value = false
    if (contentRef.value) contentRef.value.scrollTop = 0
  }
}, { immediate: true })
onBeforeUnmount(() => syncBodyLock(false))

const accept = async () => {
  if (!reachedEnd.value || !confirmed.value || saving.value) return
  saving.value = true
  try {
    if (userStore.token) {
      const result = await userApi.acceptLegalConsent()
      const data = result?.data ?? result
      if (data?.accepted === false || data?.ok === false) throw new Error(data?.message || '协议确认保存失败')
    }
    saveLegalConsent()
    emit('accepted')
  } catch (error) {
    ElMessage.error(error?.message || '协议确认保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.legal-gate { position:fixed; inset:0; z-index:4000; display:grid; place-items:center; padding:20px; background:rgba(12,28,22,.72); backdrop-filter:blur(4px); }
.legal-card { width:min(660px, 100%); max-height:min(760px, calc(100vh - 40px)); display:flex; flex-direction:column; overflow:hidden; border:1px solid rgba(255,255,255,.16); border-radius:14px; background:var(--ff-surface); box-shadow:0 24px 80px rgba(0,0,0,.28); }
.legal-header { padding:24px 26px 18px; border-bottom:1px solid var(--ff-border); }
.legal-kicker { color:var(--ff-primary); font-size:11px; font-weight:700; letter-spacing:.08em; }
.legal-header h2 { margin-top:7px; color:var(--ff-text-strong); font-size:22px; }
.legal-header p { margin-top:7px; color:var(--ff-text-muted); font-size:13px; }
.legal-content { flex:1; min-height:220px; overflow:auto; padding:20px 26px 26px; color:var(--ff-text); font-size:13px; line-height:1.8; overscroll-behavior:contain; }
.legal-content h3 { margin:16px 0 5px; color:var(--ff-text-strong); font-size:14px; }
.legal-content h3:first-child { margin-top:0; }
.legal-content p { margin:0 0 8px; }
.legal-end { padding-top:12px; color:var(--ff-primary); text-align:center; font-size:12px; }
.legal-footer { padding:16px 26px 22px; border-top:1px solid var(--ff-border); }
.legal-check { display:flex; align-items:flex-start; gap:9px; color:var(--ff-text-strong); font-size:13px; line-height:1.5; cursor:pointer; }
.legal-check input { width:16px; height:16px; margin-top:1px; accent-color:var(--ff-primary); }
.legal-hint { margin-top:7px; color:#a15c26; font-size:12px; }
.legal-links { margin-top:7px; font-size:12px; }
.legal-links { color:var(--ff-text-muted); }
.legal-submit { width:100%; height:44px; margin-top:14px; border:0; border-radius:8px; color:#fff; background:var(--ff-primary); font-size:14px; font-weight:600; cursor:pointer; }
.legal-submit:disabled { opacity:.45; cursor:not-allowed; }
@media (max-width:600px) {
  .legal-gate { padding:10px; }
  .legal-card { max-height:calc(100vh - 20px); border-radius:10px; }
  .legal-header, .legal-content, .legal-footer { padding-left:18px; padding-right:18px; }
  .legal-header h2 { font-size:19px; }
}
</style>
