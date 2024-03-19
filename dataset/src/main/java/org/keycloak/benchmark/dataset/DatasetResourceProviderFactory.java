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

package org.keycloak.benchmark.dataset;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

import java.lang.invoke.MethodHandles;
/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class DatasetResourceProviderFactory implements RealmResourceProviderFactory  {

    private static final Logger logger = Logger.getLogger(MethodHandles.lookup().lookupClass());
    public static final String ID = "dataset";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new DatasetResourceProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        try (var session = factory.create()) {
            session.getProvider(InfinispanConnectionProvider.class)
                    .getCache("work")
                    .getCacheManager()
                    .getGlobalComponentRegistry()
                    .registerComponent(factory, "dataset");
        }
        logger.infof("Registered keycloak session factory into Infinispan: " + factory);
    }

    @Override
    public void close() {
    }
}
