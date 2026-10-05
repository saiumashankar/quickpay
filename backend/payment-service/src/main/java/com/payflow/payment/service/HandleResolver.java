package com.payflow.payment.service;

import com.payflow.payment.client.AuthServiceClient;
import com.payflow.payment.client.HandleOwner;
import com.payflow.payment.exception.PaymentBadRequestException;
import com.payflow.payment.exception.PaymentServiceUnavailableException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns what a person typed into the identity of a wallet that can be credited.
 *
 * auth-service owns the handle to user mapping and is the only place it exists,
 * so it is fetched rather than cached here. A cached copy would be a second
 * store of identity that could disagree with the first, and this lookup sits on
 * the critical path of every transfer, where being wrong means crediting the
 * wrong person.
 */
@Service
public class HandleResolver {

    private static final Logger log = LoggerFactory.getLogger(HandleResolver.class);

    private final AuthServiceClient authServiceClient;
    private final String internalToken;

    public HandleResolver(AuthServiceClient authServiceClient,
                          @Value("${payflow.internal.api-token:}") String internalToken) {
        this.authServiceClient = authServiceClient;
        this.internalToken = internalToken;
    }

    public HandleOwner resolve(String rawHandle) {
        String canonical = Handle.canonicalize(rawHandle)
                .orElseThrow(() -> new PaymentBadRequestException(
                        "Handle must be 3 to 30 characters, starting with a letter or digit, and contain only letters, digits, dot or underscore"));

        if (internalToken.isBlank()) {
            throw new PaymentServiceUnavailableException(
                    "This service cannot look up handles because no internal API token is configured");
        }

        HandleOwner owner;
        try {
            owner = authServiceClient.findByHandle(canonical, internalToken);
        } catch (FeignException.NotFound notFound) {
            // The person typed a handle that does not exist. That is a client
            // error about this transfer, not a broken dependency.
            throw new PaymentBadRequestException("No account found for @" + canonical);
        } catch (FeignException exception) {
            // Anything else means auth-service is unreachable or unhealthy. It
            // is reported as a dependency failure rather than as "no such
            // handle", because telling a user their friend does not exist when
            // the lookup simply failed is both wrong and alarming.
            log.error("Could not resolve handle @{}: {}", canonical, exception.toString());
            throw new PaymentServiceUnavailableException(
                    "Could not verify the recipient right now, please try again");
        }

        if (owner == null || owner.ownerId() == null) {
            throw new PaymentBadRequestException("No account found for @" + canonical);
        }
        return owner;
    }
}
