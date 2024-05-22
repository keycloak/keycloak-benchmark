package org.keycloak.benchmark.cache;

import jakarta.ws.rs.NotFoundException;
import org.infinispan.Cache;
import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.api.BasicCache;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;

public class EmbeddedCacheResource extends CacheResource {

    private final Cache<Object, Object> cache;

    public EmbeddedCacheResource(KeycloakSession session, String cacheName) {
        InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
        try {
            this.cache = provider.getCache(cacheName);
        } catch (CacheConfigurationException cce) {
            logger.error(cce.getMessage());
            throw new NotFoundException("Cache does not exists");
        }
    }

    @Override
    public BasicCache<Object, Object> getCache() {
        return cache;
    }

    @Override
    protected BasicCache<Object, Object> decorateCacheForRemovalAndSkipListenersIfTrue(boolean skipListeners) {
        return skipListeners
                ? cache.getAdvancedCache().withFlags(org.infinispan.context.Flag.SKIP_CACHE_STORE)
                : cache.getAdvancedCache();
    }
}
