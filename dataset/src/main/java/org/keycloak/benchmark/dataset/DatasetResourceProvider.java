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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.utils.DefaultRoles;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resource.RealmResourceProvider;

import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_CLIENTS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_REALMS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_USERS;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class DatasetResourceProvider implements RealmResourceProvider {

    protected static final Logger logger = Logger.getLogger(DatasetResourceProvider.class);

    // Ideally don't use this session to run any DB transactions
    private final KeycloakSession baseSession;

    @Context
    private HttpRequest httpRequest;

    @Context
    private UriInfo uriInfo;

    public DatasetResourceProvider(KeycloakSession session) {
        this.baseSession = session;
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

            TimerLogger timerLogger = TimerLogger.start("Creation of " + config.getCount() + " realms from " + config.getRealmPrefix() + startIndex + " to " + config.getRealmPrefix() + (realmEndIndex - 1));
            TaskManager taskManager = new TaskManager(baseSession);
            String existingTask = taskManager.addTaskIfNotInProgress(timerLogger, config.getTaskTimeout());
            if (existingTask != null) {
                return Response.status(400).entity(TaskResponse.errorSomeTaskInProgress(existingTask, getStatusUrl())).build();
            } else {
                taskAdded = true;
            }

            // Run this in separate thread to not block HTTP request
            new Thread(() -> {

                createRealmsImpl(timerLogger, baseSession.getKeycloakSessionFactory(), config, startIndex, realmEndIndex);

            }).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(timerLogger.toString(), getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    private void createRealmsImpl(TimerLogger timerLogger, KeycloakSessionFactory sessionFactory, DatasetConfig config, int startIndex, int realmEndIndex) {
        KeycloakModelUtils.runJobInTransactionWithTimeout(sessionFactory, (sessionn -> {
            ExecutorHelper executor = null;
            try {
                logger.infof("Will start creating realms from '%s' to '%s'", config.getRealmPrefix() + startIndex, config.getRealmPrefix() + (realmEndIndex - 1));

                executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);

                for (int realmIndex = startIndex ; realmIndex < realmEndIndex ; realmIndex++) {

                    final int currentRealmIndex = realmIndex;

                    // Run this concurrently with multiple threads
                    executor.addTask(executorSession -> {
                        logger.infof("Started creation of realm %s", config.getRealmPrefix() + currentRealmIndex);

                        RealmContext context = new RealmContext(config);

                        // Step 1 - create realm, realmRoles and groups
                        KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {
                            createAndSetRealm(context, currentRealmIndex, session);
                            timerLogger.debug(logger, "Created realm %s", context.getRealm().getName());

                            createRealmRoles(context);
                            timerLogger.debug(logger, "Created %d roles in realm %s", context.getRealmRoles().size(), context.getRealm().getName());

                            createGroups(context);
                            timerLogger.debug(logger, "Created %d groups in realm %s", context.getGroups().size(), context.getRealm().getName());
                            timerLogger.info(logger, "Created realm, realm roles and groups in realm %s", context.getRealm().getName());

                        }, config.getTransactionTimeoutInSeconds());

                        // Step 2 - create clients (Using single executor for now... For multiple executors run separate create-clients endpoint)
                        for (int i = 0; i < config.getClientsPerRealm(); i += config.getClientsPerTransaction()) {
                            int clientsStartIndex = i;
                            int endIndex = Math.min(clientsStartIndex + config.getClientsPerTransaction(), config.getClientsPerRealm());
                            logger.tracef("clientsStartIndex: %d, clientsEndIndex: %d", clientsStartIndex, endIndex);

                            KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {

                                createClients(context, timerLogger, session, clientsStartIndex, endIndex);

                            }, config.getTransactionTimeoutInSeconds());

                            timerLogger.debug(logger, "Created %d clients in realm %s", context.getClients().size(), context.getRealm().getName());
                        }
                        timerLogger.info(logger, "Created all %d clients in realm %s", context.getClients().size(), context.getRealm().getName());

                        // Step 3 - cache realm. This will cache the realm in Keycloak cache (looks like best regarding performance to do it in separate transaction)
                        cacheRealmAndPopulateContext(context);

                        // Step 4 - create users
                        for (int i = 0; i < config.getUsersPerRealm(); i += config.getUsersPerTransaction()) {
                            int usersStartIndex = i;
                            int endIndex = Math.min(usersStartIndex + config.getUsersPerTransaction(), config.getUsersPerRealm());
                            logger.tracef("usersStartIndex: %d, usersEndIndex: %d", usersStartIndex, endIndex);

                            KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {

                                createUsers(context, timerLogger, session, usersStartIndex, endIndex);

                            }, config.getTransactionTimeoutInSeconds());

                            timerLogger.debug(logger, "Created %d users in realm %s", context.getUsers().size(), context.getRealm().getName());
                        }

                        timerLogger.info(logger, "Created all %d users in realm %s. Finished creation of realm.", context.getUsers().size(), context.getRealm().getName());
                    });

                }

                executor.waitForAllToFinish();

                timerLogger.info(logger, "Created all realms from '%s' to '%s'", config.getRealmPrefix() + startIndex, config.getRealmPrefix() + (realmEndIndex - 1));

            } finally {
                if (executor != null) {
                    executor.shutDown();
                }
                new TaskManager(sessionn).removeExistingTask(true);
            }
        }), config.getTaskTimeout());
    }

    private Response handleDatasetException(DatasetException de) {
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

            TimerLogger timerLogger = TimerLogger.start("Creation of " + config.getCount() + " clients in the realm " + config.getRealmName());
            TaskManager taskManager = new TaskManager(baseSession);
            String existingTask = taskManager.addTaskIfNotInProgress(timerLogger, config.getTaskTimeout());
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
            new Thread(() -> {

                createClientsImpl(timerLogger, baseSession.getKeycloakSessionFactory(), config, realm);

            }).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(timerLogger.toString(), getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }


    private void createClientsImpl(TimerLogger timerLogger, KeycloakSessionFactory sessionFactory, DatasetConfig config, RealmModel realm) {
        KeycloakModelUtils.runJobInTransactionWithTimeout(sessionFactory, (sessionn -> {
            ExecutorHelper executor = null;
            try {
                int startIndex = config.getStart();
                logger.infof("Will start creating clients in the realm '%s' from '%s' to '%s'", config.getRealmName(), config.getClientPrefix() + startIndex, config.getClientPrefix() + (startIndex + config.getCount() - 1));

                RealmContext context = new RealmContext(config);
                context.setRealm(realm);
                executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);

                // Create clients now
                for (int i = startIndex; i < (startIndex + config.getCount()); i += config.getClientsPerTransaction()) {
                    final int clientsStartIndex = i;
                    final int endIndex = Math.min(clientsStartIndex + config.getClientsPerTransaction(), startIndex + config.getCount());

                    logger.tracef("clientsStartIndex: %d, clientsEndIndex: %d", clientsStartIndex, endIndex);

                    // Run this concurrently with multiple threads
                    executor.addTask(session -> {

                        createClients(context, timerLogger, session, clientsStartIndex, endIndex);

                        timerLogger.debug(logger, "Created clients in realm %s from %d to %d", context.getRealm().getName(), clientsStartIndex, endIndex);

                        if (((endIndex - startIndex) / config.getClientsPerTransaction()) % 20 == 0) {
                            timerLogger.info(logger, "Created %d clients in realm %s", context.getClients().size(), context.getRealm().getName());
                        }

                    });

                }

                executor.waitForAllToFinish();

                timerLogger.info(logger, "Created all %d clients in realm %s", context.getClients().size(), context.getRealm().getName());
            } finally {
                if (executor != null) {
                    executor.shutDown();
                }
                new TaskManager(sessionn).removeExistingTask(true);
            }
        }), config.getTaskTimeout());
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

            TimerLogger timerLogger = TimerLogger.start("Creation of " + config.getCount() + " users in the realm " + config.getRealmName());
            TaskManager taskManager = new TaskManager(baseSession);
            String existingTask = taskManager.addTaskIfNotInProgress(timerLogger, config.getTaskTimeout());
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
                return baseSession.users().getUserByUsername(username, realm) != null;
            });
            config.setStart(startIndex);

            // Run this in separate thread to not block HTTP request
            new Thread(() -> {

                createUsersImpl(timerLogger, baseSession.getKeycloakSessionFactory(), config, realm);

            }).start();
            started = true;

            return Response.ok(TaskResponse.taskStarted(timerLogger.toString(), getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !started) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }


    private void createUsersImpl(TimerLogger timerLogger, KeycloakSessionFactory sessionFactory, DatasetConfig config, RealmModel realm) {
        KeycloakModelUtils.runJobInTransactionWithTimeout(sessionFactory, (sessionn -> {
            ExecutorHelper executor = null;
            try {
                int startIndex = config.getStart();

                logger.infof("Will start creating users in the realm '%s' from '%s' to '%s'", config.getRealmName(), config.getUserPrefix() + startIndex, config.getUserPrefix() + (startIndex + config.getCount() - 1));
                logger.infof("Realm password policy: %s", realm.getPasswordPolicy().toString());

                RealmContext context = new RealmContext(config);
                context.setRealm(realm);

                // Cache the realm (It is probably good due the defaultRoles and defaultGroups when creating users, which would otherwise need to be lookup from DB)
                cacheRealmAndPopulateContext(context);
                timerLogger.info(logger, "Cached realm %s", context.getRealm().getName());

                executor = new ExecutorHelper(config.getThreadsCount(), baseSession.getKeycloakSessionFactory(), config);

                // Create users now
                for (int i = startIndex; i < (startIndex + config.getCount()); i += config.getUsersPerTransaction()) {
                    final int usersStartIndex = i;
                    final int endIndex = Math.min(usersStartIndex + config.getUsersPerTransaction(), startIndex + config.getCount());

                    logger.tracef("usersStartIndex: %d, usersEndIndex: %d", usersStartIndex, endIndex);

                    // Run this concurrently with multiple threads
                    executor.addTask(session -> {

                        createUsers(context, timerLogger, session, usersStartIndex, endIndex);

                        timerLogger.debug(logger, "Created users in realm %s from %d to %d", context.getRealm().getName(), usersStartIndex, endIndex);

                        if (((endIndex - startIndex) / config.getUsersPerTransaction()) % 20 == 0) {
                            timerLogger.info(logger, "Created %d users in realm %s", context.getUsers().size(), context.getRealm().getName());
                        }

                    });

                }

                executor.waitForAllToFinish();

                timerLogger.info(logger, "Created all %d users in realm %s", context.getUsers().size(), context.getRealm().getName());

            } finally {
                if (executor != null) {
                    executor.shutDown();
                }
                new TaskManager(sessionn).removeExistingTask(true);
            }
        }), config.getTaskTimeout());
    }


    @GET
    @Path("/status")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        TaskManager taskManager = new TaskManager(baseSession);
        String existingTask = taskManager.getExistingTask();

        if (existingTask == null) {
            return Response.ok(TaskResponse.noTaskInProgress()).build();
        } else {
            return Response.ok(TaskResponse.existingTaskStatus(existingTask)).build();
        }
    }

    @Override
    public void close() {
    }


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

    private void createClients(RealmContext context, TimerLogger timerLogger, KeycloakSession session, final int startIndex, final int endIndex) {
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
            client.setServiceAccountsEnabled(true);
            client.setDirectAccessGrantsEnabled(true);
            client.setSecret(clientId.concat("-secret"));
            client.setRedirectUris(Arrays.asList("*"));
            client.setPublicClient(false);
            client.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);

            ClientModel model = ClientManager.createClient(session, realm, client, true);
            context.clientCreated(model);

            for (int k = 0; k < config.getClientRolesPerClient() ; k++) {
                String roleName = clientId + "-" + config.getClientRolePrefix() + k;
                RoleModel role = model.addRole(roleName);
                context.clientRoleCreated(model, role);
            }
        }

        timerLogger.debug(logger, "Created %d clients in realm %s", context.getClients().size(), context.getRealm().getName());
    }

    private void createGroups(RealmContext context) {
        RealmModel realm = context.getRealm();

        for (int i = 0; i < context.getConfig().getGroupsPerRealm(); i++) {
            String groupName = context.getConfig().getGroupPrefix() + i;
            GroupModel group = realm.createGroup(groupName);
            context.groupCreated(group);
        }
    }

    private void createUsers(RealmContext context, TimerLogger timerLogger, KeycloakSession session, int startIndex, int endIndex) {
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
            session.userCredentialManager().updateCredential(realm, user, UserCredentialModel.password(password, false));

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

            context.userCreated(user);
        }
    }


    private void cacheRealmAndPopulateContext(RealmContext context) {
        DatasetConfig config = context.getConfig();

        KeycloakModelUtils.runJobInTransactionWithTimeout(baseSession.getKeycloakSessionFactory(), session -> {

            RealmModel realm = session.realms().getRealm(context.getRealm().getId());
            context.setRealm(realm);

            Set<RoleModel> roles = realm.getRoles();
            List<RoleModel> sortedRoles = roles.stream()
                    .filter(roleModel -> roleModel.getName().startsWith(config.getRealmRolePrefix()))
                    .sorted((role1, role2) -> {
                        String name1 = role1.getName().substring(config.getRealmRolePrefix().length());
                        String name2 = role2.getName().substring(config.getRealmRolePrefix().length());
                        return Integer.parseInt(name1) - Integer.parseInt(name2);
                    })
                    .collect(Collectors.toList());
            context.setRealmRoles(sortedRoles);

            logger.debugf("CACHE: After obtain realm roles in realm %s", realm.getName());

            List<GroupModel> groups = realm.getGroups();
            List<GroupModel> sortedGroups = groups.stream()
                    .filter(groupModel -> groupModel.getName().startsWith(config.getGroupPrefix()))
                    .sorted((group1, group2) -> {
                        String name1 = group1.getName().substring(config.getGroupPrefix().length());
                        String name2 = group2.getName().substring(config.getGroupPrefix().length());
                        return Integer.parseInt(name1) - Integer.parseInt(name2);
                    })
                    .collect(Collectors.toList());
            context.setGroups(sortedGroups);

            logger.debugf("CACHE: After obtain groups in realm %s", realm.getName());
            realm.getDefaultGroups();
            logger.debugf("CACHE: After obtain default groups in realm %s", realm.getName());

            DefaultRoles.getDefaultRoles(realm);
            logger.debugf("CACHE: After obtain default roles in realm %s", realm.getName());

            // Just obtain first 20 clients for assign client roles - to avoid unecessary DB calls here to load all the clients and then their roles
            List<ClientModel> clients = realm.getClients(0, 20);
            logger.debugf("CACHE: After realm.getClients in realm %s", realm.getName());

            List<RoleModel> sortedClientRoles = new ArrayList<>();
            List<ClientModel> sortedClients = clients.stream()
                    .filter(clientModel -> clientModel.getClientId().startsWith(config.getClientPrefix()))
                    .sorted((client1, client2) -> {
                        String name1 = client1.getClientId().substring(config.getClientPrefix().length());
                        String name2 = client2.getClientId().substring(config.getClientPrefix().length());
                        return Integer.parseInt(name1) - Integer.parseInt(name2);
                    })
                    .peek(client -> {
                        // Sort client roles and add to the shared list
                        List<RoleModel> currentClientRoles = client.getRoles().stream()
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

                    })
                    .collect(Collectors.toList());

            logger.debugf("CACHE: After client roles loaded in the realm %s", realm.getName());
            context.setClients(sortedClients);
            context.setClientRoles(sortedClientRoles);

        }, config.getTransactionTimeoutInSeconds());
    }



    private String getStatusUrl() {
        String providerClassPath = uriInfo.getAbsolutePath().getPath().substring(0, uriInfo.getAbsolutePath().getPath().lastIndexOf("/"));
        return uriInfo.getAbsolutePathBuilder()
                .replacePath(providerClassPath)
                .path(DatasetResourceProvider.class, "getStatus")
                .build()
                .toString();
    }

}
