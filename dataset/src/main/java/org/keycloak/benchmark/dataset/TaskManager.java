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

import java.util.concurrent.TimeUnit;

import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.commons.api.BasicCache;
import org.jboss.logging.Logger;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.sessions.infinispan.util.InfinispanUtil;

/**
 * Management of executed tasks and making sure that there won't be multiple tasks in progress (EG. there is not creation of 1000 realms triggered accidentally 2 times)
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class TaskManager {

    private final BasicCache<String, String> workCache;

    private final String KEY = "dataset_task";

    protected static final Logger logger = Logger.getLogger(TaskManager.class);

    public TaskManager(KeycloakSession session) {
        Cache workCache = session.getProvider(InfinispanConnectionProvider.class).getCache(InfinispanConnectionProvider.WORK_CACHE_NAME);
        RemoteCache<String, String> remoteCache = InfinispanUtil.getRemoteCache(workCache);
        this.workCache = (remoteCache == null) ? workCache : remoteCache;
    }

    public String getExistingTask() {
        return workCache.get(KEY);
    }

    public String addTaskIfNotInProgress(TimerLogger task, int taskTimeoutInSeconds) {
        String str = task.toString();
        String existing = workCache.putIfAbsent(KEY, str, taskTimeoutInSeconds, TimeUnit.SECONDS);
        return existing;
    }

    public void removeExistingTask(boolean successfullyFinished) {
        String existing = this.workCache.remove(KEY);
        if (existing != null && successfullyFinished) {
            logger.info("FINISHED TASK: " + existing);
        }
    }

}
