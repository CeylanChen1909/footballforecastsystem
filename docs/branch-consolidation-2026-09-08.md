# Branch consolidation (2026-09-08)

Local server repo: `/root/footballforecastsystem`

| Branch | Tip | Included in `fix/next-round-opt`? |
|--------|-----|--------------------------------------|
| `fix/mobile-matches-overflow` | `a9ed166` | yes (ancestor) |
| `feat/seo-share-meta` | `86f0092` | yes (ancestor) |
| `fix/p1-p2-ux` | `2def9b3` | yes (ancestor) |
| `fix/next-round-opt` | (see `git log -1`) | current working branch |
| `production` | `f3a6006` (+ local ops commit ahead of remote historically) | **left untouched** |

## Safer merge (recommended)

Do **not** reset or force-push `production`. Create an integration branch from next-round:

```bash
cd /root/footballforecastsystem
git checkout fix/next-round-opt
git branch integration/next   # or: git checkout -b integration/2026-09-08
# When ready to promote:
# git checkout production && git merge --no-ff integration/next
```

`integration/next` (or `integration/2026-09-08`) fast-forwards from `fix/next-round-opt` and leaves `production` unchanged until an explicit deploy merge.
