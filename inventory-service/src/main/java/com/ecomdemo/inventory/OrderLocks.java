package com.ecomdemo.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One lock per ORDER, held until the current transaction ends (Phase 32).
 *
 * <p>Reserving for an order and closing it must not interleave. Without a lock, this can happen:
 * the reservation checks "is the order closed?" (no), the close commits its fence and releases what
 * it can see (nothing yet, the reservation has not committed), and then the reservation commits -
 * stock held for a closed order, for ever. Row locks on {@code product_stock} do not help, because a
 * close for an order that never reserved has no product rows to lock.
 *
 * <p>A PostgreSQL advisory lock is a lock on a NUMBER rather than on a row, so it can be taken for
 * an order that has no rows anywhere. The {@code _xact} form is released by commit or rollback, so
 * it cannot leak. The key is the order id; nothing else in this database takes advisory locks.
 */
@Component
class OrderLocks {

    private final JdbcTemplate jdbc;

    OrderLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Blocks until this transaction holds the order's lock. Must be called inside a transaction. */
    void lock(Long orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> null, orderId);
    }
}
