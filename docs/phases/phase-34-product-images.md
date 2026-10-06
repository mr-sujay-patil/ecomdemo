# Phase 34: Product Images

| | |
|---|---|
| **Stage** | Stage 9: Production Hardening |
| **Technology** | Static binary assets over HTTP: content types, ETag and Cache-Control, anonymous `<img>` access |
| **Branch** | `feature/phase-34-product-images` |
| **PR title** | `Phase 34: Product Images` |
| **Requires** | `phase-33-complete` tag exists on `main` |
| **Completion tag** | `phase-34-complete` |

> Claude Code: implement **only** this file's scope. Follow `docs/process/execution-protocol.md` for the lifecycle, `docs/process/testing-protocol.md` before raising the PR, and `docs/process/git-workflow.md` for Git. Requested by the web team (their KI-002) and approved by the user on 2026-10-06: **yes, product images, seed-only**.

**Technology:** Serving static binary assets from a service, with HTTP caching, over an anonymous route

**Goal:** A browser `<img>` can show a product's picture, through the gateway, with no `Authorization` header, and the catalogue API says where to find it.

**What you'll implement**
- **Contract (additive only):** `ProductResponse` gains `imageUrl` (string or null). Because `ProductSearchHit.product` is a `ProductResponse`, `ProductSearchResponse.results[].product.imageUrl` follows with no second change. Nothing is renamed, removed or retyped. The value is a **path relative to the gateway origin**, `/api/products/{id}/image` (not an absolute URL, so it survives any host: compose, the kind Ingress, a deployed one). It is `null` when the product has no image, which must stay valid.
- **Storage (seed-only):** Flyway `V5` in catalog-service adds a nullable `product.image_file` column and sets it for the seeded products (not all: some stay `null` on purpose, so the null path is exercised by real data). The files ship inside catalog-service as classpath resources (`product-images/`). There is no upload endpoint and no write API for images; `ProductRequest` does not change. Admin upload is a follow-up, not this phase.
- **Seed images:** small SVG illustrations written for this repo by a script (`scripts/`), not photos, not hotlinked, not third-party. Only the files named in `image_file` can be served; the id is looked up, the client never supplies a path (no path traversal).
- **Serving:** `GET /api/products/{id}/image` in catalog-service. 200 with the right `Content-Type`; 404 (the standard error body) for an unknown product or a product with no image. Allowed formats: `svg`, `png`, `webp`, `jpeg` (an allow-list mapping extension to media type; anything else is refused at startup/test time). No variants: SVG scales, so there is no thumbnail. A maximum size of 256 KiB per file, checked by a test over the shipped files.
- **Headers:** `Cache-Control: public, max-age=86400`, a strong `ETag` and `304` on `If-None-Match`, `X-Content-Type-Options: nosniff`, `Cross-Origin-Resource-Policy: cross-origin` (so a storefront on another origin may embed it; `<img>` needs no CORS), and a restrictive `Content-Security-Policy` on SVG responses.
- **Access:** anonymous through the gateway. The gateway already permits `GET /api/products/**` and adds the service token catalog-service accepts; the phase proves it with a test, not by assumption. Writes stay ADMIN-only.
- **Docs:** the live OpenAPI documents (`/v3/api-docs` of catalog-service and the gateway's aggregate) describe `imageUrl` and the image endpoint; `docs/` (README, `docs/architecture` if it lists endpoints) and `docs/security.md` (the endpoint is public by design, and why that is safe) are updated. The web team is told the tag.

**Concepts to understand**
- Why an API returns a *path* to a binary and does not embed it (JSON size, caching, `<img>` cannot send a bearer token)
- `ETag`, `If-None-Match`, `Cache-Control`, and what a `304` saves
- Why serving user-influenced files needs `nosniff`, a content-type allow-list, and no client-supplied paths
- CORS vs `Cross-Origin-Resource-Policy`: which one an `<img>` is subject to
- Additive API change: why a nullable new field is not a breaking one

**Done when**
- `GET /api/products` and `GET /api/products/{id}` and the search results carry `imageUrl`; a seeded product with an image returns it, one without returns `null`; the URL, fetched anonymously through the gateway, returns the image with the documented headers; an unknown id and an imageless product return 404; a repeated request with `If-None-Match` returns 304. Each is proven by an automated test.

## Smoke test additions (`scripts/smoke-test.sh`)

- A seeded product's `imageUrl` is fetched through the gateway with no `Authorization` header: 200, an `image/*` content type, `nosniff`, `Cache-Control`, `ETag`.
- The same request with `If-None-Match` is a 304.
- A product with `imageUrl` null answers 404 on `/image`; the list response still validates.
- No existing field of `ProductResponse` changed (the check for `id`, `name`, `description`, `price`, `stockQuantity`, `category` stays).

## Your manual steps (user)

None. Only review, learn, merge, and reply `merged, continue`. Then tell the web team the tag (`phase-34-complete`) and that `imageUrl` is a gateway-relative path.
