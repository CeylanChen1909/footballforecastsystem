/**
 * 图片验证码（旧版 ImageCaptcha.vue 同款行为）。
 * props: image（data URL 或空）, answer, onAnswerChange, onRefresh, onSubmit。
 */
import { t } from '../../i18n'

export default function ImageCaptcha(props) {
  return (
    <div class="ff-image-captcha" role="group" aria-label={t('auth.captcha')}>
      <button type="button" class="ff-captcha-image-btn" title="点击刷新验证码" onClick={() => props.onRefresh?.()}>
        {props.image ? (
          <img src={props.image} alt="图形验证码，点击刷新" />
        ) : (
          <span class="ff-captcha-placeholder">{t('common.loading')}</span>
        )}
      </button>
      <input
        class="ff-input"
        value={props.answer || ''}
        placeholder={t('auth.captchaImagePlaceholder')}
        autocomplete="off"
        maxlength="5"
        onInput={(e) => props.onAnswerChange?.(e.currentTarget.value.toUpperCase())}
        onKeyDown={(e) => {
          if (e.key === 'Enter') props.onSubmit?.()
        }}
      />
      <button type="button" class="ff-captcha-refresh" onClick={() => props.onRefresh?.()}>
        {t('auth.captchaRefresh')}
      </button>
    </div>
  )
}
