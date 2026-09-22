/**
 * The catalogue's published data contracts.
 *
 * <p><strong>A named interface, which is Modulith's way of publishing more than a module's root
 * package.</strong> By default a module exposes its top-level types and nothing else: every
 * sub-package is internal, so {@code product.dto} would be off-limits to the rest of the
 * application. That default is right for most sub-packages and wrong for this one — these records
 * are the shape of a product as every other module and every HTTP client sees it, which is the
 * definition of published.
 *
 * <p>The distinction it draws is the useful one. {@code Product}, the JPA entity, stays exposed at
 * the module root but carries {@code @Version} and its persistence mapping; these records carry
 * only what a caller is entitled to know. Declaring the DTO package public and leaving everything
 * else internal is a sharper statement than either "the whole module is open" or "nothing is".
 *
 * <p>What this legalises in practice is {@code CacheConfig}, which needs {@code ProductResponse}
 * as a type token to build a typed Redis serializer — the one place outside the catalogue that
 * must name this type. The deeper alternative was considered and deferred: the catalogue could
 * contribute its own cache configuration, so the cache module would never need to know that
 * products exist at all. That inverts a dependency rather than declaring it, and it is a change
 * to caching rather than to boundaries, so it is recorded in {@code docs/decisions.md} instead of
 * being smuggled into this phase.
 */
@org.springframework.modulith.NamedInterface("dto")
package com.ecomdemo.catalog.dto;
