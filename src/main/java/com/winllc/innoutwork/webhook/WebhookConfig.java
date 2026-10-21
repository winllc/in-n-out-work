package com.winllc.innoutwork.webhook;

import com.winllc.innoutwork.config.ApplicationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

/** The webhook sender's address policy and transport: the JVM's trust store, no proxy. */
@Configuration
public class WebhookConfig {

    @Bean
    WebhookAddressPolicy webhookAddressPolicy(ApplicationProperties properties) {
        return WebhookAddressPolicy.of(properties.getWebhooks().getAllowedCidrs());
    }

    @Bean
    WebhookTransport webhookTransport(WebhookAddressPolicy policy, ApplicationProperties properties)
            throws NoSuchAlgorithmException {
        ApplicationProperties.Webhooks settings = properties.getWebhooks();
        return new WebhookTransport(policy, SSLContext.getDefault().getSocketFactory(), settings.getConnectTimeout(),
                settings.getReadTimeout());
    }
}
