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

package org.keycloak.benchmark.dataset.config;

import org.keycloak.credential.hash.Pbkdf2PasswordHashProviderFactory;

import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_CLIENTS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_EVENTS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_REALMS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_USERS;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.LAST_CLIENT;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.LAST_REALM;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.LAST_USER;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.REMOVE_REALMS;

/**
 * Configuration parameters, which can be send to the particular datasource operation. They can be send for example through HTTP request
 * query parameters
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class DatasetConfig {

    // Used when creating many realms as a prefix. For example when prefix us "foo", we will create realms like "foo0", "foo1" etc.
    // For many events, it will need the realm prefix as events are created randomly in all the already created realms
    @QueryParamFill(paramName = "realm-prefix", defaultValue = "realm-", operations = { CREATE_REALMS, CREATE_EVENTS, REMOVE_REALMS, LAST_REALM })
    private String realmPrefix;

    // Used if want to remove all realms with given prefix
    @QueryParamFill(paramName = "remove-all", defaultValue = "false", operations = { REMOVE_REALMS })
    private String removeAll;

    // First index to remove included. For example if "first-to-remove" is 30 and "last-to-remove" is 40, then realms "realm30", "realm31", ... , "realm39" will be deleted
    @QueryParamIntFill(paramName = "first-to-remove", defaultValue = -1, operations = { REMOVE_REALMS })
    private Integer firstToRemove;

    // Last index to remove excluded.
    @QueryParamIntFill(paramName = "last-to-remove", defaultValue = -1, operations = { REMOVE_REALMS })
    private Integer lastToRemove;

    // Realm-name is required when creating many clients or users. The realm where clients/users will be created must already exists
    @QueryParamFill(paramName = "realm-name",  required = true, operations = { CREATE_CLIENTS, CREATE_USERS, LAST_CLIENT, LAST_USER })
    private String realmName;

    // NOTE: Start index is not available as parameter as it will be "auto-detected" based on already created realms (clients, users)
    private Integer start;

    // Count of entities to be created. Entity is realm, client or user based on the operation
    @QueryParamIntFill(paramName = "count", required = true, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, CREATE_EVENTS })
    private Integer count;

    // Prefix for realm roles to create in every realm (in case of CREATE_REALMS) or to assign to users (in case of CREATE_USERS)
    @QueryParamFill(paramName = "realm-role-prefix", defaultValue = "role-", operations = { CREATE_REALMS, CREATE_USERS })
    private String realmRolePrefix;

    // Count of realm roles to be created in every created realm
    @QueryParamIntFill(paramName = "realm-roles-per-realm", defaultValue = 25, operations = { CREATE_REALMS })
    private Integer realmRolesPerRealm;

    // Prefix for newly created clients (in case of CREATE_REALMS and CREATE_CLIENTS). In case of CREATE_USERS it is used to find the clients with clientRoles, which will be assigned to users
    @QueryParamFill(paramName = "client-prefix", defaultValue = "client-", operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, LAST_CLIENT })
    private String clientPrefix;

    @QueryParamIntFill(paramName = "clients-per-realm", defaultValue = 30, operations = { CREATE_REALMS })
    private Integer clientsPerRealm;

    // Count of clients created in every DB transaction
    @QueryParamIntFill(paramName = "clients-per-transaction", defaultValue = 10, operations = { CREATE_REALMS, CREATE_CLIENTS })
    private Integer clientsPerTransaction;

    // Prefix of clientRoles to be created (in case of CREATE_REALMS and CREATE_CLIENTS). In case of CREATE_USERS it is used to find the clientRoles, which will be assigned to users
    @QueryParamFill(paramName = "client-role-prefix", defaultValue = "client-role-", operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS })
    private String clientRolePrefix;

    // When creating clients, every client will have this amount of client roles created
    @QueryParamIntFill(paramName = "client-roles-per-client", defaultValue = 10, operations = { CREATE_REALMS, CREATE_CLIENTS })
    private Integer clientRolesPerClient;

    // Prefix of groups to be created (in case of CREATE_REALMS operation) or assigned to the users (In case of CREATE_USERS and CREATE_REALMS operations)
    @QueryParamFill(paramName = "group-prefix", defaultValue = "group-", operations = { CREATE_REALMS, CREATE_USERS })
    private String groupPrefix;

    // Count of groups to be created in every created realm
    @QueryParamIntFill(paramName = "groups-per-realm", defaultValue = 20, operations = { CREATE_REALMS })
    private Integer groupsPerRealm;

    // Prefix for newly created users
    @QueryParamFill(paramName = "user-prefix", defaultValue = "user-", operations = { CREATE_REALMS, CREATE_USERS, LAST_USER })
    private String userPrefix;

    // Count of users to be created in every realm (In case of CREATE_REALMS)
    @QueryParamIntFill(paramName = "users-per-realm", defaultValue = 200, operations = { CREATE_REALMS })
    private Integer usersPerRealm;

    // Count of groups assigned to every user
    @QueryParamIntFill(paramName = "groups-per-user", defaultValue = 4, operations = { CREATE_REALMS, CREATE_USERS })
    private Integer groupsPerUser;

    // Count of realm roles assigned to every user. The roles assigned are not random, but depends on the "index" of the current user and total amount of roles available and assigned to each user
    @QueryParamIntFill(paramName = "realm-roles-per-user", defaultValue = 4, operations = { CREATE_REALMS, CREATE_USERS })
    private Integer realmRolesPerUser;

    // Count of client roles assigned to every user. The roles assigned are not random, but depends on the "index" of the current user and total amount of roles available and assigned to each user
    @QueryParamIntFill(paramName = "client-roles-per-user", defaultValue = 4, operations = { CREATE_REALMS, CREATE_USERS })
    private Integer clientRolesPerUser;

    // Password policy with the amount of password hash iterations. It is 20000 by default
    @QueryParamIntFill(paramName = "password-hash-iterations", defaultValue = Pbkdf2PasswordHashProviderFactory.DEFAULT_ITERATIONS, operations = { CREATE_REALMS })
    private Integer passwordHashIterations;

    // Check if eventStorage will be enabled for newly created realms
    @QueryParamFill(paramName = "events-enabled", defaultValue = "false", operations = { CREATE_REALMS })
    private String eventsEnabled;

    // Transaction timeout used for transactions for creating objects
    @QueryParamIntFill(paramName = "transaction-timeout", defaultValue = 300, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, CREATE_EVENTS, REMOVE_REALMS })
    private Integer transactionTimeoutInSeconds;

    // Count of users created in every transaction
    @QueryParamIntFill(paramName = "users-per-transaction", defaultValue = 10, operations = { CREATE_REALMS, CREATE_USERS })
    private Integer usersPerTransaction;

    // Count of worker threads concurrently creating entities
    @QueryParamIntFill(paramName = "threads-count", defaultValue = 5, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, CREATE_EVENTS, REMOVE_REALMS })
    private Integer threadsCount;

    // Timeout for the whole task. If timeout expires, then the existing task may not be terminated immediatelly. However it will be permitted to start another task
    // (EG. Send another HTTP request for creating realms), which can cause conflicts
    @QueryParamIntFill(paramName = "task-timeout", defaultValue = 3600, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, CREATE_EVENTS, REMOVE_REALMS })
    private Integer taskTimeout;

    // String representation of this configuration (cached here to not be computed in runtime)
    private String toString = "DatasetConfig []";

    public String getRealmPrefix() {
        return realmPrefix;
    }

    public Boolean getRemoveAll() {
        return Boolean.valueOf(removeAll);
    }

    public Integer getFirstToRemove() {
        return firstToRemove;
    }

    public Integer getLastToRemove() {
        return lastToRemove;
    }

    public String getRealmName() {
        return realmName;
    }

    public void setStart(Integer start) {
        this.start = start;
    }

    public Integer getStart() {
        return start;
    }

    public Integer getCount() {
        return count;
    }

    public String getRealmRolePrefix() {
        return realmRolePrefix;
    }

    public Integer getRealmRolesPerRealm() {
        return realmRolesPerRealm;
    }

    public String getClientPrefix() {
        return clientPrefix;
    }

    public Integer getClientsPerRealm() {
        return clientsPerRealm;
    }

    public Integer getClientsPerTransaction() {
        return clientsPerTransaction;
    }

    public String getClientRolePrefix() {
        return clientRolePrefix;
    }

    public Integer getClientRolesPerClient() {
        return clientRolesPerClient;
    }

    public String getGroupPrefix() {
        return groupPrefix;
    }

    public Integer getGroupsPerRealm() {
        return groupsPerRealm;
    }

    public String getUserPrefix() {
        return userPrefix;
    }

    public Integer getUsersPerRealm() {
        return usersPerRealm;
    }

    public Integer getGroupsPerUser() {
        return groupsPerUser;
    }

    public Integer getRealmRolesPerUser() {
        return realmRolesPerUser;
    }

    public Integer getClientRolesPerUser() {
        return clientRolesPerUser;
    }

    public Integer getPasswordHashIterations() {
        return passwordHashIterations;
    }

    public Boolean getEventsEnabled() {
        return Boolean.valueOf(eventsEnabled);
    }

    public Integer getTransactionTimeoutInSeconds() {
        return transactionTimeoutInSeconds;
    }

    public Integer getUsersPerTransaction() {
        return usersPerTransaction;
    }

    public Integer getThreadsCount() {
        return threadsCount;
    }

    public Integer getTaskTimeout() {
        return taskTimeout;
    }

    public void setToString(String toString) {
        this.toString = toString;
    }

    @Override
    public String toString() {
        return toString;
    }
}
