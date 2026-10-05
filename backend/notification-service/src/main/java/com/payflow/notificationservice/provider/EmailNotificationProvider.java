package com.payflow.notificationservice.provider;

import com.payflow.notificationservice.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP delivery. Talks to whatever mail server is configured, so it works
 * unchanged against a local dev server, a managed SMTP relay or a provider SDK
 * that sits behind the same interface.
 */
@Component
public class EmailNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProvider.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public EmailNotificationProvider(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public NotificationMessage.Kind kind() {
        return NotificationMessage.Kind.EMAIL;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public boolean isConfigured() {
        return properties.from() != null && !properties.from().isBlank();
    }

    @Override
    public void send(NotificationMessage message) throws NotificationDeliveryException {
        SimpleMailMessage mail = new SimpleMailMessage();
        if (isConfigured()) {
            mail.setFrom(properties.from());
        }
        mail.setTo(message.destination());
        mail.setSubject(message.subject());
        mail.setText(message.body());

        try {
            mailSender.send(mail);
        } catch (MailException exception) {
            // Retryable. A relay that refuses a connection or times out will
            // usually accept the same message later, and the consumer's backoff
            // gives it that chance.
            log.warn("SMTP delivery to {} failed: {}", message.destination(), exception.getMessage());
            throw NotificationDeliveryException.retryable(
                    "Could not send email to " + message.destination(), exception);
        }
    }
}
