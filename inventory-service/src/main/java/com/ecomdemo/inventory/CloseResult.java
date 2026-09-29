package com.ecomdemo.inventory;

/**
 * What closing an order did.
 *
 * @param released how many reservations were given back by THIS call; zero if there were none, or
 *     if an earlier call had already given them back
 * @param alreadyClosed whether the order was closed before this call
 */
public record CloseResult(int released, boolean alreadyClosed) {
}
