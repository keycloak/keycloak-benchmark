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

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jboss.logging.Logger;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ExecutorHelper {

    private final ExecutorService executor;
    private final KeycloakSessionFactory sessionFactory;
    private final DatasetConfig config;
    private final Queue<Future> futures = new LinkedList<>();
    protected static final Logger logger = Logger.getLogger(ExecutorHelper.class);

    public ExecutorHelper(int threadCount, KeycloakSessionFactory sessionFactory, DatasetConfig config) {
        executor = Executors.newFixedThreadPool(threadCount);
        this.sessionFactory = sessionFactory;
        this.config = config;
    }

    public void addTask(Runnable task) {
        Future f = executor.submit(task);
        futures.add(f);
    }

    public void addTaskRunningInTransaction(KeycloakSessionTask sessionTask) {
        Future f = executor.submit(() -> KeycloakModelUtils.runJobInTransactionWithTimeout(sessionFactory, sessionTask, config.getTransactionTimeoutInSeconds()));
        futures.add(f);
    }


    public void waitForAllToFinish() throws ExecutionException, InterruptedException {
        logger.info("Waiting for tasks to complete");

        for (Optional<Future> runningTask = getFirstRunningTask(); runningTask.isPresent(); runningTask = getFirstRunningTask()) {
            runningTask.get().get(); // wait for task to complete
        }
        logger.info("All tasks finished");
    }

    private Optional<Future> getFirstRunningTask() {
        return futures.stream().filter(f -> !f.isDone()).findFirst();
    }

    public void shutDown() {
        executor.shutdown();
    }


}
