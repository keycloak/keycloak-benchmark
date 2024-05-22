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

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.infinispan.commons.api.BasicCache;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.benchmark.dataset.TaskResponse;
import org.keycloak.utils.MediaType;

import java.util.UUID;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public abstract class CacheResource {

    protected static final Logger logger = Logger.getLogger(CacheResource.class);


    public abstract BasicCache<Object, Object> getCache();


    @GET
    @Path("/clear")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public TaskResponse clear() {
        getCache().clear();
        logger.infof("Cache %s cleared successfully", getCache().getName());
        return TaskResponse.statusMessage("Cache " + getCache().getName() + " cleared successfully");
    }

    @GET
    @Path("/contains/{id}")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public boolean contains(@PathParam("id") String id) {
        if (getCache().containsKey(id)) {
            return true;
        } else if (id.length() == 36) {
            try {
                UUID uuid = UUID.fromString(id);
                return getCache().containsKey(uuid);
            } catch (IllegalArgumentException iae) {
                logger.warnf("Given string %s not an UUID", id);
                return false;
            }
        } else {
            return false;
        }
    }

    @GET
    @Path("/size")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public int size() {
        return getCache().size();
    }

    protected abstract BasicCache<Object, Object> decorateCacheForRemovalAndSkipListenersIfTrue(boolean skipListeners);

    @GET
    @Path("/remove/{id}")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public boolean remove(@PathParam("id") String id, @QueryParam("skipListeners") @DefaultValue("false") boolean skipListeners) {
        if (decorateCacheForRemovalAndSkipListenersIfTrue(skipListeners).remove(id) != null) {
            return true;
        } else if (id.length() == 36) {
            try {
                UUID uuid = UUID.fromString(id);
                return decorateCacheForRemovalAndSkipListenersIfTrue(skipListeners).remove(uuid) != null;
            } catch (IllegalArgumentException iae) {
                logger.warnf("Given string %s not an UUID", id);
                return false;
            }
        } else {
            return false;
        }
    }
}
