/**
 * One cart per account, and the lines in it.
 *
 * <p>It holds real {@link com.ecomdemo.catalog.Product} instances rather than ids, so a line can
 * report the current price without a second lookup - which is also why it depends on the
 * catalogue at all. It reads stock but never writes it; that rule is enforced in
 * {@code StockMutationRulesTest}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Cart",
        allowedDependencies = {"clients :: catalog", "jwt", "shared"})
package com.ecomdemo.cart;
