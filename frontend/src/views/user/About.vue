<template>
  <div class="legal-page ff-page-shell">
    <AppTopNav title="ChenFootball" subtitle="" :brand-icon="Football" active-path="/about" />
    <main id="app-main" class="legal-main" tabindex="-1">
      <header class="about-hero">
        <p class="eyebrow">{{ t('about.eyebrow') }}</p>
        <p class="one-liner">{{ t('about.oneLiner') }}</p>
        <router-link class="hero-cta" to="/matches">{{ t('about.cta') }}</router-link>
      </header>

      <section class="product-brief" :aria-label="t('about.overviewAria')">
        <div class="brief-cols">
          <div class="brief-col">
            <h3>{{ t('about.whatWeDo') }}</h3>
            <ul>
              <li>{{ t('about.doFixturesPrefix') }}<router-link to="/matches">{{ t('about.fixturesLink') }}</router-link>{{ t('about.doFixturesSuffix') }}</li>
              <li>{{ t('about.doProbabilities') }}</li>
              <li>{{ t('about.doReview') }}</li>
              <li>{{ t('about.doFavorites') }}</li>
            </ul>
          </div>
          <div class="brief-col is-boundary">
            <h3>{{ t('about.whatWeDontDo') }}</h3>
            <ul>
              <li><strong>{{ t('about.dontBetting') }}</strong></li>
              <li>{{ t('about.dontGuarantee') }}</li>
              <li>{{ t('about.dontIllegal') }}</li>
            </ul>
          </div>
        </div>
      </section>

      <dl class="trust-facts trust-grid" :aria-label="t('about.trustAria')">
        <div>
          <dt>{{ t('about.trustFixturesTitle') }}</dt>
          <dd>{{ t('about.trustFixturesBody') }}</dd>
        </div>
        <div>
          <dt>{{ t('about.trustPredictionsTitle') }}</dt>
          <dd>{{ t('about.trustPredictionsBody') }}</dd>
        </div>
        <div>
          <dt>{{ t('about.trustPrivacyTitle') }}</dt>
          <dd>{{ t('about.trustPrivacyBody') }}</dd>
        </div>
      </dl>

      <section class="about-disclaimer" :aria-label="t('about.disclaimerAria')">
        <h2>{{ t('about.disclaimerTitle') }}</h2>
        <p>{{ t('about.disclaimerBody') }}</p>
      </section>

      <section class="about-contact" :aria-label="t('about.contactAria')">
        <h2>{{ t('about.contactTitle') }}</h2>
        <p>{{ t('about.contactChannels') }}{{ t('about.contactAccountPrefix') }}<router-link to="/profile">{{ t('common.profile') }}</router-link>{{ t('about.contactAccountSuffix') }}<router-link to="/agent">{{ t('common.agent') }}</router-link>{{ t('about.contactAgentSuffix') }}<router-link to="/terms">{{ t('common.terms') }}</router-link>{{ t('about.contactAnd') }}<router-link to="/privacy">{{ t('common.privacy') }}</router-link>{{ t('about.contactEnd') }}</p>
      </section>

      <div class="legal-actions">
        <router-link class="action-primary" to="/matches">{{ t('about.cta') }}</router-link>
        <router-link class="action-ghost" to="/privacy">{{ t('common.privacy') }}</router-link>
        <router-link class="action-ghost" to="/terms">{{ t('common.terms') }}</router-link>
      </div>
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import AppFooter from '../../components/layout/AppFooter.vue'
import AppTopNav from '../../components/layout/AppTopNav.vue'
import { Football } from '@element-plus/icons-vue'
import { useI18n } from '../../i18n'

const { t } = useI18n()
</script>

<style scoped>
.legal-main {
  max-width: 760px;
  margin: 0 auto;
  padding: 36px 24px 56px;
  color: var(--ff-text);
  line-height: 1.75;
}
.about-hero { margin: 0 0 22px; }
.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--ff-primary);
}
.one-liner {
  margin: 0 0 16px;
  color: var(--ff-text-strong);
  font-size: 20px;
  font-weight: 650;
  line-height: 1.45;
  max-width: 36em;
}
.hero-cta {
  display: inline-flex;
  align-items: center;
  min-height: 40px;
  padding: 0 16px;
  border-radius: 6px;
  background: var(--ff-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 650;
  text-decoration: none;
}
.hero-cta:hover { background: var(--ff-primary-hover); }
.hero-cta:focus-visible { outline: 2px solid var(--ff-primary); outline-offset: 2px; }

.product-brief {
  margin: 0 0 8px;
  padding: 18px 0 4px;
  border-top: 1px solid var(--ff-border);
}
.brief-cols {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 28px;
}
.brief-col h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--ff-text-strong);
}
.brief-col.is-boundary h3 { color: var(--ff-text); }
.brief-cols ul {
  margin: 0;
  padding-left: 1.05em;
  color: var(--ff-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.brief-cols li { margin: 5px 0; }
.brief-cols a {
  color: var(--ff-primary);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.legal-main h2 {
  margin: 28px 0 8px;
  color: var(--ff-text-strong);
  font-size: 16px;
  scroll-margin-top: 84px;
}
.legal-main p { color: var(--ff-text-muted); margin: 0; }
.about-disclaimer p,
.about-contact p { font-size: 13px; line-height: 1.65; }
.about-contact a {
  color: var(--ff-primary);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.trust-facts { margin: 18px 0 4px; border-top: 1px solid var(--ff-border); }
.trust-facts > div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid var(--ff-border);
}
.trust-facts dt { margin: 0; color: var(--ff-text-strong); font-size: 13px; font-weight: 700; }
.trust-facts dd { margin: 0; color: var(--ff-text-muted); font-size: 13px; line-height: 1.55; }

.legal-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 28px;
}
.action-primary,
.action-ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
  padding: 0 14px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}
.action-primary { background: var(--ff-primary); color: #fff; }
.action-primary:hover { background: var(--ff-primary-hover); }
.action-ghost {
  border: 1px solid var(--ff-border);
  color: var(--ff-text);
  background: #fff;
}
.action-ghost:hover { border-color: var(--ff-primary); color: var(--ff-primary); }

@media (max-width: 680px) {
  .legal-main { padding: 24px 16px 40px; }
  .one-liner { font-size: 18px; }
  .brief-cols { grid-template-columns: 1fr; gap: 18px; }
  .trust-facts > div { grid-template-columns: 1fr; gap: 2px; }
}
</style>
