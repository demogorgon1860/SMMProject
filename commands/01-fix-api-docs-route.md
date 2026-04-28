# Task 01 — Fix `/api-docs` route (Spring intercepts before SPA loads)

## Context

(See `_CONTEXT.md` for repo + prod + creds.)

## What's wrong

Verified through Playwright + curl against `https://smmworld.vip/api-docs`:

```
$ curl -s https://smmworld.vip/api-docs
{"error":"Unauthorized"}
```

The page returns raw JSON instead of the React docs page. Spring (SpringDoc / OpenAPI) is binding `/api-docs` as a backend endpoint and the request never reaches the SPA fallback in nginx.

The React route exists and the bundle is correct:
- `frontend/src/App.tsx` line ~100: `<Route path="api-docs" element={<ApiDocsPage />} />`
- `frontend/src/pages/public/ApiDocs.tsx` is a full 3-column docs page (TOC scroll-spy, sandbox aside)
- The compiled bundle on prod (`/assets/index-*.js`) does include `ApiDocsPage` — it's just unreachable

This is the most visible P0 from the audit: the public API documentation is literally returning a 401 to anyone who clicks the API link from the nav.

## What to do

Pick **one** of these. Option A is preferred — less risk than messing with SpringDoc paths.

### Option A — rename the SPA route to `/docs`

1. `frontend/src/App.tsx` — change `<Route path="api-docs">` to `<Route path="docs">`.
2. Search the entire `frontend/src/` for `'/api-docs'` and `'api-docs'` (string literal). Replace each with `/docs`. Known call sites:
   - `frontend/src/components/shells/PublicShell.tsx` — top nav menu item "API"
   - `frontend/src/components/shells/AppShell.tsx` — sidebar item "API" (in `NAV` array)
   - `frontend/src/pages/public/Landing.tsx` — "Read API docs" button on the CTA card
   - `frontend/src/pages/public/PublicShell.tsx` — footer "API" link
3. The page itself displays absolute curl examples — leave those as `https://smmworld.vip/api/v2/...`, those are real backend paths and have nothing to do with the SPA route.

### Option B — move SpringDoc off `/api-docs`

1. `backend/src/main/resources/application.yml` — add or update:
   ```yaml
   springdoc:
     api-docs:
       path: /openapi/v3
     swagger-ui:
       path: /openapi/swagger-ui.html
   ```
2. SSH to the prod server, check `C:\SMMPanel\nginx.conf`. Confirm there's a `try_files $uri /index.html` (or equivalent SPA fallback) and that `/api-docs` is NOT explicitly forwarded to the backend `proxy_pass`.
3. If nginx has `location /api/ { proxy_pass http://backend; }`, that's fine — `/api-docs` doesn't match `/api/`. But check anyway.

## Verify after deploy

1. `curl -I https://smmworld.vip/<chosen-path>` returns `200` with `content-type: text/html`.
2. Open the path in a browser — the 3-column React docs page renders, with TOC scroll-spy on the left.
3. No console errors.
4. Internal nav links (footer, sidebar) all point at the new path — search `frontend/dist/` post-build for `/api-docs` to confirm it's gone (option A) or still works (option B).
5. Nothing else regressed: Landing CTA, Help page footer, etc., all click through correctly.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices** — no shortcuts, no dead constants left behind, no commented-out routes. If you choose Option A, also update memory if there's a saved fact about `/api-docs` being the docs path. Commit with a clear single-line subject and a body explaining which option you picked and why.
