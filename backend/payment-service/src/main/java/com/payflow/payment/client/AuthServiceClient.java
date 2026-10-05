package com.payflow.payment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Resolves a handle to the owner it belongs to.
 *
 * Called by the registered service name, never an address, so Eureka resolves
 * it and a second auth-service instance would be load balanced.
 *
 * There is no user token to forward. The call happens while a user is signed in
 * but on behalf of the transfer, not on behalf of the user's session, and
 * putting a bearer token on an internal hop means it is valid for another
 * audience than it was issued for. The shared internal token is the same
 * mechanism notification-service already uses for the same reason.
 */
@FeignClient(name = "auth-service")
public interface AuthServiceClient {

    /**
     * Feign raises {@code FeignException.NotFound} for the 404 that means "no
     * such handle". That is caught by {@code HandleResolver} and reported as an
     * unknown handle rather than as a failure, so it must not be left to
     * propagate: an unknown handle is a normal thing for a person to type, not
     * an outage that should trip a circuit breaker for every other user.
     */
    @GetMapping("/api/internal/users/by-handle/{handle}")
    HandleOwner findByHandle(@PathVariable("handle") String handle,
                            @RequestHeader("X-Internal-Token") String internalToken);
}
