package com.example.orderplatform.messaging.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

/**
 * The "email provider" stage — sends an email through SES.
 */
@Component
public class EmailProvider {

    private final SesClient sesClient;
    private final String senderEmail;

    public EmailProvider(SesClient sesClient,
                         @Value("${aws.ses.senderEmail:no-reply@example.com}") String senderEmail) {
        this.sesClient = sesClient;
        this.senderEmail = senderEmail;
    }

    public void send(String recipient, String subject, String bodyText) {
        sesClient.sendEmail(SendEmailRequest.builder()
                .source(senderEmail)
                .destination(Destination.builder().toAddresses(recipient).build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).build())
                        .body(Body.builder().text(Content.builder().data(bodyText).build()).build())
                        .build())
                .build());
    }
}
