package com.ecomdemo.batch;

import java.math.BigDecimal;

/**
 * The header figures of one day's sales report: how many orders, and how much they came to.
 *
 * @param orders number of orders placed that day
 * @param revenue their total, which is zero rather than null on a day with no orders
 */
public record DailySales(long orders, BigDecimal revenue) {
}
