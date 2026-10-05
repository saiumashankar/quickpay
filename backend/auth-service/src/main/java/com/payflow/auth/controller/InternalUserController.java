package com.payflow.auth.controller;

import com.payflow.auth.dto.NotificationPreferencesResponse;
import com.payflow.auth.dto.UserHandleResponse;
import com.payflow.auth.entity.User;
import com.payflow.auth.repository.UserRepository;
import com.payflow.auth.service.HandleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service surface. Not reachable with a user JWT: it is guarded by
 * InternalTokenFilter, which this path must also be permitted for in the
 * security chain. Callers identify the user by ownerId, which is the only
 * stable identifier that appears on a payment event.
 */
@RestController
@RequestMapping("/api/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserRepository userRepository;
    private final HandleService handleService;

    @GetMapping("/{ownerId}/notification-preferences")
    public ResponseEntity<?> getNotificationPreferences(@PathVariable UUID ownerId) {
        return userRepository.findByOwnerId(ownerId)
                .map(NotificationPreferencesResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Resolves a handle to the user who owns it. This is what makes
     * wallet-to-wallet possible: payment-service is given "@sai123" by the
     * caller and needs an ownerId to credit, and this is the only place that
     * mapping exists.
     *
     * The handle is normalised the same way it is at registration, so the
     * lookup is case and "@" prefix insensitive. Returns 404 for an unknown
     * handle, which is a real answer rather than an outage and must not be
     * treated as a failure by the caller's circuit breaker.
     */
    @GetMapping("/by-handle/{handle}")
    public ResponseEntity<?> getByHandle(@PathVariable String handle) {
        return handleService.normalize(handle)
                .flatMap(normalized -> userRepository.findFirstByUsernameIgnoreCase(normalized)
                        .filter(User::isEnabled)
                        .map(UserHandleResponse::from))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
