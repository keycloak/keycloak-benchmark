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
import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.benchmark.dataset.tasks.CreateClientsDistributedTask;
import org.keycloak.benchmark.dataset.tasks.CreateUsersDistributedTask;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.events.Event;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.EventType;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakUriInfo;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.services.resource.RealmResourceProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_CLIENTS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_EVENTS;
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
        var clusterExecutor = baseSession.getProvider(InfinispanConnectionProvider.class)
                .getCache("work")
                .getCacheManager()
                .executor()
                .allNodeSubmission()
                .timeout(config.getTaskTimeout(), TimeUnit.SECONDS);
        try {
            logger.infof("Will start creating realms from '%s' to '%s'", config.getRealmPrefix() + startIndex, config.getRealmPrefix() + (realmEndIndex - 1));

            for (int realmIndex = startIndex; realmIndex < realmEndIndex; realmIndex++) {

                final int currentRealmIndex = realmIndex;

                // Run this concurrently in multiple threads
                executor.addTask(() -> {
                    var realmName = config.getRealmPrefix() + currentRealmIndex;
                    logger.infof("Started creation of realm %s", realmName);

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
                    createGroupsInMultipleTransactions(config, context, task);

                    try {
                        // Step 2 - create clients (Using single executor for now... For multiple executors run separate create-clients endpoint)
                        task.info(logger, "Creating clients");
                        var clientsTask = new CreateClientsDistributedTask(realmName, 0, config.getClientsPerRealm(), config);
                        clusterExecutor.submitConsumer(clientsTask, clientsTask).get();

                        // Step 4 - create users
                        task.info(logger, "Creating users");
                        var usersTask = new CreateUsersDistributedTask(realmName, 0, config.getUsersPerRealm(), config);
                        clusterExecutor.submitConsumer(usersTask, usersTask).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExecutionException e) {
                        throw new DatasetException(e);
                    }


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

    private void createGroupsInMultipleTransactions(DatasetConfig config, RealmContext context, Task task) {
        int groupsPerRealm = config.getGroupsPerRealm();
        boolean hierarchicalGroups = Boolean.parseBoolean(config.getGroupsWithHierarchy());
        int hierarchyDepth = config.getGroupsHierarchyDepth();
        int countGroupsAtEachLevel = hierarchicalGroups ? config.getCountGroupsAtEachLevel() : groupsPerRealm;
        int totalNumberOfGroups =  hierarchicalGroups ? (int) Math.pow(countGroupsAtEachLevel, hierarchyDepth) : groupsPerRealm;

        for (int i = 0; i < totalNumberOfGroups; i += config.getGroupsPerTransaction()) {
            int groupsStartIndex = i;
            int groupEndIndex =  hierarchicalGroups ? Math.min(groupsStartIndex + config.getGroupsPerTransaction(), totalNumberOfGroups)
            : Math.min(groupsStartIndex + config.getGroupsPerTransaction(), config.getGroupsPerRealm());

            logger.tracef("groupsStartIndex: %d, groupsEndIndex: %d", groupsStartIndex, groupEndIndex);

            KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(),
                    session -> createGroups(context, groupsStartIndex, groupEndIndex, hierarchicalGroups, hierarchyDepth, countGroupsAtEachLevel, session),
                    config.getTransactionTimeoutInSeconds());

            task.debug(logger, "Created %d groups in realm %s", context.getGroups().size(), context.getRealm().getName());
        }
        task.info(logger, "Created all %d groups in realm %s", context.getGroups().size(), context.getRealm().getName());
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

            var distributedTask = new CreateClientsDistributedTask(config);
            baseSession.getProvider(InfinispanConnectionProvider.class)
                    .getCache("work")
                    .getCacheManager()
                    .executor()
                    .allNodeSubmission()
                    .timeout(config.getTaskTimeout(), TimeUnit.SECONDS)
                    .submitConsumer(distributedTask, distributedTask)
                    .whenComplete((unused, throwable) -> {
                        try {
                            if (throwable != null) {
                                logException(throwable);
                                return;
                            }
                            task.info(logger, "Created all %d clients in realm %s", config.getCount(), config.getRealmName());
                            success();
                        } finally {
                            cleanup();
                        }
                    });
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

            var distributedTask = new CreateUsersDistributedTask(realm.getId(), config);
            baseSession.getProvider(InfinispanConnectionProvider.class)
                    .getCache("work")
                    .getCacheManager()
                    .executor()
                    .allNodeSubmission()
                    .timeout(config.getTaskTimeout(), TimeUnit.SECONDS)
                    .submitConsumer(distributedTask, distributedTask)
                    .whenComplete((unused, throwable) -> {
                        try {
                            if (throwable != null) {
                                logException(throwable);
                                return;
                            }
                            task.info(logger, "Created all %d users in realm %s", config.getCount(), config.getRealmName());
                            success();
                        } finally {
                            cleanup();
                        }
                    });
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
        realm.setPasswordPolicy(PasswordPolicy.parse(session, "hashIterations(" + config.getPasswordHashIterations() + ")"));

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


    private String getGroupName(boolean hierarchicalGroups, int countGroupsAtEachLevel, String prefix, int currentCount) {

        if (!hierarchicalGroups) {
            return prefix + currentCount;
        }
        if(currentCount == 0) {
            return  prefix + "0";
        }
        // we are using "." separated paths in the group names, this is basically a number system with countGroupsAtEachLevel being the basis
        // this allows us to find the parent group by trimming the group name even if the parent was created in previous transaction
        StringBuilder groupName = new StringBuilder();
        if(countGroupsAtEachLevel == 1) {
            // numbering system does not work for base 1
            groupName.append("0");
            IntStream.range(0, currentCount).forEach(i -> groupName.append(GROUP_NAME_SEPARATOR).append("0"));
            return prefix + groupName;
        }

        int leftover = currentCount;
        while (leftover > 0) {
            int digit = leftover % countGroupsAtEachLevel;
            groupName.insert(0, digit + GROUP_NAME_SEPARATOR);
            leftover = (leftover - digit) / countGroupsAtEachLevel;
        }
        return prefix + groupName.substring(0, groupName.length() - 1);
    }

    private String getParentGroupName(String groupName) {
        if (groupName == null || groupName.lastIndexOf(GROUP_NAME_SEPARATOR) < 0) {
            return null;
        }
        return groupName.substring(0, groupName.lastIndexOf(GROUP_NAME_SEPARATOR));
    }

    private void createGroups(RealmContext context, int startIndex, int endIndex, boolean hierarchicalGroups, int hierarchyDepth, int countGroupsAtEachLevel, KeycloakSession session) {
        RealmModel realm = context.getRealm();
        for (int i = startIndex; i < endIndex; i++) {
            String groupName = getGroupName(hierarchicalGroups, countGroupsAtEachLevel, context.getConfig().getGroupPrefix(), i);
            String parentGroupName = getParentGroupName(groupName);

            if (parentGroupName != null) {
                Optional<GroupModel> maybeParent = session.groups().searchForGroupByNameStream(realm, parentGroupName, true, 1, 1).findFirst();
                maybeParent.ifPresent(parent -> {
                    GroupModel groupModel = session.groups().createGroup(realm, groupName, parent);
                    context.groupCreated(groupModel);
                });
            } else {
                GroupModel groupModel = session.groups().createGroup(realm, groupName);
                context.groupCreated(groupModel);
            }
        }
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
                user.joinGroup(context.getGroups().get(groupIndex));

                logger.tracef("Assigned group %s to the user %s", context.getGroups().get(groupIndex).getName(), user.getUsername());
            }

            context.incUserCount();
        }
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
        cleanup();
    }

    protected void cleanup() {
        KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), session ->
                new TaskManager(session).removeExistingTask(false));
    }

    protected void success() {
        KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), session
                -> new TaskManager(session).removeExistingTask(true));
    }

}
