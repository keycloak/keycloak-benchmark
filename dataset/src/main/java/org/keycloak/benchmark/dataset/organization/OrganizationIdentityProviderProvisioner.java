/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.benchmark.dataset.organization;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.OAuth2Constants;
import org.keycloak.benchmark.dataset.ExecutorHelper;
import org.keycloak.benchmark.dataset.TaskManager;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.broker.oidc.KeycloakOIDCIdentityProviderFactory;
import org.keycloak.broker.provider.ConfigConstants;
import org.keycloak.broker.provider.HardcodedRoleMapper;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderMapperSyncMode;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.organization.OrganizationProvider;

public class OrganizationIdentityProviderProvisioner extends AbstractOrganizationProvisioner {

    private final OrganizationModel organization;
    private final DatasetConfig config;

    public OrganizationIdentityProviderProvisioner(KeycloakSession session, OrganizationModel organization, DatasetConfig config) {
        super(session);
        this.organization = organization;
        this.config = config;
    }

    @Path("create")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response create() {
        return start("Creation of " + config.getCount() + " identity providers in organization " + organization.getName(), addIdentityProviders());
    }

    private Runnable addIdentityProviders() {
        return () -> {
            String orgId = organization.getId();
            CountDownLatch latch = new CountDownLatch(config.getCount());
            KeycloakSessionFactory sessionFactory = baseSession.getKeycloakSessionFactory();
            ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);

            executor.addTask(() -> runJobInTransactionWithTimeout(session -> addIdentityProviders(session, orgId, latch)));

            try {
                executor.waitForAllToFinish();
                success();
                logger.infof("Added %d identity providers to organization %s", config.getCount() - latch.getCount(), organization.getName());
            } catch (Exception e) {
                KeycloakModelUtils.runJobInTransaction(sessionFactory, session
                        -> new TaskManager(session).removeExistingTask(false));
            }
        };
    }

    protected void addIdentityProviders(KeycloakSession session, String orgId, CountDownLatch latch) {
        OrganizationProvider provider = getOrganizationProvider(session);
        OrganizationModel organization = provider.getById(orgId);
        int startIndex = getLastIndex(session, organization.getName());
        RealmModel realm = session.getContext().getRealm();

        long count = latch.getCount();

        for (int i = startIndex; i < startIndex + count; i++) {
            String idpAlias = "idp-" + organization.getName() + "-" + i;

            IdentityProviderModel identityProvider = realm.getIdentityProviderByAlias(idpAlias);

            if (identityProvider != null && identityProvider.getInternalId() != null && identityProvider.getOrganizationId() != null) {
                continue;
            }

            if (identityProvider == null || identityProvider.getInternalId() == null) {
                identityProvider = new IdentityProviderModel();

                identityProvider.setAlias(idpAlias);
                identityProvider.setProviderId(KeycloakOIDCIdentityProviderFactory.PROVIDER_ID);
                identityProvider.setLoginHint(true);
                identityProvider.setEnabled(true);
                HashMap<String, String> idpConfig = new HashMap<>();
                identityProvider.setConfig(idpConfig);
                idpConfig.put("issuer", "http://localhost:8180/realms/" + realm.getName());
                idpConfig.put("jwksUrl", "http://localhost:8180/realms/" + realm.getName() + "/protocol/openid-connect/certs");
                idpConfig.put("logoutUrl", "http://localhost:8180/realms/" + realm.getName() + "/protocol/openid-connect/auth");
                idpConfig.put("metadataDescriptorUrl", "http://localhost:8180/realms/" + realm.getName() + "/.well-known/openid-configuration");
                idpConfig.put("tokenUrl", "http://localhost:8180/realms/" + realm.getName() + "/protocol/openid-connect/token");
                idpConfig.put("authorizationUrl", "http://localhost:8180/realms/" + realm.getName() + "/protocol/openid-connect/auth");
                idpConfig.put("useJwksUrl", "true");
                idpConfig.put("userInfoUrl", "http://localhost:8180/realms/" + realm.getName() + "/protocol/openid-connect/userinfo");
                idpConfig.put("validateSignature", "true");
                idpConfig.put("clientId", "org-broker-client");
                idpConfig.put("clientSecret", "secret");
                idpConfig.put("clientAuthMethod", "client_secret_post");
                realm.addIdentityProvider(identityProvider);

                for (int j = 0; j < config.getIdentityProviderMappersCount(); j++) {
                    IdentityProviderMapperModel mapper = new IdentityProviderMapperModel();
                    mapper.setIdentityProviderAlias(idpAlias);
                    mapper.setName(idpAlias + "-idp-mapper-" + j);
                    mapper.setIdentityProviderMapper(HardcodedRoleMapper.PROVIDER_ID);
                    mapper.setConfig(Map.of(
                            ConfigConstants.ROLE, OAuth2Constants.OFFLINE_ACCESS, 
                            IdentityProviderMapperModel.SYNC_MODE, IdentityProviderMapperSyncMode.INHERIT.toString()
                    ));
                    realm.addIdentityProviderMapper(mapper);
                }

                AtomicInteger lastIndex = (AtomicInteger) session.getAttribute("idpLastIndex");
                lastIndex.incrementAndGet();
            }

            if (provider.addIdentityProvider(organization, identityProvider)) {
                latch.countDown();
            }
        }
    }

    private int getLastIndex(KeycloakSession session, String orgName) {
        AtomicInteger lastIndex = (AtomicInteger) session.getAttribute("idpLastIndex");

        if (lastIndex == null) {
            RealmModel realm = session.getContext().getRealm();
            lastIndex = new AtomicInteger(ConfigUtil.findFreeEntityIndex(index -> realm.getIdentityProvidersStream().anyMatch(idp -> idp.getAlias().equals("idp-" + orgName + "-" + index))));
            session.setAttribute("idpLastIndex", lastIndex);
        }

        return lastIndex.get();
    }
}
