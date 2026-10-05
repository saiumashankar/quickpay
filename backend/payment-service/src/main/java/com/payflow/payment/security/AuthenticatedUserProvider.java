package com.payflow.payment.security;

import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.exception.PaymentForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserProvider {

    public GatewayUserContext currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new PaymentForbiddenException("No authenticated user in the security context");
        }
        return fromToken(jwtAuthentication);
    }

    private GatewayUserContext fromToken(JwtAuthenticationToken jwtAuthentication) {
        Long userId = readUserId(jwtAuthentication);
        String ownerId = jwtAuthentication.getToken().getClaimAsString("ownerId");
        String role = jwtAuthentication.getToken().getClaimAsString("role");
        String email = jwtAuthentication.getToken().getSubject();
        String handle = jwtAuthentication.getToken().getClaimAsString("handle");
        return handle == null || handle.isBlank()
                ? GatewayUserContext.withoutHandle(userId, ownerId, role, email)
                : new GatewayUserContext(userId, ownerId, role, email, handle);
    }

    private Long readUserId(JwtAuthenticationToken jwtAuthentication) {
        Object claim = jwtAuthentication.getToken().getClaims().get("userId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        if (claim != null) {
            try {
                return Long.valueOf(claim.toString());
            } catch (NumberFormatException ignored) {
                // fall through to the explicit failure below
            }
        }
        throw new PaymentForbiddenException("Token is missing a valid userId claim");
    }
}
