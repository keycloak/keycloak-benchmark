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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.benchmark.dataset.ExecutorHelper;
import org.keycloak.benchmark.dataset.TaskManager;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.organization.OrganizationProvider;

public class OrganizationMemberProvisioner extends AbstractOrganizationProvisioner {

    private final OrganizationModel organization;
    private final DatasetConfig config;

    public OrganizationMemberProvisioner(KeycloakSession session, OrganizationModel organization, DatasetConfig config) {
        super(session);
        if (organization == null) {
            throw new BadRequestException("Organization not set");
        }
        this.organization = organization;
        this.config = config;
    }

    @Path("create-unmanaged")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createUnmanaged() {
        return start("Creation of " + config.getCount() + " unmanaged members in organization " + organization.getName(), () -> {
            KeycloakSessionFactory sessionFactory = baseSession.getKeycloakSessionFactory();
            ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);
            String orgId = organization.getId();
            CountDownLatch latch = new CountDownLatch(config.getCount());

            executor.addTask(() -> runJobInTransactionWithTimeout(session -> addUnmanagedMembers(session, orgId, latch)));

            try {
                executor.waitForAllToFinish();
                success();
                logger.infof("Added %d members to organization %s", config.getCount() - latch.getCount(), organization.getName());
            } catch (Exception e) {
                KeycloakModelUtils.runJobInTransaction(sessionFactory, session
                        -> new TaskManager(session).removeExistingTask(false));
            }
        });
    }

    @Path("create-managed")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createManaged() {
        return start("Creation of " + config.getCount() + " managed members in organization " + organization.getName(), () -> {
            KeycloakSessionFactory sessionFactory = baseSession.getKeycloakSessionFactory();
            ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);
            String orgId = organization.getId();
            CountDownLatch latch = new CountDownLatch(config.getCount());

            executor.addTask(() -> runJobInTransactionWithTimeout(session -> addManagedMembers(session, orgId, latch)));

            try {
                executor.waitForAllToFinish();
                success();
                logger.infof("Added %d members to organization %s", config.getCount() - latch.getCount(), organization.getName());
            } catch (Exception e) {
                KeycloakModelUtils.runJobInTransaction(sessionFactory, session
                        -> new TaskManager(session).removeExistingTask(false));
            }
        });
    }

    protected void addUnmanagedMembers(KeycloakSession session, String orgId, CountDownLatch latch) {
        OrganizationProvider provider = getOrganizationProvider(session);
        RealmModel realm = session.getContext().getRealm();
        OrganizationModel organization = provider.getById(orgId);
        session.users().searchForUserStream(realm, Map.of(UserModel.INCLUDE_SERVICE_ACCOUNT, Boolean.FALSE.toString()))
                .filter((u) -> provider.getByMember(u) == null)
                .takeWhile(userModel -> latch.getCount() > 0)
                .forEach(userModel -> {
                    provider.addMember(organization, userModel);
                    latch.countDown();
                });
    }

    protected void addManagedMembers(KeycloakSession session, String orgId, CountDownLatch latch) {
        OrganizationProvider provider = getOrganizationProvider(session);
        RealmModel realm = session.getContext().getRealm();
        OrganizationModel organization = provider.getById(orgId);
        session.users().searchForUserStream(realm, Map.of(UserModel.INCLUDE_SERVICE_ACCOUNT, Boolean.FALSE.toString()))
                .filter((u) -> provider.getByMember(u) == null)
                .takeWhile(userModel -> latch.getCount() > 0)
                .forEach(userModel -> {
                    Optional<IdentityProviderModel> broker = organization.getIdentityProviders().findAny();

                    if (broker.isPresent()) {
                        FederatedIdentityModel federatedIdentity = new FederatedIdentityModel(broker.get().getAlias(), userModel.getId(), userModel.getUsername());
                        session.users().addFederatedIdentity(realm, userModel, federatedIdentity);
                        provider.addMember(organization, userModel);
                        latch.countDown();
                    }
                });
    }
}
