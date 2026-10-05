package com.payflow.notificationservice.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the provider for a kind of message.
 *
 * Every NotificationProvider on the classpath registers itself here by the kind
 * it handles, so adding a transport is a matter of adding a component. Neither
 * NotificationService nor the consumer knows which providers exist, and there is
 * no switch statement anywhere to keep in step with them.
 *
 * Two providers claiming the same kind is a startup failure rather than a
 * silently ignored bean. It would otherwise show up as one provider quietly
 * never being called, which is the hardest kind of wiring bug to notice.
 */
@Component
public class NotificationProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviderRegistry.class);

    private final Map<NotificationMessage.Kind, NotificationProvider> providers;

    public NotificationProviderRegistry(List<NotificationProvider> discovered) {
        Map<NotificationMessage.Kind, NotificationProvider> byKind = new EnumMap<>(NotificationMessage.Kind.class);
        for (NotificationProvider provider : discovered) {
            NotificationProvider existing = byKind.putIfAbsent(provider.kind(), provider);
            if (existing != null) {
                throw new IllegalStateException("Two notification providers claim the kind "
                        + provider.kind() + ": " + existing.name() + " and " + provider.name());
            }
        }
        this.providers = Map.copyOf(byKind);
        log.info("Notification providers registered: {}", this.providers.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().name() + (entry.getValue().isConfigured() ? "" : " (disabled)"))
                .toList());
    }

    /**
     * @return the provider for this kind, or empty when none handles it.
     */
    public java.util.Optional<NotificationProvider> forKind(NotificationMessage.Kind kind) {
        return java.util.Optional.ofNullable(providers.get(kind));
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }
}
