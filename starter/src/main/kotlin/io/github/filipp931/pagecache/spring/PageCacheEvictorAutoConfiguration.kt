package io.github.filipp931.pagecache.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Activates scheduled page-cache eviction when `pagecache.evictor.enabled=true`.
 * See [PageCacheEvictorProperties] for the full configuration reference.
 */
@AutoConfiguration
@EnableConfigurationProperties(PageCacheEvictorProperties::class)
@ConditionalOnProperty(prefix = "pagecache.evictor", name = ["enabled"], havingValue = "true")
public class PageCacheEvictorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun pageCacheEvictionScheduler(properties: PageCacheEvictorProperties): PageCacheEvictionScheduler = PageCacheEvictionScheduler(properties)
}
