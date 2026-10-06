# Phase 34 Test Report: Product Images

- **Date:** 2026-10-06
- **Branch:** `feature/phase-34-product-images`
- **Machine:** the WSL2 workstation. Claude Code produced every local result below on that machine. CI
  results are GitHub Actions runs on the PR.
- **Requested by:** the web team (their KI-002). Approved by the user 2026-10-06: yes, product images, seed-only.
- **Result:** ✅ green.
  - `./mvnw clean verify`: BUILD SUCCESS, **724 tests** (542 unit, 182 integration), 0 failed, 0 skipped.
    Up from 701.
  - Compose, cold (`down -v`, `up --build --wait`, `.smoke-state` removed): smoke **480 / 0 / 0**
    (12 new checks). Second run on the same stack, volumes kept: **482 / 0 / 0**.
  - Kubernetes (kind, `scripts/k8s-up.sh` with images rebuilt, all Deployments restarted): smoke
    **436 / 0 / 7**. The 7 skips are the known ones (observability stays in compose, and the AI
    generation checks).

## 1. Done when

| Claim | Proven by |
|---|---|
| The list, one product and search hits carry `imageUrl` | `ProductResponseTest`; `ProductApiIT.listCarriesImageUrl` and `seededProductImageIsServed`; smoke "every product in the list still has its old fields and now an imageUrl key". Search hits embed `ProductResponse` (`ProductSearchHit.product`), so they carry it by type |
| A seeded product with an image returns its URL, one without returns `null` | `ProductApiIT.listCarriesImageUrl` (some of each); `createdProductHasNoImage`; seed: products 9 and 10 have none |
| Fetched anonymously through the gateway, the URL returns the image with the documented headers | Smoke (anonymous `curl` through :8080, and through the Ingress on kind): 200, `image/svg+xml`, `nosniff`, `Cache-Control: public, max-age=86400`, `Cross-Origin-Resource-Policy: cross-origin`, `ETag`. `EdgeSecurityIT.productImagesArePublicToRead` (anonymous read passes the edge, a customer's write is 403). `ProductImageControllerTest` for the headers in the slice |
| An unknown id and an imageless product return 404 | `ProductImageControllerTest.notFound`, `ProductApiIT.createdProductHasNoImage`, `unknownProductAndNoToken`; smoke (both cases) |
| `If-None-Match` returns 304 | `ProductImageControllerTest.conditionalRequest`, `ProductApiIT.seededProductImageIsServed`; smoke |

Also proven: a stored name that is a path or outside the allow-list is refused (`ProductImageServiceTest`),
every shipped file is small, allow-listed and inert (`ProductImageFilesTest`), and catalog-service itself
still refuses a tokenless request (`ProductImageControllerTest.needsAToken`, `ProductApiIT.unknownProductAndNoToken`).

## 2. Evidence (compose, through the gateway)

    GET /api/products/1/image (no Authorization)
    HTTP/1.1 200 OK
    Cache-Control: max-age=86400, public
    X-Content-Type-Options: nosniff
    Cross-Origin-Resource-Policy: cross-origin
    Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox
    ETag: "9fc17ae00989d5de97a1591173099467d116e997e3d45f86464f60affaf646f1"
    Content-Type: image/svg+xml
    Content-Length: 2127

`GET /api/products` returns `imageUrl` `/api/products/{1..8}/image` for eight products and `null` for ids 9
and 10. The live catalog OpenAPI document (`/v3/api-docs/catalog` through the gateway) describes
`ProductResponse.imageUrl` as `string | null` and the endpoint's `200`, `304` and `404` responses. There is
no committed OpenAPI snapshot in this repository to refresh.

## 3. Notes

- catalog-service is an internal API, so its image path needs a token like every other path; "anonymous" is the
  gateway's behaviour (it adds its `catalog:read` token). No gateway or security rule changed; the tests and
  smoke prove it.
- A cache written before the deploy has no `imageUrl` for at most 10 minutes (product) or 2 minutes (list).
  Not seen on a cold stack. Recorded in `docs/decisions.md`.
- First version of the smoke section failed to compare exact header values because the dumped headers carry
  `\r`; fixed (`sed -i 's/\r$//'`) before the first full run. The runs above are of the fixed script.
- `mvnw.cmd` still shows as modified in the working tree (KI-045, from the Dependabot Maven bump); it is not part
  of this PR.

## 4. Manual verification

None needed. To look at the pictures: `docker compose up --build`, then open
`http://localhost:8080/api/products/1/image` in a browser.
