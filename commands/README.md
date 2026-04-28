# SMMWorld task queue

One file per task. Each file is self-contained — paste the whole thing into a fresh Claude Code session and it has all the context it needs.

Filenames are numbered roughly in the order they should be picked up. The numbers are loose — pick whichever one is unblocked.

## Shared context block

Every task already includes the project context inline. If you skip the context block in a fresh session you'll get vague answers, so don't.

## Status

All facts in these files were verified through one of:
- Direct file reads in the repo (paths quoted)
- Playwright automation against `https://smmworld.vip/` while logged in as `admin / Admin@2025!Secure` and `mmknshnk / master1860`
- API curl against prod (`/api/v2/admin/*`, `/api/v1/*`)
- `psql` against `smm_postgres` on prod (where stated)
- Direct git log inspection

If a task says "currently shows X" it means I literally saw X on the deployed site or in the response body. Nothing in here is invented.
