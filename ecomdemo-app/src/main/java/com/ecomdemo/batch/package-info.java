/**
 * The CSV import and the nightly sales report.
 *
 * <p>It goes through {@code ProductService} and {@code InventoryService} rather than the
 * catalogue's repository, which is new in this phase: a job that writes stock is subject to the
 * same rule as a checkout that writes stock.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Batch",
        allowedDependencies = {"clients :: catalog", "clients :: inventory", "shared"})
package com.ecomdemo.batch;
