/**
 * The consumer that turns an order event into a notification, exactly once.
 *
 * <p>It depends on {@code messaging} alone, and notably NOT on {@code order}. It never sees an
 * {@code Order}: everything it needs arrives in the event, which is the property that would let
 * it become a separate service in Phase 20 without carrying the ordering module with it. That is
 * what the hand-written event record bought.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {"messaging"})
package com.ecomdemo.notification;
