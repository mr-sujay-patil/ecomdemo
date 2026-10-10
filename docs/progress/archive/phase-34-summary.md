## Phase 34: Product Images (tag: phase-34-complete, PR #59)
**What exists now:** `ProductResponse.imageUrl` (nullable, additive; also in search hits via `ProductSearchHit.product`)
is the gateway-relative path `/api/products/{id}/image`. The endpoint is public through the gateway, with
ETag/304, `Cache-Control: public, max-age=86400`, nosniff, CORP cross-origin, a CSP on SVG. Seed-only: 8 of
the 10 seeded products have an SVG, products 9 and 10 have none; products created via the API have none.
**Key code:** catalog-service `Product.imageFile` (V5 `image_file`, no setter), `dto.ProductResponse.imageUrl`,
`ProductImageService` (plain-name + extension allow-list, classpath `product-images/`, SHA-256 ETag, cached),
`ProductImage` record, `internal.ProductImageController` (`WebRequest.checkNotModified`).
**Config & infrastructure:** none new. Images: `catalog-service/src/main/resources/product-images/`, written
by `scripts/generate-product-images.py`. No gateway or security-rule change: `GET /api/products/**` was already public.
**Tests:** 724 (542 unit, 182 IT). New: `ProductResponseTest`, `ProductImageServiceTest`, `ProductImageFilesTest`
(type, 256 KiB cap, inert SVG, V5 names only shipped files), `ProductImageControllerTest`; image tests in
`ProductApiIT` and `EdgeSecurityIT`. Smoke section "Product images" (12 checks): compose cold 480/0/0, kept volumes
482/0/0, kind 436/0/7.
**Gotchas:** catalog-service is internal: its image path needs a token, anonymous access is the gateway's
(tests say so). A new image file needs a V-migration naming it AND the extension on the allow-list. `ProductResponse`
is cached in Redis (product 10 min, list 2 min): after deploying onto a warm Redis, entries written before
this phase read `imageUrl` as null until they expire. Self-heals; a cold stack (`down -v`) never sees it.
**Follow-ups (not done):** admin upload of images (storage, size and content validation, cleanup); thumbnails or
`srcset` variants if raster images arrive; a versioned URL so caching could be `immutable`.
