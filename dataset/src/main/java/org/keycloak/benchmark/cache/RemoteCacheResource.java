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

import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.commons.CacheConfigurationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.benchmark.dataset.TaskResponse;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.sessions.infinispan.util.InfinispanUtil;
import org.keycloak.utils.MediaType;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class RemoteCacheResource {

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


    @GET
    @Path("/clear")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public TaskResponse clear() {
        remoteCache.clear();
        logger.infof("Remote cache %s cleared successfully", remoteCache.getName());
        return TaskResponse.statusMessage("Remote cache " + remoteCache.getName() + " cleared successfully");
    }

    @GET
    @Path("/size")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public int size(@PathParam("id") String id) {
        return remoteCache.size();
    }

    @GET
    @Path("/contains/{id}")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public boolean contains(@PathParam("id") String id) {
        if (remoteCache.containsKey(id)) {
            return true;
        } else if (id.length() == 36) {
            try {
                UUID uuid = UUID.fromString(id);
                return remoteCache.containsKey(uuid);
            } catch (IllegalArgumentException iae) {
                logger.warnf("Given string %s not an UUID", id);
                return false;
            }
        } else {
            return false;
        }
    }
}
