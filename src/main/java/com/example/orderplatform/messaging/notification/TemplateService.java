package com.example.orderplatform.messaging.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads notification templates from the classpath and renders them by
 * substituting {@code {{key}}} placeholders with values from the message data.
 */
@Component
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    private final ResourceLoader resourceLoader;

    public TemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Loads the raw template text for the given template name.
     * Looks up {@code classpath:templates/<name>.txt}.
     */
    public String load(String templateName) {
        String path = "classpath:templates/" + templateName + ".txt";
        try {
            Resource resource = resourceLoader.getResource(path);
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load template " + templateName, e);
        }
    }

    /**
     * Replaces every {@code {{key}}} placeholder with the corresponding value
     * from {@code data}. Missing/null values are left as-is.
     */
    public String render(String template, Map<String, Object> data) {
        String result = template;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
        }
        return result;
    }
}
