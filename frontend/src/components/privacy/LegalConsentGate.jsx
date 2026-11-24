/**
 * 法律确认门（旧版 LegalConsentGate.vue 同款行为）。
 * 首次访问必须滚动到底部、勾选同意后才能进入；已登录用户先调接口保存，
 * 未登录只记本地。文案与旧版一字不差。
 */
import { createEffect, createSignal, Show } from 'solid-js'
import auth from '../../stores/auth'
import { userApi } from '../../api'
import { saveLegalConsent } from '../../utils/legalConsent'
import { toastError } from '../../utils/toast'
import { t } from '../../i18n'

export default function LegalConsentGate(props) {
  const [reachedEnd, setReachedEnd] = createSignal(false)
  const [confirmed, setConfirmed] = createSignal(false)
  const [saving, setSaving] = createSignal(false)
  let contentRef

  // Solid 2.0 的 createEffect 是双参：compute 追踪依赖，effect 不追踪。
  createEffect(
    () => props.visible,
    (visible) => {
      document.body.style.overflow = visible ? 'hidden' : ''
      if (visible) {
        setReachedEnd(false)
        setConfirmed(false)
        if (contentRef) contentRef.scrollTop = 0
      }
    },
  )

  const onScroll = (e) => {
    const el = e.currentTarget
    setReachedEnd(el.scrollTop + el.clientHeight >= el.scrollHeight - 12)
  }

  const accept = async () => {
    if (!reachedEnd() || !confirmed() || saving()) return
    setSaving(true)
    try {
      if (auth.token()) {
        const data = await userApi.acceptLegalConsent()
        if (data?.accepted === false || data?.ok === false) throw new Error(data?.message || '协议确认保存失败')
      }
      saveLegalConsent()
      props.onAccepted?.()
    } catch (error) {
      toastError(error?.message || '协议确认保存失败，请稍后重试')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Show when={props.visible}>
      <div class="ff-legal-gate" role="dialog" aria-modal="true" aria-labelledby="legal-gate-title">
        <section class="ff-legal-card">
          <header class="ff-legal-header">
            <span class="ff-legal-kicker">{t('legal.kicker')}</span>
            <h2 id="legal-gate-title">{t('legal.title')}</h2>
            <p>{t('legal.intro')}</p>
          </header>
          <div ref={contentRef} class="ff-legal-content" tabindex="0" onScroll={onScroll}>
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
            <p class="ff-legal-end">{t('legal.readToEnd')}</p>
          </div>
          <div class="ff-legal-footer">
            <label class="ff-legal-check">
              <input type="checkbox" checked={confirmed()} disabled={!reachedEnd()} onChange={(e) => setConfirmed(e.currentTarget.checked)} />
              <span>{t('legal.confirmLabel')}</span>
            </label>
            <Show when={!reachedEnd()}>
              <p class="ff-legal-hint">{t('legal.scrollHint')}</p>
            </Show>
            <p class="ff-legal-links">{t('legal.version')}</p>
            <button type="button" class="ff-btn ff-btn-primary ff-btn-block" style="height:44px;margin-top:14px" disabled={!confirmed() || saving()} onClick={accept}>
              {saving() ? t('legal.saving') : t('legal.accept')}
            </button>
          </div>
        </section>
      </div>
    </Show>
  )
}
