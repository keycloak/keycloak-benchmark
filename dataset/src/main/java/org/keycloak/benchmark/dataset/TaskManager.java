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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Management of executed tasks and making sure that there won't be multiple tasks in progress (EG. there is no creation of 1000 realms triggered accidentally 2 times).
 * </p>
 * It stores its state in the {@link SingleUseObjectProvider} with static key so that it is available across multiple nodes.
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class TaskManager {

    /* Previous versions of the provider stored their state in the workcache of Infinispan which is part of the legacy store.
       With that being no longer available after switching to the map storage, this now uses the SingleUseObjectProvider to store a single value.
       While this might not be a valid strategy for providers running a production environment, this strategy is sufficient for running it in load testing environments. */
    private final SingleUseObjectProvider singleUseObjectProvider;

    private final String KEY_RUNNING = "dataset_task_running";
    private final String KEY_COMPLETED = "dataset_task_completed";

    protected static final Logger logger = Logger.getLogger(TaskManager.class);

    public TaskManager(KeycloakSession session) {
        singleUseObjectProvider = session.getProvider(SingleUseObjectProvider.class);
    }

    public Task getExistingTask() {
        Map<String, String> existingTask = singleUseObjectProvider.get(KEY_RUNNING);
        if (existingTask == null) {
            return null;
        }
        return Task.fromMap(existingTask);
    }

    public Task getCompletedTask() {
        Map<String, String> existingTask = singleUseObjectProvider.get(KEY_COMPLETED);
        if (existingTask == null) {
            return null;
        }
        return Task.fromMap(existingTask);
    }

    public Task addTaskIfNotInProgress(Task task, int taskTimeoutInSeconds) {
        Map<String, String> existingTask = singleUseObjectProvider.get(KEY_RUNNING);
        if (existingTask != null) {
            return Task.fromMap(existingTask);
        }
        Map<String, String> notes = task.toMap();
        singleUseObjectProvider.put(KEY_RUNNING, taskTimeoutInSeconds, notes);
        return null;
    }

    public void removeExistingTask(boolean successfullyFinished) {
        Map<String, String> existingTask = singleUseObjectProvider.get(KEY_RUNNING);
        if (existingTask != null) {
            singleUseObjectProvider.remove(KEY_RUNNING);
            Task task = Task.fromMap(existingTask);
            task.SetSuccess(successfullyFinished);
            singleUseObjectProvider.remove(KEY_COMPLETED);
            singleUseObjectProvider.put(KEY_COMPLETED, TimeUnit.DAYS.toSeconds(1), task.toMap());
            if (successfullyFinished) {
                logger.info("FINISHED TASK: " + task);
            }
        }
    }

    public void deleteCompletedTask() {
        singleUseObjectProvider.remove(KEY_COMPLETED);
    }
}
