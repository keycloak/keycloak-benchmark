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

import org.keycloak.credential.hash.Pbkdf2Sha512PasswordHashProviderFactory;
import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_AUTHZ_CLIENT;
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
 * Configuration parameters, which can be send to the particular datasource operation. They can be send for example through HTTP request
 * query parameters
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class DatasetConfig {

    // Used when creating many realms as a prefix. For example when prefix us "foo", we will create realms like "foo0", "foo1" etc.
    // For many events, it will need the realm prefix as events are created randomly in all the already created realms
    @QueryParamFill(paramName = "realm-prefix", defaultValue = "realm-", operations = { CREATE_REALMS, CREATE_EVENTS, CREATE_OFFLINE_SESSIONS,
            REMOVE_REALMS, LAST_REALM })
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
    @QueryParamFill(paramName = "realm-name",  required = true, operations = { CREATE_CLIENTS, CREATE_USERS, LAST_CLIENT, LAST_USER, CREATE_AUTHZ_CLIENT })
    private String realmName;

    // NOTE: Start index is not available as parameter as it will be "auto-detected" based on already created realms (clients, users)
    private Integer start;

    // Count of entities to be created. Entity is realm, client or user based on the operation
    @QueryParamIntFill(paramName = "count", required = true, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, CREATE_EVENTS, CREATE_OFFLINE_SESSIONS, CREATE_AUTHZ_CLIENT })
    private Integer count;

    // Prefix for realm roles to create in every realm (in case of CREATE_REALMS) or to assign to users (in case of CREATE_USERS)
    @QueryParamFill(paramName = "realm-role-prefix", defaultValue = "role-", operations = { CREATE_REALMS, CREATE_USERS })
    private String realmRolePrefix;

    // Count of realm roles to be created in every created realm
    @QueryParamIntFill(paramName = "realm-roles-per-realm", defaultValue = 25, operations = { CREATE_REALMS })
    private Integer realmRolesPerRealm;

    // Prefix for newly created clients (in case of CREATE_REALMS and CREATE_CLIENTS). In case of CREATE_USERS it is used to find the clients with clientRoles, which will be assigned to users
    @QueryParamFill(paramName = "client-prefix", defaultValue = "client-", operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS, CREATE_OFFLINE_SESSIONS, LAST_CLIENT })
    private String clientPrefix;

    @QueryParamIntFill(paramName = "clients-per-realm", defaultValue = 30, operations = { CREATE_REALMS })
    private Integer clientsPerRealm;

    // Count of clients created in every DB transaction
    @QueryParamIntFill(paramName = "clients-per-transaction", defaultValue = 10, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_AUTHZ_CLIENT })
    private Integer clientsPerTransaction;

    // default number of entries created per DB transaction
    @QueryParamIntFill(paramName = "entries-per-transaction", defaultValue = 10, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_AUTHZ_CLIENT })
    private Integer entriesPerTransaction;

    // Prefix of clientRoles to be created (in case of CREATE_REALMS and CREATE_CLIENTS). In case of CREATE_USERS it is used to find the clientRoles, which will be assigned to users
    @QueryParamFill(paramName = "client-role-prefix", defaultValue = "client-role-", operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS })
    private String clientRolePrefix;

    // Check if created clients should be service account clients
    @QueryParamFill(paramName = "client-access-type", defaultValue = "confidential", operations = {CREATE_REALMS, CREATE_CLIENTS})
    private String clientAccessType;

    // Check if created clients should be service account clients
    @QueryParamFill(paramName = "service-account-client", defaultValue = "true", operations = {CREATE_REALMS, CREATE_CLIENTS})
    private String isServiceAccountClient;

    // When creating clients, every client will have this amount of client roles created
    @QueryParamIntFill(paramName = "client-roles-per-client", defaultValue = 10, operations = { CREATE_REALMS, CREATE_CLIENTS })
    private Integer clientRolesPerClient;

    // Prefix of groups to be created (in case of CREATE_REALMS operation) or assigned to the users (In case of CREATE_USERS and CREATE_REALMS operations)
    @QueryParamFill(paramName = "group-prefix", defaultValue = "group-", operations = { CREATE_REALMS, CREATE_USERS })
    private String groupPrefix;

    // Count of groups to be created in every created realm
    @QueryParamIntFill(paramName = "groups-per-realm", defaultValue = 20, operations = { CREATE_REALMS })
    private Integer groupsPerRealm;

    // Number of groups to be created in one transaction
    @QueryParamIntFill(paramName = "groups-per-transaction", defaultValue = 100, operations = { CREATE_REALMS })
    private Integer groupsPerTransaction;

    // When this parameter is false only top level groups are created, groups and subgroups are created
    @QueryParamFill(paramName = "groups-with-hierarchy", defaultValue = "false", operations = { CREATE_REALMS })
    private String groupsWithHierarchy;

   // Depth of the group hierarchy tree. Active if groups-with-hierarchy = true
    @QueryParamIntFill(paramName = "groups-hierarchy-depth", defaultValue = 3, operations = { CREATE_REALMS })
    private Integer groupsHierarchyDepth;

    // Number of at each level of hierarchy. Each group will have this many subgroups. Active if groups-with-hierarchy = true
    @QueryParamIntFill(paramName = "groups-count-each-level", defaultValue = 10, operations = { CREATE_REALMS })
    private Integer countGroupsAtEachLevel;


    // Prefix for newly created users
    @QueryParamFill(paramName = "user-prefix", defaultValue = "user-", operations = { CREATE_REALMS, CREATE_USERS, CREATE_OFFLINE_SESSIONS, LAST_USER, CREATE_AUTHZ_CLIENT })
    private String userPrefix;

    // Count of users to be created in every realm (In case of CREATE_REALMS)
    @QueryParamIntFill(paramName = "users-per-realm", defaultValue = 200, operations = { CREATE_REALMS, CREATE_AUTHZ_CLIENT })
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

    // Password policy with the number of password hash iterations. It is 210000 by default
    @QueryParamIntFill(paramName = "password-hash-iterations", defaultValue = Pbkdf2Sha512PasswordHashProviderFactory.DEFAULT_ITERATIONS, operations = { CREATE_REALMS })
    private Integer passwordHashIterations;

    // Check if eventStorage will be enabled for newly created realms
    @QueryParamFill(paramName = "events-enabled", defaultValue = "false", operations = { CREATE_REALMS })
    private String eventsEnabled;

    // Transaction timeout used for transactions for creating objects
    @QueryParamIntFill(paramName = "transaction-timeout", defaultValue = 300, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS,
            CREATE_EVENTS, CREATE_OFFLINE_SESSIONS, REMOVE_REALMS, CREATE_AUTHZ_CLIENT })
    private Integer transactionTimeoutInSeconds;

    // Count of users created in every transaction
    @QueryParamIntFill(paramName = "users-per-transaction", defaultValue = 10, operations = { CREATE_REALMS, CREATE_USERS })
    private Integer usersPerTransaction;

    // Count of worker threads concurrently creating entities
    @QueryParamIntFill(paramName = "threads-count", operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS,
            CREATE_EVENTS, CREATE_OFFLINE_SESSIONS, REMOVE_REALMS, CREATE_AUTHZ_CLIENT })
    private Integer threadsCount;

    // Timeout for the whole task. If timeout expires, then the existing task may not be terminated immediatelly. However it will be permitted to start another task
    // (EG. Send another HTTP request for creating realms), which can cause conflicts
    @QueryParamIntFill(paramName = "task-timeout", defaultValue = 3600, operations = { CREATE_REALMS, CREATE_CLIENTS, CREATE_USERS,
            CREATE_EVENTS, CREATE_OFFLINE_SESSIONS, REMOVE_REALMS, CREATE_AUTHZ_CLIENT })
    private Integer taskTimeout;

    // The client id of a client to which data is going to be provisioned
    @QueryParamFill(paramName = "client-id", operations = { CREATE_AUTHZ_CLIENT })
    private String clientId;

    // The prefix to resource names
    @QueryParamFill(paramName = "resource-prefix", defaultValue = "resource-", operations = { CREATE_AUTHZ_CLIENT })
    private String resourcePrefix;

    // The number of scopes per resource
    @QueryParamIntFill(paramName = "scopes-per-resource", defaultValue = 3, operations = CREATE_AUTHZ_CLIENT)
    private int scopesPerResource;

    // The prefix to scope names
    @QueryParamFill(paramName = "scope-prefix", defaultValue = "scope-", operations = { CREATE_AUTHZ_CLIENT })
    private String scopePrefix;

    // The number of users per user policy. If greater than 1, an aggregated policy is created with the given number of user policies
    @QueryParamIntFill(paramName = "users-per-user-policy", defaultValue = 1, operations = CREATE_AUTHZ_CLIENT)
    private int usersPerUserPolicy;

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

    public String getGroupsWithHierarchy() {
        return groupsWithHierarchy;
    }

    public Integer getGroupsHierarchyDepth() {
        return groupsHierarchyDepth;
    }

    public Integer getCountGroupsAtEachLevel() {
        return countGroupsAtEachLevel;
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
        if (threadsCount == -1) {
            return Runtime.getRuntime().availableProcessors();
        }
        return threadsCount;
    }

    public Integer getTaskTimeout() {
        return taskTimeout;
    }

    public String getClientAccessType() {
        return clientAccessType;
    }

    public String getIsServiceAccountClient() {
        return isServiceAccountClient;
    }

    public void setToString(String toString) {
        this.toString = toString;
    }

    public Integer getGroupsPerTransaction() {
        return groupsPerTransaction;
    }

    public String getClientId() {
        return clientId;
    }

    public String getResourcePrefix() {
        return resourcePrefix;
    }

    public Integer getEntriesPerTransaction() {
        return entriesPerTransaction;
    }

    public int getScopesPerResource() {
        return scopesPerResource;
    }

    public String getScopePrefix() {
        return scopePrefix;
    }

    public int getUsersPerUserPolicy() {
        return usersPerUserPolicy;
    }

    @Override
    public String toString() {
        return toString;
    }
}
