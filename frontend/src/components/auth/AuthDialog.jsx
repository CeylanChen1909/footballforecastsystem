/**
 * 鉴权对话框（旧版 AuthDialog.vue 的同款行为，Kobalte Dialog 实现）。
 * 三个页签：登录 / 注册 / 找回密码。登录支持服务端下发的计算验证
 * （loginChallenge.captchaQuestion）；注册含图片验证码位 + 邮箱验证码
 * （60s 重发倒计时）；登录接口接线等契约组核对后再校准。
 */
import { createEffect, createSignal, Show, onCleanup } from 'solid-js'
import { Dialog } from '@kobalte/core/dialog'
import { useNavigate } from '@solidjs/router'
import auth from '../../stores/auth'
import { userApi } from '../../api'
import { toastError, toastInfo, toastSuccess, toastWarning } from '../../utils/toast'
import { t } from '../../i18n'
import ImageCaptcha from './ImageCaptcha'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export default function AuthDialog() {
  const navigate = useNavigate()
  // Solid 2.0 严格读：组件体里直接读 authDialogTab() 初始化 signal 会报
  // STRICT_READ_UNTRACKED；初始值固定 'login'，打开时由下面的 effect 同步。
  const [activeTab, setActiveTab] = createSignal('login')
  const [loading, setLoading] = createSignal(false)
  const [loginAccount, setLoginAccount] = createSignal('')
  const [loginPassword, setLoginPassword] = createSignal('')
  const [loginCaptchaAnswer, setLoginCaptchaAnswer] = createSignal('')
  const [loginError, setLoginError] = createSignal('')
  const [regEmail, setRegEmail] = createSignal('')
  const [regNickname, setRegNickname] = createSignal('')
  const [regPassword, setRegPassword] = createSignal('')
  const [regConfirm, setRegConfirm] = createSignal('')
  const [regCode, setRegCode] = createSignal('')
  const [regCaptchaId, setRegCaptchaId] = createSignal('')
  const [regCaptchaImage, setRegCaptchaImage] = createSignal('')
  const [regCaptchaAnswer, setRegCaptchaAnswer] = createSignal('')
  const [regCaptchaVerified, setRegCaptchaVerified] = createSignal(false)
  const [regCodeEmail, setRegCodeEmail] = createSignal('')
  const [regError, setRegError] = createSignal('')
  const [codeCountdown, setCodeCountdown] = createSignal(0)
  const [resetEmail, setResetEmail] = createSignal('')
  const [resetCode, setResetCode] = createSignal('')
  const [resetPassword, setResetPassword] = createSignal('')
  const [resetConfirm, setResetConfirm] = createSignal('')
  const [resetError, setResetError] = createSignal('')
  const [resetCodeCountdown, setResetCodeCountdown] = createSignal(0)

  let codeTimer = null
  let resetCodeTimer = null
  onCleanup(() => {
    window.clearInterval(codeTimer)
    window.clearInterval(resetCodeTimer)
  })
  const startCountdown = (setFn, seconds = 60) => {
    setFn(seconds)
    return window.setInterval(() => {
      setFn((v) => {
        if (v <= 1) {
          window.clearInterval(codeTimer)
          window.clearInterval(resetCodeTimer)
          return 0
        }
        return v - 1
      })
    }, 1000)
  }

  const refreshRegisterCaptcha = async () => {
    setRegCaptchaAnswer('')
    setRegCaptchaImage('')
    setRegCaptchaVerified(false)
    try {
      const data = await userApi.getRegistrationCaptcha()
      if (data?.ok === false) throw new Error(data.message || '图形验证码加载失败')
      setRegCaptchaId(data?.captchaId || '')
      setRegCaptchaImage(data?.image || '')
    } catch (error) {
      toastError(error?.message || '图形验证加载失败，请刷新后重试')
    }
  }

  // Solid 2.0 的 createEffect 是双参：compute 追踪依赖并返回值，effect 不追踪。
  createEffect(
    () => ({ visible: auth.authDialogVisible(), tab: auth.authDialogTab() || 'login' }),
    ({ visible, tab }) => {
      if (visible) {
        setActiveTab(tab)
        setLoginError('')
        setRegError('')
        setResetError('')
        if (tab === 'register' && !regCaptchaImage()) refreshRegisterCaptcha()
      }
    },
  )
  createEffect(
    () => ({ tab: activeTab(), captcha: regCaptchaImage(), visible: auth.authDialogVisible() }),
    ({ tab, captcha, visible }) => {
      if (tab === 'register' && !captcha && visible) refreshRegisterCaptcha()
    },
  )
  createEffect(
    // 注册邮箱改了，之前发的验证码与图形验证作废（与旧版 watch 一致）。
    // regCodeEmail 也要进 compute：effect 回调里直接读会报 STRICT_READ_UNTRACKED。
    () => ({ email: regEmail().trim().toLowerCase(), codeEmail: regCodeEmail() }),
    ({ email, codeEmail }) => {
      if (codeEmail && email !== codeEmail) {
        setRegCodeEmail('')
        setRegCode('')
        setRegCaptchaVerified(false)
        refreshRegisterCaptcha()
      }
    },
  )

  // goAfterLogin（契约 §3.1）：redirect 仅当以 '/' 开头且不是 '//' 开头时回跳，
  // 否则管理员 → /admin、其余 → /matches；用完清空。
  const afterAuthSuccess = () => {
    const redirect = auth.authDialogRedirect()
    auth.clearAuthRedirect()
    auth.closeAuthDialog()
    if (redirect && redirect.startsWith('/') && !redirect.startsWith('//')) navigate(redirect)
    else navigate(auth.isAdmin() ? '/admin' : '/matches')
  }

  const handleLogin = async () => {
    if (!loginAccount().trim()) return setLoginError('请输入邮箱或账号')
    if (!loginPassword()) return setLoginError('请输入密码')
    if (auth.loginChallenge() && !loginCaptchaAnswer().trim()) return setLoginError('请输入计算结果')
    setLoading(true)
    setLoginError('')
    try {
      const ok = await auth.login(
        loginAccount().trim(),
        loginPassword(),
        auth.loginChallenge()?.captchaId || '',
        loginCaptchaAnswer(),
      )
      if (ok) afterAuthSuccess()
    } catch (error) {
      setLoginError(error?.message || '登录未成功，请稍后再试')
    } finally {
      setLoading(false)
    }
  }

  const sendRegisterCode = async () => {
    if (!EMAIL_RE.test(regEmail())) {
      toastWarning(t('auth.fillValidEmailFirst'))
      return
    }
    if (regCaptchaVerified()) {
      await refreshRegisterCaptcha()
      toastInfo(t('auth.newCaptchaAfterSend'))
      return
    }
    if (!regCaptchaId() || !regCaptchaAnswer()) {
      toastWarning(t('auth.completeCaptchaFirst'))
      return
    }
    try {
      const result = await userApi.sendEmailCode(regEmail(), 'REGISTER', regCaptchaId(), regCaptchaAnswer())
      if (result?.ok === false) throw new Error(result.message || '验证码发送失败')
      toastSuccess(result?.delivery === 'console' ? t('auth.codeSentConsole') : t('auth.codeSent'))
      setRegCodeEmail(regEmail().trim().toLowerCase())
      setRegCaptchaVerified(true)
      codeTimer = startCountdown(setCodeCountdown)
    } catch (error) {
      toastError(error?.message || '验证码未能发送，请稍后重试')
    }
  }

  const handleRegister = async () => {
    if (!EMAIL_RE.test(regEmail())) return setRegError('邮箱格式不正确')
    if (!regNickname().trim()) return setRegError('请输入昵称')
    if (regNickname().length > 32) return setRegError('昵称不能超过32个字符')
    if (regCode().length !== 6) return setRegError('验证码为6位数字')
    if (regPassword().length < 8) return setRegError('至少 8 位密码')
    if (regConfirm() !== regPassword()) return setRegError('两次密码不一致')
    setLoading(true)
    setRegError('')
    try {
      const ok = await auth.register(regEmail().trim(), regNickname().trim(), regPassword(), regCode(), regCaptchaId(), regCaptchaAnswer())
      if (ok) {
        // 注册成功不自动登录：切回 login tab 并回填账号（契约 §3.1）。
        toastSuccess('注册成功，请使用邮箱登录以同步收藏与预测')
        setLoginAccount(regEmail().trim())
        setActiveTab('login')
      }
    } catch (error) {
      setRegError(error?.message || '注册未完成，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  // 找回密码发码不走 sendEmailCode，走 requestPasswordReset（契约 §2.2）。
  const sendResetCode = async () => {
    if (!EMAIL_RE.test(resetEmail())) {
      toastWarning(t('auth.fillValidEmailFirst'))
      return
    }
    try {
      const result = await userApi.requestPasswordReset(resetEmail())
      toastSuccess(result?.delivery === 'console' ? t('auth.codeSentConsole') : t('auth.codeSent'))
      resetCodeTimer = startCountdown(setResetCodeCountdown)
    } catch (error) {
      toastError(error?.message || '验证码未能发送，请稍后重试')
    }
  }

  const handleReset = async () => {
    if (!EMAIL_RE.test(resetEmail())) return setResetError('邮箱格式不正确')
    if (resetCode().length !== 6) return setResetError('验证码为6位数字')
    if (resetPassword().length < 8) return setResetError('至少 8 位密码')
    if (resetConfirm() !== resetPassword()) return setResetError('两次密码不一致')
    setLoading(true)
    setResetError('')
    try {
      const data = await userApi.resetPassword(resetEmail().trim(), resetCode(), resetPassword())
      if (data?.ok === false) throw new Error(data.message || '密码重置失败')
      toastSuccess('密码已重置，请使用新密码登录')
      setActiveTab('login')
    } catch (error) {
      setResetError(error?.message || '密码重置失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    // Kobalte 2 alpha：Dialog 本身就是 Root 组件（Object.assign(DialogRoot, {...})），
    // 没有 .Root 属性；子件挂在 Dialog.Portal / .Overlay / .Content 等命名空间上。
    <Dialog open={auth.authDialogVisible()} onOpenChange={(open) => { if (!open) auth.closeAuthDialog() }}>
      <Dialog.Portal>
        <Dialog.Overlay class="ff-dialog-overlay" />
        <Dialog.Content class="ff-dialog-content" aria-label="登录与注册">
          <div class="ff-auth-heading">
            <div class="ff-auth-mark" aria-hidden="true">
              足
            </div>
            <div>
              <Dialog.Title class="ff-dialog-title">{activeTab() === 'login' ? t('auth.welcomeBack') : t('auth.joinTitle')}</Dialog.Title>
              <Dialog.Description class="ff-dialog-desc">
                {activeTab() === 'login' ? t('auth.loginSubtitle') : t('auth.registerSubtitle')}
              </Dialog.Description>
            </div>
            <Dialog.CloseButton class="ff-auth-close" aria-label={t('auth.closeDialog')}>
              ×
            </Dialog.CloseButton>
          </div>

          <div class="ff-tabs" role="tablist">
            <button type="button" role="tab" class={`ff-tab ${activeTab() === 'login' ? 'is-active' : ''}`} onClick={() => setActiveTab('login')}>
              {t('auth.loginTab')}
            </button>
            <button type="button" role="tab" class={`ff-tab ${activeTab() === 'register' ? 'is-active' : ''}`} onClick={() => setActiveTab('register')}>
              {t('auth.registerTab')}
            </button>
            <button type="button" role="tab" class={`ff-tab ${activeTab() === 'reset' ? 'is-active' : ''}`} onClick={() => setActiveTab('reset')}>
              {t('auth.resetTab')}
            </button>
          </div>

          <Show when={activeTab() === 'login'}>
            <form onSubmit={(e) => { e.preventDefault(); handleLogin() }}>
              <div class="ff-field">
                <label for="ff-login-account">{t('auth.account')}</label>
                <input id="ff-login-account" class="ff-input" value={loginAccount()} placeholder={t('auth.accountPlaceholder')} autocomplete="username" onInput={(e) => setLoginAccount(e.currentTarget.value)} />
              </div>
              <Show when={auth.loginChallenge()}>
                <div class="ff-field">
                  <label>{t('auth.captchaQuestion')}</label>
                  <div class="ff-captcha-row">
                    <span class="ff-captcha-question">{auth.loginChallenge()?.captchaQuestion}</span>
                    <input class="ff-input" value={loginCaptchaAnswer()} placeholder={t('auth.captchaAnswerPlaceholder')} onInput={(e) => setLoginCaptchaAnswer(e.currentTarget.value)} />
                  </div>
                </div>
              </Show>
              <div class="ff-field">
                <label for="ff-login-password">{t('auth.password')}</label>
                <input id="ff-login-password" class="ff-input" type="password" value={loginPassword()} placeholder={t('auth.passwordPlaceholder')} autocomplete="current-password" onInput={(e) => setLoginPassword(e.currentTarget.value)} />
              </div>
              <Show when={loginError()}>
                <p class="ff-field-error" style="margin-bottom:10px">{loginError()}</p>
              </Show>
              <button type="submit" class="ff-btn ff-btn-primary ff-btn-block" disabled={loading()}>
                {loading() ? t('common.loading') : t('auth.login')}
              </button>
              <button type="button" class="ff-aux-link" onClick={() => setActiveTab('reset')}>
                {t('auth.forgotPassword')}
              </button>
            </form>
          </Show>

          <Show when={activeTab() === 'register'}>
            <ul class="ff-register-perks" aria-label="注册可获得的能力">
              {t('auth.registerPerks').map((perk) => (
                <li>{perk}</li>
              ))}
            </ul>
            <p class="ff-register-why">{t('auth.registerWhy')}</p>
            <form onSubmit={(e) => { e.preventDefault(); handleRegister() }}>
              <div class="ff-field">
                <label for="ff-reg-email">{t('auth.email')}</label>
                <input id="ff-reg-email" class="ff-input" type="email" value={regEmail()} placeholder={t('auth.emailPlaceholder')} autocomplete="email" onInput={(e) => setRegEmail(e.currentTarget.value)} />
              </div>
              <div class="ff-field">
                <label for="ff-reg-nickname">{t('auth.nickname')}</label>
                <input id="ff-reg-nickname" class="ff-input" value={regNickname()} placeholder={t('auth.nicknamePlaceholder')} autocomplete="nickname" onInput={(e) => setRegNickname(e.currentTarget.value)} />
              </div>
              <div class="ff-field">
                <label>{t('auth.captcha')}</label>
                <Show when={regCaptchaVerified()} fallback={<ImageCaptcha image={regCaptchaImage()} answer={regCaptchaAnswer()} onAnswerChange={setRegCaptchaAnswer} onRefresh={refreshRegisterCaptcha} onSubmit={handleRegister} />}>
                  <div class="ff-captcha-verified">
                    <span aria-hidden="true">✓</span> {t('auth.captchaVerified')}
                  </div>
                </Show>
              </div>
              <div class="ff-field">
                <label for="ff-reg-code">{t('auth.emailCode')}</label>
                <div class="ff-code-row">
                  <input id="ff-reg-code" class="ff-input" value={regCode()} placeholder={t('auth.emailCodePlaceholder')} inputmode="numeric" maxlength="6" onInput={(e) => setRegCode(e.currentTarget.value)} />
                  <button type="button" class="ff-btn ff-btn-ghost" disabled={codeCountdown() > 0} onClick={sendRegisterCode}>
                    {codeCountdown() > 0 ? t('auth.resendIn', { seconds: codeCountdown() }) : t('auth.sendCode')}
                  </button>
                </div>
              </div>
              <div class="ff-field">
                <label for="ff-reg-password">{t('auth.password')}</label>
                <input id="ff-reg-password" class="ff-input" type="password" value={regPassword()} placeholder={t('auth.passwordMinPlaceholder')} autocomplete="new-password" onInput={(e) => setRegPassword(e.currentTarget.value)} />
              </div>
              <div class="ff-field">
                <label for="ff-reg-confirm">{t('auth.confirmPassword')}</label>
                <input id="ff-reg-confirm" class="ff-input" type="password" value={regConfirm()} placeholder={t('auth.confirmPasswordPlaceholder')} autocomplete="new-password" onInput={(e) => setRegConfirm(e.currentTarget.value)} />
              </div>
              <Show when={regError()}>
                <p class="ff-field-error" style="margin-bottom:10px">{regError()}</p>
              </Show>
              <button type="submit" class="ff-btn ff-btn-primary ff-btn-block" disabled={loading()}>
                {loading() ? t('common.loading') : t('auth.register')}
              </button>
              <p class="ff-terms-note">
                注册即表示你同意阅读并遵守{' '}
                <a href="/privacy" onClick={() => auth.closeAuthDialog()}>
                  隐私与数据说明
                </a>
                。
              </p>
            </form>
          </Show>

          <Show when={activeTab() === 'reset'}>
            <form onSubmit={(e) => { e.preventDefault(); handleReset() }}>
              <div class="ff-field">
                <label for="ff-reset-email">{t('auth.email')}</label>
                <input id="ff-reset-email" class="ff-input" type="email" value={resetEmail()} placeholder={t('auth.emailPlaceholder')} autocomplete="email" onInput={(e) => setResetEmail(e.currentTarget.value)} />
              </div>
              <div class="ff-field">
                <label for="ff-reset-code">{t('auth.emailCode')}</label>
                <div class="ff-code-row">
                  <input id="ff-reset-code" class="ff-input" value={resetCode()} placeholder={t('auth.emailCodePlaceholder')} inputmode="numeric" maxlength="6" onInput={(e) => setResetCode(e.currentTarget.value)} />
                  <button type="button" class="ff-btn ff-btn-ghost" disabled={resetCodeCountdown() > 0} onClick={sendResetCode}>
                    {resetCodeCountdown() > 0 ? t('auth.resendIn', { seconds: resetCodeCountdown() }) : t('auth.sendCode')}
                  </button>
                </div>
              </div>
              <div class="ff-field">
                <label for="ff-reset-password">{t('auth.newPassword')}</label>
                <input id="ff-reset-password" class="ff-input" type="password" value={resetPassword()} placeholder={t('auth.newPasswordPlaceholder')} autocomplete="new-password" onInput={(e) => setResetPassword(e.currentTarget.value)} />
              </div>
              <div class="ff-field">
                <label for="ff-reset-confirm">{t('auth.confirmNewPassword')}</label>
                <input id="ff-reset-confirm" class="ff-input" type="password" value={resetConfirm()} placeholder={t('auth.confirmNewPasswordPlaceholder')} autocomplete="new-password" onInput={(e) => setResetConfirm(e.currentTarget.value)} />
              </div>
              <Show when={resetError()}>
                <p class="ff-field-error" style="margin-bottom:10px">{resetError()}</p>
              </Show>
              <button type="submit" class="ff-btn ff-btn-primary ff-btn-block" disabled={loading()}>
                {loading() ? t('common.loading') : t('auth.resetPassword')}
              </button>
              <button type="button" class="ff-aux-link" onClick={() => setActiveTab('login')}>
                {t('auth.backToLogin')}
              </button>
            </form>
          </Show>

          <button type="button" class="ff-guest-link" onClick={() => auth.closeAuthDialog()}>
            {t('auth.guest')}
          </button>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog>
  )
}
