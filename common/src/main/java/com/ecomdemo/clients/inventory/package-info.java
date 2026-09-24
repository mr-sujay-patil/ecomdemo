/**
 * The client for inventory-service.
 *
 * <p>{@code @NamedInterface} rather than {@code @ApplicationModule}: this is a sub-package of the
 * {@code clients} module, and the annotation is what exposes {@code InventoryGateway} to the
 * modules that call it instead of leaving it internal.
 */
@org.springframework.modulith.NamedInterface("inventory")
package com.ecomdemo.clients.inventory;
