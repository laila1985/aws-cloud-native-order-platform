package com.example.orderplatform.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Enables Spring's cache abstraction backed by Redis (ElastiCache in AWS).
 * <p>
 * This implements the cache-aside pattern for reads:
 * <pre>
 *   GET /orders/{id}  →  Redis  →  cache hit?  →  return
 *                                     │ no
 *                                     ▼
 *                                  DynamoDB  →  store in Redis  →  return
 * </pre>
 * <p>
 * Values are serialized to JSON via a Jackson mapper that knows about
 * {@code java.time} types (the order's {@code createdAt} is an {@code Instant}).
 * A time-to-live bounds how long a stale entry may survive.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Value("${spring.cache.redis.time-to-live:PT5M}")
    private Duration timeToLive;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // ObjectMapper aware of java.time (Instant) and with default typing so
        // the JSON round-trips back into concrete Order/OrderItem types.
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .entryTtl(timeToLive);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
