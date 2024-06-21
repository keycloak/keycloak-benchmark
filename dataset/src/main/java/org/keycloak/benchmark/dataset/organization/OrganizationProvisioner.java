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

import static org.keycloak.utils.StringUtil.isBlank;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.benchmark.dataset.ExecutorHelper;
import org.keycloak.benchmark.dataset.TaskResponse;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.models.ClientModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.OrganizationDomainModel;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.utils.StringUtil;

public class OrganizationProvisioner extends AbstractOrganizationProvisioner {

    private OrganizationModel organization;

    public OrganizationProvisioner(KeycloakSession session) {
        super(session);
        enableOrganization();
    }

    @Path("create")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response create() {
        return start("Creation of " + getDatasetConfig().getCount() + " organizations", createOrganizations());
    }

    @Path("remove")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response remove() {
        OrganizationProvider provider = getOrganizationProvider(baseSession);
        RealmModel realm = getRealm(baseSession);
        organization.getIdentityProviders().map(IdentityProviderModel::getAlias).forEach(realm::removeIdentityProviderByAlias);
        provider.remove(organization);
        return Response.noContent().build();
    }

    @Path("{org-alias}")
    public Object getOrganization(@PathParam("org-alias") String orgAlias) {
        OrganizationProvider provider = getOrganizationProvider(baseSession);

        organization = provider.getAllStream(orgAlias, true, -1, -1).findAny().orElse(null);

        if (organization == null) {
            return Response.status(400).entity(TaskResponse.error("Organization " + orgAlias + " does not exist")).build();
        }

        return this;
    }

    @Path("members")
    public Object members() {
        return new OrganizationMemberProvisioner(baseSession, organization, getDatasetConfig());
    }

    @Path("identity-providers")
    public Object identityProviders() {
        return new OrganizationIdentityProviderProvisioner(baseSession, organization, getDatasetConfig());
    }

    private Runnable createOrganizations() {
        return () -> {
            DatasetConfig config = getDatasetConfig();
            KeycloakSessionFactory sessionFactory = baseSession.getKeycloakSessionFactory();
            ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);

            try {
                Integer count = config.getCount();

                executor.addTask(() -> runJobInTransactionWithTimeout(session -> {
                    RealmModel realm = getRealm(session);
                    ClientModel client = realm.getClientByClientId("org-broker-client");

                    if (client == null) {
                        client = realm.addClient("org-broker-client");
                        client.setSecret("secret");
                        client.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
                        client.setPublicClient(false);
                        client.addRedirectUri("http://localhost:8180/realms/" + realm.getName() + "/broker/*");
                    }

                    int startIndex = getLastIndex(session);

                    if (isBlank(config.getName())) {
                        for (int i = startIndex; i < (startIndex + count); i += config.getEntriesPerTransaction()) {
                            int orgStartIndex = i;
                            int endIndex = Math.min(orgStartIndex + config.getEntriesPerTransaction(), startIndex + count);

                            addTaskRunningInTransaction(executor, s -> {
                                for (int j = orgStartIndex; j < endIndex; j++) {
                                    createOrganization(config.getOrgPrefix() + j, s, config);
                                }

                                int createdCount = endIndex - orgStartIndex;

                                logger.infof("Created %d organizations in realm %s from %d to %d", createdCount, getRealmName(), orgStartIndex, endIndex);
                            });
                        }
                    } else {
                        createOrganization(config.getName(), session, config);
                    }
                }));

                executor.waitForAllToFinish();
                success();
            } catch (Exception e) {
                cleanup(executor);
                logger.error("Failed to provision organizations", e);
            }
        };
    }

    private void createOrganization(String name, KeycloakSession session, DatasetConfig config) {
        OrganizationProvider orgProvider = getOrganizationProvider(session);

        if (orgProvider.getAllStream(name, true, -1, -1).findAny().isPresent()) {
            RealmModel realm = session.getContext().getRealm();
            logger.infof("Not creating %s organization in realm %s because it already exists", name, realm.getName());
            return;
        }

        OrganizationModel organization = orgProvider.create(name, name);
        int domainsCount = config.getDomainsCount();
        String domains = Optional.ofNullable(config.getDomains()).filter(StringUtil::isNotBlank).orElse(name);

        if (domainsCount > 0) {
            Set<String> domainSet = new HashSet<>();

            for (int i = 0; i < domainsCount; i++) {
                domainSet.add(name + "." + "d" + i);
            }

            domains = String.join(",", domainSet);
        }

        organization.setDomains(Stream.of(domains.split(",")).map(OrganizationDomainModel::new).collect(Collectors.toSet()));

        int orgUnmanagedMembersCount = config.getUnManagedMembersCount();

        if (orgUnmanagedMembersCount > 0) {
            new OrganizationMemberProvisioner(baseSession, organization, config).addUnmanagedMembers(session, organization.getId(), new CountDownLatch(orgUnmanagedMembersCount));
        }

        int orgIdentityProvidersCount = config.getIdentityProvidersCount();

        if (orgIdentityProvidersCount > 0) {
            new OrganizationIdentityProviderProvisioner(baseSession, organization, config).addIdentityProviders(session, organization.getId(), new CountDownLatch(orgIdentityProvidersCount));
        }
    }

    private int getLastIndex(KeycloakSession session) {
        String orgPrefix = getDatasetConfig().getOrgPrefix();
        return ConfigUtil.findFreeEntityIndex(index -> getOrganizationProvider(session).getAllStream(orgPrefix + index, true, -1, -1).findAny().isPresent());
    }

    private void enableOrganization() {
        RealmModel realm = baseSession.getContext().getRealm();
        realm.setOrganizationsEnabled(true);
    }
}
