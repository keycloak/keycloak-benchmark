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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

/**
 * Collection of objects, which were created and are related to the particular realm. This collection is "maintained" here to avoid
 * DB lookups as much as possible...
 *
 * Caller should expect that many model instances obtained here should NOT be used for WRITE as they might be loaded to different transaction.
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class RealmContext {

    private final DatasetConfig config;

    private RealmModel realm;

    private final AtomicLong clientsCount = new AtomicLong();

    private final AtomicLong resourcesCount = new AtomicLong();

    private List<RoleModel> realmRoles = new ArrayList<>();

    // All client roles of all clients
    private List<RoleModel> clientRoles = Collections.synchronizedList(new ArrayList<>());

    private List<GroupModel> groups = new ArrayList<>();

    private final AtomicLong usersCount = new AtomicLong();

    public RealmContext(DatasetConfig config) {
        this.config = config;
    }

    public DatasetConfig getConfig() {
        return config;
    }

    public RealmModel getRealm() {
        return realm;
    }

    public void setRealm(RealmModel realm) {
        this.realm = realm;
    }

    public void incClientCount() {
        clientsCount.incrementAndGet();
    }

    public void setClientCount(long clientCount) {
        clientsCount.set(clientCount);
    }

    public long getClientCount() {
        return clientsCount.get();
    }

    public void realmRoleCreated(RoleModel role) {
        realmRoles.add(role);
    }

    public List<RoleModel> getRealmRoles() {
        return realmRoles;
    }

    public void setRealmRoles(List<RoleModel> realmRoles) {
        this.realmRoles = realmRoles;
    }

    public void clientRoleCreated(ClientModel client, RoleModel clientRole) {
        this.clientRoles.add(clientRole);
    }

    public List<RoleModel> getClientRoles() {
        return clientRoles;
    }

    public void setClientRoles(List<RoleModel> clientRoles) {
        this.clientRoles = Collections.synchronizedList(clientRoles);
    }

    public void groupCreated(GroupModel group) {
        groups.add(group);
    }

    public List<GroupModel> getGroups() {
        return groups;
    }

    public void setGroups(List<GroupModel> groups) {
        this.groups = groups;
    }

    public void incUserCount() {
        this.usersCount.incrementAndGet();
    }

    public long getUserCount() {
        return this.usersCount.get();
    }

    public void incResourceCount() {
        resourcesCount.incrementAndGet();
    }

    public long getResourceCount() {
        return resourcesCount.get();
    }
}
