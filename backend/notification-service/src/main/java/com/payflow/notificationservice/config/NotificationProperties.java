package com.payflow.notificationservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param from the envelope sender for email. Blank disables the email provider
 *             rather than failing a send, so a deployment without a relay still
 *             starts and can use the other channels.
 * @param sms  Twilio credentials. Absent disables SMS.
 * @param webhook the HMAC secret merchants verify with. Absent disables webhook
 *                delivery. The destination URL is not configured here because it
 *                is per merchant and travels on the notification itself.
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(String from, Sms sms, Webhook webhook) {

    public record Sms(String accountSid, String authToken, String fromNumber) {
    }

    public record Webhook(String secret) {
    }
}
