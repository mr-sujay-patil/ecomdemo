/**
 * The client for catalog-service.
 *
 * <p>{@code @NamedInterface} rather than {@code @ApplicationModule}, for the reason given on the
 * inventory client: a sub-package is internal to its module until it says it is not.
 */
@org.springframework.modulith.NamedInterface("catalog")
package com.ecomdemo.clients.catalog;
