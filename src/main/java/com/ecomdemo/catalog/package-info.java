/**
 * What a product is: its name, description, price and category.
 *
 * <p>Paired with {@code inventory}, which owns how many of it there are. The two share the
 * {@code product} table and the {@link com.ecomdemo.catalog.Product} entity deliberately - the
 * split is of BEHAVIOUR, not of schema - and {@code StockMutationRulesTest} is what keeps that
 * split real, since the compiler cannot.
 *
 * <p>{@code dto} is a named interface rather than an internal package: those records are the
 * shape of a product as every other module and every HTTP client sees it. The entity beside them
 * is exposed too, because inventory and the cart hold real instances of it; the repository and
 * the controller are not.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Catalog",
        allowedDependencies = {"inventory", "shared"})
package com.ecomdemo.catalog;
