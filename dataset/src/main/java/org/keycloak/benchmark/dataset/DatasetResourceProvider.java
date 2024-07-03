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

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.NoCache;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.benchmark.dataset.organization.OrganizationProvisioner;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.events.Event;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.EventType;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.KeycloakSessionTaskWithResult;
import org.keycloak.models.KeycloakUriInfo;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCAdvancedConfigWrapper;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.services.resource.RealmResourceProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_CLIENTS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_EVENTS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_GROUPS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_OFFLINE_SESSIONS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_REALMS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_USERS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.LAST_CLIENT;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.LAST_REALM;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.LAST_USER;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.REMOVE_REALMS;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class DatasetResourceProvider implements RealmResourceProvider {

    protected static final Logger logger = Logger.getLogger(DatasetResourceProvider.class);
    public static final String GROUP_NAME_SEPARATOR = ".";

    // Ideally don't use this session to run any DB transactions
    protected final KeycloakSession baseSession;

    protected HttpRequest httpRequest;

    protected KeycloakUriInfo uriInfo;

    public DatasetResourceProvider(KeycloakSession session) {
        this.baseSession = session;
        this.httpRequest = session.getContext().getHttpRequest();
        this.uriInfo = session.getContext().getUri();
    }

    @Override
    public Object getResource() {
        return this;
    }


    // See "CreateRealmConfig" class for the list of available query parameters
    @GET
    @Path("/create-realms")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createRealms() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_REALMS);

            logger.infof("Trigger creating realms with the configuration: %s", config);

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String realmName = config.getRealmPrefix() + index;
                return baseSession.getProvider(RealmProvider.class).getRealmByName(realmName) != null;
            });
            config.setStart(startIndex);


            int realmEndIndex = startIndex + config.getCount();

            Task task = Task.start("Creation of " + config.getCount() + " realms from " + config.getRealmPrefix() + startIndex + " to " + config.getRealmPrefix() + (realmEndIndex - 1));
            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            // Run this in separate thread to not block HTTP request
            new Thread(() -> createRealmsImpl(task, config, startIndex, realmEndIndex)).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    // Implementation of creating many realms. This is triggered outside of HTTP request to not block HTTP request
    private void createRealmsImpl(Task task, DatasetConfig config, int startIndex, int realmEndIndex) {
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);
        try {
            logger.infof("Will start creating realms from '%s' to '%s'", config.getRealmPrefix() + startIndex, config.getRealmPrefix() + (realmEndIndex - 1));

            for (int realmIndex = startIndex; realmIndex < realmEndIndex; realmIndex++) {

                final int currentRealmIndex = realmIndex;

                // Run this concurrently in multiple threads
                executor.addTask(() -> {
                    logger.infof("Started creation of realm %s", config.getRealmPrefix() + currentRealmIndex);

                    RealmContext context = new RealmContext(config);

                    // Step 1 - create realm, realmRoles and groups
                    KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {
                        createAndSetRealm(context, currentRealmIndex, session);
                        task.debug(logger, "Created realm %s", context.getRealm().getName());

                        createRealmRoles(context);
                        task.debug(logger, "Created %d roles in realm %s", context.getRealmRoles().size(), context.getRealm().getName());

                    }, config.getTransactionTimeoutInSeconds());

                    // create each 100 groups per transaction as default case
                    // (to avoid transaction timeouts when creating too many groups in one transaction)
                    createGroupsInMultipleTransactions(config, context, task, config.getGroupsPerRealm());

                    // Step 2 - create clients (Using single executor for now... For multiple executors run separate create-clients endpoint)
                    for (int i = 0; i < config.getClientsPerRealm(); i += config.getClientsPerTransaction()) {
                        int clientsStartIndex = i;
                        int endIndex = Math.min(clientsStartIndex + config.getClientsPerTransaction(), config.getClientsPerRealm());
                        logger.tracef("clientsStartIndex: %d, clientsEndIndex: %d", clientsStartIndex, endIndex);

                        KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session ->
                                createClients(context, task, session, clientsStartIndex, endIndex), config.getTransactionTimeoutInSeconds());

                        task.debug(logger, "Created %d clients in realm %s", context.getClientCount(), context.getRealm().getName());
                    }
                    task.info(logger, "Created all %d clients in realm %s", context.getClientCount(), context.getRealm().getName());

                    // Step 3 - cache realm. This will cache the realm in Keycloak cache (looks like best regarding performance to do it in separate transaction)
                    cacheRealmAndPopulateContext(context);

                    // Step 4 - create users
                    addUserCreationTasks(context, task, config, executor, 0, config.getUsersPerRealm());
                    task.info(logger, "Triggered creation of %d users in realm %s. Finished creation of realm.", config.getUsersPerRealm(), context.getRealm().getName());
                });
            }
            executor.waitForAllToFinish();
            task.info(logger, "Created all realms from '%s' to '%s'", config.getRealmPrefix() + startIndex, config.getRealmPrefix() + (realmEndIndex - 1));
            success();
        } catch (Throwable ex) {
            logException(ex);
        } finally {
            cleanup(executor);
        }
    }

    private void createGroupsInMultipleTransactions(DatasetConfig config, RealmContext context, Task task, int topLevelCount) {
        boolean hierarchicalGroups = Boolean.parseBoolean(config.getGroupsWithHierarchy());
        int hierarchyDepth = config.getGroupsHierarchyDepth();
        int countGroupsAtEachLevel = hierarchicalGroups ? config.getCountGroupsAtEachLevel() : 0;
        String realmName = context.getRealm().getName();
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);
        Long groupsCount = getGroupsCount(realmName);

        for (AtomicInteger index = new AtomicInteger(0); index.get() < topLevelCount; index.incrementAndGet()) {
            KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), baseSession.getContext(), session -> {
                RealmModel realm = session.realms().getRealmByName(realmName);
                session.getContext().setRealm(realm);
                String groupName = config.getGroupPrefix() + index.get();

                while (session.groups().getGroupByName(realm, null, groupName) != null) {
                    groupName = config.getGroupPrefix() + index.incrementAndGet();
                }

                String finalGroupName = groupName;
                KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), session.getContext(), s -> s.groups().createGroup(s.getContext().getRealm(), finalGroupName));
                logger.infof("Creating top-level group %s in realm %s", groupName, context.getRealm().getName());
                createGroupLevel(session, countGroupsAtEachLevel, hierarchyDepth, groupName, executor);
            });
        }

        executor.waitForAllToFinish();

        task.info(logger, "Created all %d groups in realm %s", getGroupsCount(realmName) - groupsCount, context.getRealm().getName());
    }

    private Long getGroupsCount(String realmName) {
        return KeycloakModelUtils.runJobInTransactionWithResult(baseSession.getKeycloakSessionFactory(), baseSession.getContext(), session -> {
            RealmModel realm = session.realms().getRealmByName(realmName);
            return session.groups().getGroupsCount(realm, false);
        });
    }

    protected Response handleDatasetException(DatasetException de) {
        if (de.getCause() != null) {
            logger.error(de.getMessage(), de.getCause());
        } else {
            logger.error(de.getMessage());
        }
        return Response.status(400).entity(TaskResponse.error(de.getMessage())).build();
    }

    @GET
    @Path("/create-clients")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createClients() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_CLIENTS);

            Task task = Task.start("Creation of " + config.getCount() + " clients in the realm " + config.getRealmName());
            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            logger.infof("Trigger creating clients with the configuration: %s", config);

            // Avoid cache (Realm will be invalidated from the cache anyway)
            RealmModel realm = baseSession.getProvider(RealmProvider.class).getRealmByName(config.getRealmName());
            if (realm == null) {
                throw new DatasetException("Realm '" + config.getRealmName() + "' not found");
            }

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String clientId = config.getClientPrefix() + index;
                return realm.getClientByClientId(clientId) != null;
            });
            config.setStart(startIndex);

            // Run this in separate thread to not block HTTP request
            new Thread(() -> createClientsImpl(task, baseSession.getKeycloakSessionFactory(), config, realm)).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    // Implementation of creating many clients. This is triggered outside of HTTP request to not block HTTP request
    private void createClientsImpl(Task task, KeycloakSessionFactory sessionFactory, DatasetConfig config, RealmModel realm) {
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);
        try {
            int startIndex = config.getStart();
            logger.infof("Will start creating clients in the realm '%s' from '%s' to '%s'", config.getRealmName(), config.getClientPrefix() + startIndex, config.getClientPrefix() + (startIndex + config.getCount() - 1));

            RealmContext context = new RealmContext(config);
            context.setRealm(realm);

            // Create clients now
            for (int i = startIndex; i < (startIndex + config.getCount()); i += config.getClientsPerTransaction()) {
                final int clientsStartIndex = i;
                final int endIndex = Math.min(clientsStartIndex + config.getClientsPerTransaction(), startIndex + config.getCount());

                logger.tracef("clientsStartIndex: %d, clientsEndIndex: %d", clientsStartIndex, endIndex);

                // Run this concurrently with multiple threads
                executor.addTaskRunningInTransaction(session -> {

                    createClients(context, task, session, clientsStartIndex, endIndex);

                    task.debug(logger, "Created clients in realm %s from %d to %d", context.getRealm().getName(), clientsStartIndex, endIndex);

                    if (((endIndex - startIndex) / config.getClientsPerTransaction()) % 20 == 0) {
                        task.info(logger, "Created %d clients in realm %s", context.getClientCount(), context.getRealm().getName());
                    }

                });

            }

            executor.waitForAllToFinish();

            task.info(logger, "Created all %d clients in realm %s", context.getClientCount(), context.getRealm().getName());
            success();

        } catch (Throwable ex) {
            logException(ex);
        } finally {
            cleanup(executor);
        }
    }


    @GET
    @Path("/create-users")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createUsers() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_USERS);

            Task task = Task.start("Creation of " + config.getCount() + " users in the realm " + config.getRealmName());
            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            logger.infof("Trigger creating users with the configuration: %s", config);

            // Use the cache
            RealmModel realm = baseSession.realms().getRealmByName(config.getRealmName());
            if (realm == null) {
                throw new DatasetException("Realm '" + config.getRealmName() + "' not found");
            }

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String username = config.getUserPrefix() + index;
                return baseSession.users().getUserByUsername(realm, username) != null;
            });
            config.setStart(startIndex);

            // Run this in separate thread to not block HTTP request
            new Thread(() -> createUsersImpl(task, config, realm)).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    // Implementation of creating many users. This is triggered outside of HTTP request to not block HTTP request
    private void createUsersImpl(Task task, DatasetConfig config, RealmModel realm) {
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);
        try {
            int startIndex = config.getStart();

            logger.infof("Will start creating users in the realm '%s' from '%s' to '%s'", config.getRealmName(), config.getUserPrefix() + startIndex, config.getUserPrefix() + (startIndex + config.getCount() - 1));
            logger.infof("Realm password policy: %s", realm.getPasswordPolicy().toString());

            RealmContext context = new RealmContext(config);
            context.setRealm(realm);

            // Cache the realm (It is probably good due the defaultRoles and defaultGroups when creating users, which would otherwise need to be lookup from DB)
            cacheRealmAndPopulateContext(context);
            task.info(logger, "Cached realm %s", context.getRealm().getName());

            // Create users now
            addUserCreationTasks(context, task, config, executor, startIndex, config.getCount());
            executor.waitForAllToFinish();

            task.info(logger, "Created all %d users in realm %s", context.getUserCount(), context.getRealm().getName());
            success();

        } catch (Throwable ex) {
            logException(ex);
        } finally {
            cleanup(executor);
        }
    }

    private void addUserCreationTasks(RealmContext context, Task task, DatasetConfig config, ExecutorHelper executor, int startIndex, int usersCount) {

        for (int i = startIndex; i < (startIndex + usersCount); i += config.getUsersPerTransaction()) {
            final int usersStartIndex = i;
            final int endIndex = Math.min(usersStartIndex + config.getUsersPerTransaction(), startIndex + usersCount);

            logger.tracef("usersStartIndex: %d, usersEndIndex: %d", usersStartIndex, endIndex);

            // Run this concurrently with multiple threads
            executor.addTaskRunningInTransaction(session -> {
                createUsers(context, session, usersStartIndex, endIndex);

                task.debug(logger, "Created users in realm %s from %d to %d", context.getRealm().getName(), usersStartIndex, endIndex);

                if (((endIndex - startIndex) / config.getUsersPerTransaction()) % 20 == 0) {
                    task.info(logger, "Created %d users in realm %s", context.getUserCount(), context.getRealm().getName());
                }
            });
        }
    }


    @GET
    @Path("/create-events")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createEvents() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_EVENTS);

            int lastRealmIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String realmName = config.getRealmPrefix() + index;
                return baseSession.getProvider(RealmProvider.class).getRealmByName(realmName) != null;
            }) - 1;
            if (lastRealmIndex < 0) {
                throw new DatasetException("Not found any realm with prefix '" + config.getRealmName() + "'");
            }

            Task task = Task.start("Creation of " + config.getCount() + " events");
            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            logger.infof("Trigger creating events with the configuration: %s", config);
            logger.infof("Will create events in the realms '" + config.getRealmPrefix() + "0' - '" + config.getRealmPrefix() + lastRealmIndex + "'");

            // Run this in separate thread to not block HTTP request
            new Thread(() -> createEventsImpl(task, config, lastRealmIndex)).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    // Implementation of creating many events. This is triggered outside of HTTP request to not block HTTP request
    private void createEventsImpl(Task task, DatasetConfig config, int lastRealmIndex) {
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);
        try {
            // Create events now
            int eventsPerTransaction = 10000;
            for (int i = 0; i < config.getCount(); i += eventsPerTransaction) {
                final int eventsStartIndex = i;
				final int eventsEndIndex;

				if (i + eventsPerTransaction < config.getCount()) {
					eventsEndIndex = i + eventsPerTransaction;
				}
				else {
					eventsEndIndex = config.getCount();
				}

                // Run this concurrently with multiple threads
                executor.addTaskRunningInTransaction(session -> {

                    EventStoreProvider eventStore = session.getProvider(EventStoreProvider.class);

		    for (int j = eventsStartIndex; j < eventsEndIndex; j++) {
                        int realmIdx = new Random().nextInt(lastRealmIndex + 1);
                        String realmName = config.getRealmPrefix() + realmIdx;

                        Event event = new Event();
                        event.setClientId("account");
                        event.setDetails(new HashMap<>());
                        event.setError("error");
                        event.setIpAddress("127.0.0.1");
                        event.setRealmId(realmName);
                        event.setSessionId(null);
                        event.setTime(System.currentTimeMillis());
                        event.setType(EventType.LOGIN);
                        event.setUserId("123");
                        eventStore.onEvent(event);
                    }

                    if (eventsEndIndex % (config.getThreadsCount() * eventsPerTransaction) == 0) {
                        task.info(logger, "Created %d events", eventsEndIndex);
                    }

                });

            }

            executor.waitForAllToFinish();

            task.info(logger, "Created all %d events", config.getCount());
            success();

        } catch (Throwable ex) {
            logException(ex);
        } finally {
            cleanup(executor);
        }
    }


    @GET
    @Path("/create-offline-sessions")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createOfflineSessions() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_OFFLINE_SESSIONS);

            int lastRealmIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String realmName = config.getRealmPrefix() + index;
                return baseSession.getProvider(RealmProvider.class).getRealmByName(realmName) != null;
            }) - 1;
            if (lastRealmIndex < 0) {
                throw new DatasetException("Not found any realm with prefix '" + config.getRealmName() + "'");
            }

            Task task = Task.start("Creation of " + config.getCount() + " offline sessions");
            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            logger.infof("Trigger creating offline sessions with the configuration: %s", config);
            logger.infof("Will create offline sessions in the realms '" + config.getRealmPrefix() + "0' - '" + config.getRealmPrefix() + lastRealmIndex + "'");

            // Run this in separate thread to not block HTTP request
            new Thread(() -> createOfflineSessionsImpl(task, config, lastRealmIndex)).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    // Implementation of creating many offline sessions. This is triggered outside of HTTP request to not block HTTP request
    private void createOfflineSessionsImpl(Task task, DatasetConfig config, int lastRealmIndex) {
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);
        try {
            // Create events now
            int offlineSessionsPerTransaction = 100;
            for (int i = 0; i < config.getCount(); i += offlineSessionsPerTransaction) {
                final int sessionIndex = i + offlineSessionsPerTransaction;

                // Run this concurrently with multiple threads
                executor.addTaskRunningInTransaction(session -> {

                    int realmIdx = new Random().nextInt(lastRealmIndex + 1);
                    String realmName = config.getRealmPrefix() + realmIdx;
                    RealmModel realm = session.realms().getRealmByName(realmName);
                    if (realm == null) {
                        throw new IllegalStateException("Not found realm with name '" + realmName + "'");
                    }
                    // Just use user like "user-0"
                    String username = config.getUserPrefix() + "0";
                    UserModel user = session.users().getUserByUsername(realm, username);
                    if (user == null) {
                        throw new IllegalStateException("Not found user with username '" + username + "' in the realm '" + realmName + "'");
                    }
                    // Just use client like "client-0"
                    String clientId = config.getClientPrefix() + "0";
                    ClientModel client = session.clients().getClientByClientId(realm, clientId);
                    if (client == null) {
                        throw new IllegalStateException("Not found client with clientId  '" + client + "' in the realm '" + clientId + "'");
                    }

                    for (int j = 0; j < offlineSessionsPerTransaction; j++) {
                        UserSessionModel userSession = session.sessions().createUserSession(realm, user, username, "127.0.0.1", "form", false, null, null);
                        AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(userSession.getRealm(), client, userSession);

                        // Convert user and client sessions to offline.
                        new UserSessionManager(session).createOrUpdateOfflineSession(clientSession, userSession);

                    }

                    if (sessionIndex % (config.getThreadsCount() * offlineSessionsPerTransaction) == 0) {
                        task.info(logger, "Created %d offline sessions", sessionIndex);
                    }
                });
            }

            executor.waitForAllToFinish();

            task.info(logger, "Created all %d offline sessions", config.getCount());
            success();

        } catch (Throwable ex) {
            logException(ex);
        } finally {
            cleanup(executor);
        }
    }

    @GET
    @Path("/remove-realms")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeRealms() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, REMOVE_REALMS);

            if (!config.getRemoveAll() && (config.getFirstToRemove() == -1 || config.getLastToRemove() == -1)) {
                throw new DatasetException("Either remove-all need to be true OR both first-to-remove and last-to-remove need to be filled");
            }

            Task task;
            if (config.getRemoveAll()) {
                task = Task.start("Removal of all realms with prefix " + config.getRealmPrefix());
            } else {
                task = Task.start("Removal of all realms from " + config.getRealmPrefix() + config.getFirstToRemove() + " to " + config.getRealmPrefix() + (config.getLastToRemove() - 1));
            }

            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            logger.infof("Trigger removing realms with the configuration: %s", config);

            // Run this in separate thread to not block HTTP request
            new Thread(() -> removeRealmsImpl(task, config)).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    // Implementation of removing all realms. This is triggered outside of HTTP request to not block HTTP request
    private void removeRealmsImpl(Task task, DatasetConfig config) {
        ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);
        try {
            final List<String> realmIds = new ArrayList<>();
            KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {
                if (config.getRemoveAll()) {
                    task.info(logger, "Will obtain list of all realms to remove");

                    // Don't cache realms as we are just about to remove them
                    realmIds.addAll(session.getProvider(RealmProvider.class).getRealmsStream()
                            .filter(realm -> realm.getName().startsWith(config.getRealmPrefix()))
                            .map(RealmModel::getId)
                            .collect(Collectors.toList()));

                    task.info(logger, "Will delete %d realms.", realmIds.size());
                } else {
                    // Just rely that realmName is same as realmId to avoid additional lookups
                    for (int i = config.getFirstToRemove(); i < config.getLastToRemove(); i++) {
                        realmIds.add(config.getRealmPrefix() + i);
                    }
                }
            }, config.getTransactionTimeoutInSeconds());

            for (String realmId : realmIds) {
                executor.addTaskRunningInTransaction(session -> {
                    logger.debugf("Will delete realm %s", realmId);

                    // first delete the realm - but keep the realm name for later to remove the client
                    RealmModel realm = session.realms().getRealm(realmId);
                    boolean deleted = session.realms().removeRealm(realmId);

                    if (deleted) {
                        // then delete the client associated with the realm
                        RealmModel master = session.realms().getRealmByName("master");
                        ClientModel clientByClientId = session.clients().getClientByClientId(master, realm.getName() + "-realm");
                        if (clientByClientId != null) {
                            session.clients().removeClient(master, clientByClientId.getId());
                        }
                    }

                    if (deleted) {
                        task.info(logger, "Deleted realm %s", realmId);
                    } else {
                        logger.warnf("Realm %s did not exist", realmId);
                    }
                });
            }

            executor.waitForAllToFinish();

            task.info(logger, "Deleted all %d realms", realmIds.size());
            success();

        } catch (Throwable ex) {
            logException(ex);
        } finally {
            cleanup(executor);
        }
    }


    @GET
    @Path("/status")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        TaskManager taskManager = new TaskManager(baseSession);
        Task task = taskManager.getExistingTask();

        if (task == null) {
            return Response.ok(TaskResponse.noTaskInProgress()).build();
        } else {
            return Response.ok(TaskResponse.existingTaskStatus(task)).build();
        }
    }

    @GET
    @Path("/status-completed")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatusCompleted() {
        TaskManager taskManager = new TaskManager(baseSession);
        Task task = taskManager.getCompletedTask();

        if (task == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(TaskResponse.noCompletedTask()).build();
        } else {
            return Response.ok(TaskResponse.previousTask(task)).build();
        }
    }

    @DELETE
    @Path("/status-completed")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearStatusCompleted() {
        TaskManager taskManager = new TaskManager(baseSession);
        taskManager.deleteCompletedTask();
        return Response.noContent().build();
    }


    @GET
    @Path("/last-realm")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response lastRealm() {
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, LAST_REALM);
            logger.infof("Request to obtain last realm. Configuration: %s", config.toString());

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String realmName = config.getRealmPrefix() + index;
                return baseSession.getProvider(RealmProvider.class).getRealmByName(realmName) != null;
            });

            String response = startIndex == 0 ? "No realm created yet" : config.getRealmPrefix() + (startIndex - 1);

            return Response.ok(TaskResponse.statusMessage(response)).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        }
    }


    @GET
    @Path("/last-client")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response lastClient() {
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, LAST_CLIENT);
            logger.infof("Request to obtain last client. Configuration: %s", config.toString());

            RealmModel realm = baseSession.getProvider(RealmProvider.class).getRealmByName(config.getRealmName());
            if (realm == null) {
                throw new DatasetException("Realm '" + config.getRealmName() + "' not found");
            }

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String clientId = config.getClientPrefix() + index;
                return realm.getClientByClientId(clientId) != null;
            });

            String response = startIndex == 0 ? "No client created yet in realm " + realm.getName() : config.getClientPrefix() + (startIndex - 1);

            return Response.ok(TaskResponse.statusMessage(response)).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        }
    }


    @GET
    @Path("/last-user")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response lastUser() {
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, LAST_USER);
            logger.infof("Request to obtain last user. Configuration: %s", config.toString());

            RealmModel realm = baseSession.getProvider(RealmProvider.class).getRealmByName(config.getRealmName());
            if (realm == null) {
                throw new DatasetException("Realm '" + config.getRealmName() + "' not found");
            }

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String username = config.getUserPrefix() + index;
                return baseSession.users().getUserByUsername(realm, username) != null;
            });

            String response = startIndex == 0 ? "No user created yet in realm " + realm.getName() : config.getUserPrefix() + (startIndex - 1);

            return Response.ok(TaskResponse.statusMessage(response)).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        }
    }

    @Path("/authz")
    public AuthorizationProvisioner authz() {
        return new AuthorizationProvisioner(baseSession);
    }

    @Path("/orgs")
    public OrganizationProvisioner orgs() {
        return new OrganizationProvisioner(baseSession);
    }

    @GET
    @Path("/take-dc-down")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response takeDCDown() {
        String siteName = baseSession.getProvider(InfinispanConnectionProvider.class).getTopologyInfo().getMySiteName();
        baseSession.realms().getRealmByName("master").setAttribute("is-site-" + siteName + "-down", true);

        return Response.ok(TaskResponse.statusMessage("Site " + siteName + " was marked as down.")).build();
    }

    @GET
    @Path("/take-dc-up")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response takeDCUp() {
        String siteName = baseSession.getProvider(InfinispanConnectionProvider.class).getTopologyInfo().getMySiteName();
        baseSession.realms().getRealmByName("master").removeAttribute("is-site-" + siteName + "-down");

        return Response.ok(TaskResponse.statusMessage("Site " + siteName + " was marked as up.")).build();
    }

    @GET
    @Path("/create-groups")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createGroups() {
        boolean started = false;
        boolean taskAdded = false;
        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_GROUPS);

            Task task = Task.start("Creation of " + config.getCount() + " groups in the realm " + config.getRealmName());
            TaskManager taskManager = new TaskManager(baseSession);
            Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            logger.infof("Trigger creating groups with the configuration: %s", config);

            // Use the cache
            RealmModel realm = baseSession.realms().getRealmByName(config.getRealmName());
            if (realm == null) {
                throw new DatasetException("Realm '" + config.getRealmName() + "' not found");
            }

            int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
                String name = config.getGroupPrefix() + index;
                return baseSession.groups().getGroupByName(realm, null, name) != null;
            });
            config.setStart(startIndex);

            // Run this in separate thread to not block HTTP request
            RealmContext context = new RealmContext(config);

            context.setRealm(realm);

            new Thread(() -> {
                try {
                    createGroupsInMultipleTransactions(config, context, task, config.getCount());
                    success();
                } catch (Exception e) {
                    KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), session
                            -> new TaskManager(session).removeExistingTask(false));
                }
            }).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    @Override
    public void close() {
    }


    // Worker task to be triggered by single executor thread
    private void createAndSetRealm(RealmContext context, int index, KeycloakSession session) {
        DatasetConfig config = context.getConfig();

        RealmManager realmManager = new RealmManager(session);
        RealmRepresentation rep = new RealmRepresentation();

        String realmName = config.getRealmPrefix() + index;
        rep.setRealm(realmName);
        rep.setId(realmName);
        RealmModel realm = realmManager.importRealm(rep);

        realm.setEnabled(true);
        realm.setRegistrationAllowed(true);
        realm.setAccessCodeLifespan(60);
        PasswordPolicy.Builder b = PasswordPolicy.build();
        if (!config.getPasswordHashAlgorithm().isEmpty()) { // only set if parameter explicitly provided, see QueryParamFill.defaultValue()
            b.put("hashAlgorithm", config.getPasswordHashAlgorithm());
        }
        if (config.getPasswordHashIterations() != -1) { // only set if parameter explicitly provided, see QueryParamIntFill.defaultValue()
            b.put("hashIterations", config.getPasswordHashIterations().toString());
        }
        realm.setPasswordPolicy(b.build(session));

        if (config.getEventsEnabled()) {
            realm.setEventsEnabled(true);
            realm.setEventsExpiration(new Random().nextInt(10) * 100);
        }

        // offlineSession timeout to vary from 5 to 50 minutes according to the "realm offset"
        int offlineSessionTimeout = ((((index + 10) - 1) % 10) + 1) * 5 * 60;
        realm.setOfflineSessionIdleTimeout(offlineSessionTimeout);

        session.getContext().setRealm(realm);
        context.setRealm(realm);
    }

    private void createRealmRoles(RealmContext context) {
        RealmModel realm = context.getRealm();

        for (int i = 0; i < context.getConfig().getRealmRolesPerRealm(); i++) {
            String roleName = context.getConfig().getRealmRolePrefix() + i;
            RoleModel role = realm.addRole(roleName);
            context.realmRoleCreated(role);
        }
    }


    // Worker task to be triggered by single executor thread
    private void createClients(RealmContext context, Task task, KeycloakSession session, final int startIndex, final int endIndex) {
        RealmModel realm = context.getRealm();

        // Eagerly register invalidation to make sure we don't cache the realm in this transaction. Caching will result in bunch of
        // unneeded SQL queries (triggered from constructor of org.keycloak.models.cache.infinispan.entities.CachedRealm) and we need to invalidate realm anyway in this transaction
        RealmProvider realmProvider = session.realms();
        if (realmProvider instanceof CacheRealmProvider) {
            ((CacheRealmProvider) realmProvider).registerRealmInvalidation(realm.getId(), realm.getName());
        }

        // Refresh realm in current transaction
        realm = realmProvider.getRealm(realm.getId());

        DatasetConfig config = context.getConfig();

        for (int i = startIndex; i < endIndex; i++) {
            ClientRepresentation client = new ClientRepresentation();

            String clientId = config.getClientPrefix() + i;
            client.setClientId(clientId);
            client.setName(clientId);
            client.setEnabled(true);
            client.setDirectAccessGrantsEnabled(true);
            client.setSecret(clientId.concat("-secret"));
            client.setRedirectUris(List.of("*"));
            client.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);

            switch(config.getClientAccessType()) {
                case "bearer-only":
                    client.setBearerOnly(true);
                    break;
                case "public":
                    client.setPublicClient(true);
                    break;
                case "confidential":
                default:
                    client.setPublicClient(false);
                    break;
            }

            ClientModel model = ClientManager.createClient(session, realm, client);

            // Enable service account
            if(Boolean.parseBoolean(config.getIsServiceAccountClient())) {
                new ClientManager(new RealmManager(session)).enableServiceAccount(model);
            }

            // Set "post.logout.redirect.uris"
            if(OIDCAdvancedConfigWrapper.fromClientModel(model).getPostLogoutRedirectUris() == null) {
                OIDCAdvancedConfigWrapper.fromClientModel(model).setPostLogoutRedirectUris(Collections.singletonList("+"));
            }

            context.incClientCount();

            for (int k = 0; k < config.getClientRolesPerClient() ; k++) {
                String roleName = clientId + "-" + config.getClientRolePrefix() + k;
                RoleModel role = model.addRole(roleName);
                context.clientRoleCreated(model, role);
            }
        }

        task.debug(logger, "Created %d clients in realm %s", context.getClientCount(), context.getRealm().getName());
    }

    // Worker task to be triggered by single executor thread
    private void createUsers(RealmContext context, KeycloakSession session, int startIndex, int endIndex) {
        // Refresh the realm
        RealmModel realm = session.realms().getRealm(context.getRealm().getId());
        DatasetConfig config = context.getConfig();

        for (int i = startIndex; i < endIndex; i++) {
            String username = config.getUserPrefix() + i;
            UserModel user = session.users().addUser(realm, username);
            user.setEnabled(true);
            user.setFirstName(username + "-first");
            user.setLastName(username + "-last");
            user.setEmail(username + String.format("@%s.com", realm.getName()));

            String password = String.format("%s-password", username);
            user.credentialManager().updateCredential(UserCredentialModel.password(password, false));

            // Detect which roles we assign to the user
            int roleIndexStartForCurrentUser = (i * config.getRealmRolesPerUser());
            for (int j = roleIndexStartForCurrentUser ; j < roleIndexStartForCurrentUser + config.getRealmRolesPerUser() ; j++) {
                int roleIndex = j % context.getRealmRoles().size();
                user.grantRole(context.getRealmRoles().get(roleIndex));

                logger.tracef("Assigned role %s to the user %s", context.getRealmRoles().get(roleIndex).getName(), user.getUsername());
            }

            int clientRolesTotal = context.getClientRoles().size();
            int clientRoleIndexStartForCurrentUser = (i * config.getClientRolesPerUser());
            for (int j = clientRoleIndexStartForCurrentUser ; j < clientRoleIndexStartForCurrentUser + config.getClientRolesPerUser() ; j++) {
                int roleIndex = j % clientRolesTotal;
                user.grantRole(context.getClientRoles().get(roleIndex));

                logger.tracef("Assigned role %s to the user %s", context.getClientRoles().get(roleIndex).getName(), user.getUsername());
            }

            // Detect which groups we assign to the user
            int groupIndexStartForCurrentUser = (i * config.getGroupsPerUser());
            for (int j = groupIndexStartForCurrentUser ; j < groupIndexStartForCurrentUser + config.getGroupsPerUser() ; j++) {
                int groupIndex = j % context.getGroups().size();
                GroupModel group = context.getGroups().get(groupIndex);
                user.joinGroup(session.groups().getGroupById(realm, group.getId()));

                logger.tracef("Assigned group %s to the user %s", context.getGroups().get(groupIndex).getName(), user.getUsername());
            }

            context.incUserCount();
        }
    }


    private void cacheRealmAndPopulateContext(RealmContext context) {
        DatasetConfig config = context.getConfig();

        KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {

            RealmModel realm = session.realms().getRealm(context.getRealm().getId());
            context.setRealm(realm);

            List<RoleModel> sortedRoles = realm.getRolesStream()
                    .filter(roleModel -> roleModel.getName().startsWith(config.getRealmRolePrefix()))
                    .sorted((role1, role2) -> {
                        String name1 = role1.getName().substring(config.getRealmRolePrefix().length());
                        String name2 = role2.getName().substring(config.getRealmRolePrefix().length());
                        return Integer.parseInt(name1) - Integer.parseInt(name2);
                    })
                    .collect(Collectors.toList());
            context.setRealmRoles(sortedRoles);

            logger.debugf("CACHE: After obtain realm roles in realm %s", realm.getName());

            List<GroupModel> sortedGroups = realm.getGroupsStream()
                    .filter(groupModel -> groupModel.getName().startsWith(config.getGroupPrefix()))
                    .sorted((group1, group2) -> {
                        String name1 = group1.getName().substring(config.getGroupPrefix().length());
                        String name2 = group2.getName().substring(config.getGroupPrefix().length());
                        String [] name1Exploded = name1.split(Pattern.quote(GROUP_NAME_SEPARATOR));
                        String [] name2Exploded = name2.split(Pattern.quote(GROUP_NAME_SEPARATOR));
                        for(int i = 0; i< Math.min(name1Exploded.length, name2Exploded.length); i++) {
                            if (name1Exploded[i].compareTo(name2Exploded[i]) != 0) {
                                return name1Exploded[i].compareTo(name2Exploded[i]);
                            }
                        }
                        return name1.compareTo(name2);
                    })
                    .collect(Collectors.toList());
            context.setGroups(sortedGroups);

            logger.debugf("CACHE: After obtain groups in realm %s", realm.getName());
            realm.getDefaultGroupsStream().collect(Collectors.toList());
            logger.debugf("CACHE: After obtain default groups in realm %s", realm.getName());

            realm.getDefaultRole().getCompositesStream().collect(Collectors.toList());
            logger.debugf("CACHE: After obtain default roles in realm %s", realm.getName());

            // Just obtain first 20 clients for assign client roles - to avoid unecessary DB calls here to load all the clients and then their roles
            List<ClientModel> clients = realm.getClientsStream(0, 20).collect(Collectors.toList());
            logger.debugf("CACHE: After realm.getClients in realm %s", realm.getName());

            List<RoleModel> sortedClientRoles = new ArrayList<>();
            List<ClientModel> sortedClients = clients.stream()
                    .filter(clientModel -> clientModel.getClientId().startsWith(config.getClientPrefix()))
                    .sorted((client1, client2) -> {
                        String name1 = client1.getClientId().substring(config.getClientPrefix().length());
                        String name2 = client2.getClientId().substring(config.getClientPrefix().length());
                        return Integer.parseInt(name1) - Integer.parseInt(name2);
                    })
                    .collect(Collectors.toList());

            sortedClients.forEach(client -> {
                // Sort client roles and add to the shared list
                List<RoleModel> currentClientRoles = client.getRolesStream()
                    .filter(roleModel -> roleModel.getName().startsWith(config.getClientPrefix()))
                    .sorted((role1, role2) -> {
                        int index1 = role1.getName().indexOf(config.getClientRolePrefix()) + config.getClientRolePrefix().length();
                        int index2 = role2.getName().indexOf(config.getClientRolePrefix()) + config.getClientRolePrefix().length();
                        String name1 = role1.getName().substring(index1);
                        String name2 = role2.getName().substring(index2);
                        return Integer.parseInt(name1) - Integer.parseInt(name2);
                    })
                    .collect(Collectors.toList());
                sortedClientRoles.addAll(currentClientRoles);
            });

            logger.debugf("CACHE: After client roles loaded in the realm %s", realm.getName());
            context.setClientCount(sortedClients.size());
            context.setClientRoles(sortedClientRoles);

        }, config.getTransactionTimeoutInSeconds());
    }



    protected String getStatusUrl() {
        String providerClassPath = uriInfo.getAbsolutePath().getPath().substring(0, uriInfo.getAbsolutePath().getPath().lastIndexOf("/"));
        return uriInfo.getAbsolutePathBuilder()
                .replacePath(providerClassPath)
                .path(DatasetResourceProvider.class, "getStatus")
                .build()
                .toString();
    }

    protected void logException(Throwable ex) {
        logger.error("unable to complete task", ex);
    }

    protected void cleanup(ExecutorHelper executor) {
        executor.shutDown();
        KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), session -> {
            try {
                new TaskManager(session).removeExistingTask(false);
            } catch (Throwable ex) {
                logException(ex);
            }
        });
    }

    protected void success() {
        KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), session
                -> new TaskManager(session).removeExistingTask(true));
    }

    private void createGroupLevel(KeycloakSession session, int count, int depth, String parent, ExecutorHelper executor) {
        RealmModel realm = session.getContext().getRealm();
        GroupProvider groups = session.groups();
        GroupModel parentGroup = groups.searchForGroupByNameStream(realm, parent, true, -1, -1).findAny().orElse(null);

        if (parentGroup == null) {
            throw new RuntimeException("Parent group " + parent + " not found");
        }

        for (int index = 0; index < count; index++) {
            String groupName = parent + GROUP_NAME_SEPARATOR + index;

            if (groups.getGroupByName(realm, parentGroup, groupName) != null) {
                continue;
            }

            logger.infof("Creating group %s", groupName);
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), session.getContext(), s -> s.groups().createGroup(realm, groupName, parentGroup));

            executor.addTask(() -> {
                for (int depthIndex = 0; depthIndex < depth; depthIndex++) {
                    KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), session.getContext(), s -> createGroupLevel(s, count, depth - 1, groupName, executor));
                }
            });
        }
    }
}
