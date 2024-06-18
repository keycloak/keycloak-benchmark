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

import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_ORGS;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.keycloak.benchmark.dataset.DatasetResourceProvider;
import org.keycloak.benchmark.dataset.ExecutorHelper;
import org.keycloak.benchmark.dataset.Task;
import org.keycloak.benchmark.dataset.TaskManager;
import org.keycloak.benchmark.dataset.TaskResponse;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.organization.OrganizationProvider;

public class AbstractOrganizationProvisioner extends DatasetResourceProvider {

    private final String realmName;

    public AbstractOrganizationProvisioner(KeycloakSession session) {
        super(session);
        realmName = session.getContext().getRealm().getName();
    }

    protected Response start(String name, Runnable runnable) {
        DatasetConfig config = getDatasetConfig();
        Task task = Task.start(name);
        TaskManager taskManager = new TaskManager(baseSession);
        Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());

        if (existingTask != null) {
            return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
        }

        try {
            new Thread(runnable).start();
            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleException(handleDatasetException(de));
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            return handleException(Response.status(500).entity(TaskResponse.error("Unexpected error")).build());
        }
    }

    protected OrganizationProvider getOrganizationProvider(KeycloakSession session) {
        session.getContext().setRealm(getRealm(session));
        OrganizationProvider orgProvider = session.getProvider(OrganizationProvider.class);

        if (orgProvider == null) {
            throw new NotFoundException();
        }

        return orgProvider;
    }

    protected DatasetConfig getDatasetConfig() {
        return ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_ORGS);
    }

    private Response handleException(Response response) {
        new TaskManager(baseSession).removeExistingTask(false);
        return response;
    }

    protected String getRealmName() {
        return realmName;
    }

    protected RealmModel getRealm(KeycloakSession session) {
        RealmModel realm = session.realms().getRealmByName(realmName);
        session.getContext().setRealm(realm);
        return realm;
    }

    protected void runJobInTransactionWithTimeout(KeycloakSessionTask task) {
        KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), task::run, getDatasetConfig().getTransactionTimeoutInSeconds());
    }

    protected void addTaskRunningInTransaction(ExecutorHelper executor, KeycloakSessionTask task) {
        executor.addTaskRunningInTransaction(task::run);
    }
}
