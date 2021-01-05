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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.sessions.infinispan.util.InfinispanUtil;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.utils.MediaType;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class CacheResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    private String[] CACHE_NAMES = {
            InfinispanConnectionProvider.REALM_CACHE_NAME,
            InfinispanConnectionProvider.USER_CACHE_NAME,
            InfinispanConnectionProvider.AUTHORIZATION_CACHE_NAME,
            InfinispanConnectionProvider.ACTION_TOKEN_CACHE,
            InfinispanConnectionProvider.WORK_CACHE_NAME,
            InfinispanConnectionProvider.AUTHENTICATION_SESSIONS_CACHE_NAME,
            InfinispanConnectionProvider.USER_SESSION_CACHE_NAME,
            InfinispanConnectionProvider.CLIENT_SESSION_CACHE_NAME,
            InfinispanConnectionProvider.OFFLINE_USER_SESSION_CACHE_NAME,
            InfinispanConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME
    };


    public CacheResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Path("/sizes")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public CacheSizesRepresentation sizes() {
        InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
        EmbeddedCacheManager mgr = provider.getCache(InfinispanConnectionProvider.REALM_CACHE_NAME).getCacheManager();

        Map<String, Integer> cacheSizes = new LinkedHashMap<>();
        for (String cacheName : CACHE_NAMES) {
            int size = mgr.getCache(cacheName).size();
            cacheSizes.put(cacheName, size);
        }

        Map<String, Integer> remoteCacheSizes = new LinkedHashMap<>();
        for (String cacheName : CACHE_NAMES) {
            Cache cache = mgr.getCache(cacheName);
            RemoteCache remoteCache = InfinispanUtil.getRemoteCache(cache);
            if (remoteCache != null) {
                int size = remoteCache.size();
                remoteCacheSizes.put(remoteCache.getName(), size);
            }
        }

        return new CacheSizesRepresentation(cacheSizes, remoteCacheSizes);
    }

    @Path("/{cache}")
    public CacheResource getCacheResource(@PathParam("cache") String cacheName) {
        return new CacheResource(session, cacheName);
    }

    @Override
    public void close() {

    }
}
