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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.infinispan.Cache;
import org.infinispan.commons.CacheConfigurationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.benchmark.dataset.TaskResponse;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.utils.MediaType;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class CacheResource {

    protected static final Logger logger = Logger.getLogger(CacheResource.class);

    private final Cache<Object, Object> cache;

    public CacheResource(KeycloakSession session, String cacheName) {
        InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
        try {
            this.cache = provider.getCache(cacheName);
        } catch (CacheConfigurationException cce) {
            logger.error(cce.getMessage());
            throw new NotFoundException("Cache does not exists");
        }
    }


    @GET
    @Path("/clear")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public TaskResponse clear() {
        cache.clear();
        logger.infof("Cache %s cleared successfully", cache.getName());
        return TaskResponse.statusMessage("Cache " + cache.getName() + " cleared successfully");
    }

    @GET
    @Path("/contains/{id}")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public boolean contains(@PathParam("id") String id) {
        if (cache.containsKey(id)) {
            return true;
        } else if (id.length() == 36) {
            try {
                UUID uuid = UUID.fromString(id);
                return cache.containsKey(uuid);
            } catch (IllegalArgumentException iae) {
                logger.warnf("Given string %s not an UUID", id);
                return false;
            }
        } else {
            return false;
        }
    }
}
