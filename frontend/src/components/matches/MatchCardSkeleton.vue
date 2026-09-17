<template>
  <div class="match-card-skeleton" role="presentation">
    <div class="skeleton-info">
      <div class="skeleton-team-row">
        <span class="sk sk-logo" />
        <span class="sk sk-name" />
        <span class="sk sk-score" />
        <span class="sk sk-name" />
        <span class="sk sk-logo" />
      </div>
      <div class="skeleton-meta">
        <span class="sk sk-chip" />
        <span class="sk sk-chip short" />
      </div>
    </div>
    <div class="skeleton-actions">
      <span class="sk sk-btn" />
      <span class="sk sk-btn narrow" />
      <span class="sk sk-circle" />
      <span class="sk sk-circle" />
    </div>
  </div>
</template>

<style scoped>
.match-card-skeleton {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-md, 12px);
  background: var(--ff-surface);
  min-width: 0;
  min-height: 132px;
}
.skeleton-team-row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 52px minmax(0, 1fr) 28px;
  gap: 8px;
  align-items: center;
}
.skeleton-meta, .skeleton-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.sk {
  display: block;
  border-radius: 8px;
  background: linear-gradient(90deg, var(--ff-surface-soft) 0%, #eef3f0 45%, var(--ff-surface-soft) 100%);
  background-size: 200% 100%;
  animation: sk-shimmer 1.2s ease-in-out infinite;
}
.sk-logo, .sk-circle { width: 28px; height: 28px; border-radius: 999px; }
.sk-name { height: 14px; width: 100%; }
.sk-score { height: 14px; width: 100%; border-radius: 6px; }
.sk-chip { height: 18px; width: 72px; border-radius: 999px; }
.sk-chip.short { width: 48px; }
.sk-btn { height: 28px; width: 88px; border-radius: 8px; }
.sk-btn.narrow { width: 72px; }
@keyframes sk-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}
@media (max-width: 680px) {
  .match-card-skeleton {
    min-height: 148px;
    padding: 12px;
    gap: 10px;
  }
  .skeleton-team-row {
    grid-template-columns: 24px minmax(0, 1fr) 40px minmax(0, 1fr) 24px;
    gap: 6px;
  }
  .sk-logo, .sk-circle { width: 24px; height: 24px; }
  .skeleton-actions { gap: 6px; }
  .sk-btn { width: 76px; height: 26px; }
  .sk-btn.narrow { width: 64px; }
}
@media (prefers-reduced-motion: reduce) {
  .sk { animation: none; }
}
</style>
