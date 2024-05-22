/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.keycloak.benchmark.cache;

import jakarta.ws.rs.NotFoundException;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.api.BasicCache;
import org.jboss.logging.Logger;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.connections.infinispan.InfinispanUtil;
import org.keycloak.models.KeycloakSession;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class RemoteCacheResource extends CacheResource {

    protected static final Logger logger = Logger.getLogger(RemoteCacheResource.class);

    private final RemoteCache remoteCache;

    public RemoteCacheResource(KeycloakSession session, String cacheName) {
        InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
        Cache<Object, Object> cache = null;
        try {
            cache = provider.getCache(cacheName);
        } catch (CacheConfigurationException cce) {
            logger.error(cce.getMessage());
            throw new NotFoundException("Cache does not exists");
        }

        RemoteCache remoteCache = InfinispanUtil.getRemoteCache(cache);
        if (remoteCache == null) {
            logger.errorf("Cache %s exists, but does not have remoteStore attached. Is RHDG integration enabled?", cacheName);
            throw new NotFoundException("Remote cache does not exists");
        }
        this.remoteCache = remoteCache;
    }


    @Override
    public BasicCache<Object, Object> getCache() {
        return remoteCache;
    }

    @Override
    protected BasicCache<Object, Object> decorateCacheForRemovalAndSkipListenersIfTrue(boolean skipListeners) {
        return skipListeners
                ? remoteCache.withFlags(Flag.SKIP_LISTENER_NOTIFICATION, Flag.FORCE_RETURN_VALUE)
                : remoteCache.withFlags(Flag.FORCE_RETURN_VALUE);
    }
}
