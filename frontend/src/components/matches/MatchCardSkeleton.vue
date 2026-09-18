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
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid var(--ff-border);
  border-radius: 0;
  background: #fff;
  min-width: 0;
  min-height: 56px;
}
.skeleton-team-row {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 48px minmax(0, 1fr) 22px;
  gap: 8px;
  align-items: center;
}
.skeleton-meta { display: none; }
.skeleton-actions { display: flex; gap: 6px; justify-content: flex-end; }
.sk {
  display: block;
  border-radius: 3px;
  background: #e7eeea;
}
.sk-logo, .sk-circle { width: 22px; height: 22px; border-radius: 2px; }
.sk-name { height: 12px; width: 100%; }
.sk-score { height: 14px; width: 100%; }
.sk-chip { display: none; }
.sk-btn { height: 28px; width: 64px; }
.sk-btn.narrow { width: 28px; }
@media (max-width: 720px) {
  .match-card-skeleton { grid-template-columns: 1fr; min-height: 72px; }
  .skeleton-actions { justify-content: flex-start; }
}
</style>
