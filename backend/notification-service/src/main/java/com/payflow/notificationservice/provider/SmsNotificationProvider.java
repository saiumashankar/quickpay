package com.payflow.notificationservice.provider;

import com.payflow.notificationservice.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SMS delivery over Twilio's REST API.
 *
 * The Twilio helper library is deliberately not used. Its only reason to exist
 * is to wrap one authenticated form POST, and adding it would pull a large
 * dependency tree into a service whose whole job is to send a few messages.
 * Twilio's Messages endpoint is stable and takes form fields, so a RestClient
 * call is the same request with the dependency tree left out.
 *
 * Sending to a phone number is opt-in per merchant, and there is no phone number
 * on a user in this system, so this provider is only reached when a
 * notification is configured with one. It is not a guess.
 */
@Component
public class SmsNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationProvider.class);

    private final NotificationProperties properties;
    private final RestClient restClient;

    public SmsNotificationProvider(NotificationProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public NotificationMessage.Kind kind() {
        return NotificationMessage.Kind.SMS;
    }

    @Override
    public String name() {
        return "twilio";
    }

    @Override
    public boolean isConfigured() {
        NotificationProperties.Sms sms = properties.sms();
        return sms != null
                && sms.accountSid() != null && !sms.accountSid().isBlank()
                && sms.authToken() != null && !sms.authToken().isBlank()
                && sms.fromNumber() != null && !sms.fromNumber().isBlank();
    }

    @Override
    public void send(NotificationMessage message) throws NotificationDeliveryException {
        NotificationProperties.Sms sms = properties.sms();
        if (!isConfigured()) {
            throw NotificationDeliveryException.permanent("SMS is not configured on this deployment");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", message.destination());
        form.add("From", sms.fromNumber());
        form.add("Body", message.body());

        try {
            restClient.post()
                    .uri("https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json", sms.accountSid())
                    .headers(headers -> headers.setBasicAuth(sms.accountSid(), sms.authToken()))
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("SMS delivery to {} failed: {}", message.destination(), exception.getMessage());
            throw NotificationDeliveryException.retryable(
                    "Could not send SMS to " + message.destination(), exception);
        }
    }
}
