/**
 * MatchCardSkeleton（自 Vue src/components/matches/MatchCardSkeleton.vue 移植，
 * 模板结构一字未动；样式见 styles/matches.css，含 2026 微光 shimmer）。
 */
export default function MatchCardSkeleton() {
  return (
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
  )
}
