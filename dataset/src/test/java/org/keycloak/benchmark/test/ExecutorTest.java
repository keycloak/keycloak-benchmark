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

package org.keycloak.benchmark.test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Ignore;
import org.junit.Test;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ExecutorTest {

    @Test
    @Ignore
    public void testExecutor() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(5);

        List<Future> futures = new ArrayList<>();
        for (int i=0 ; i<100 ; i++) {
            MyRunnable myRunnable = new MyRunnable(i);
            Future future = executor.submit(myRunnable);
            futures.add(future);
        }

        for (Future future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                System.err.println("Exception caught: " + e.getMessage());
                // throw new RuntimeException(e);
            }
        }

    }

    private class MyRunnable implements Runnable {

        private final int workerId;

        public MyRunnable(int workerId) {
            this.workerId = workerId;
        }

        @Override
        public void run() {
            System.out.println(new Date() + ": Worker " + workerId + " starting");
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (workerId %5 == 0) {
                throw new RuntimeException("EXCEPTION FOR WORKER " + workerId);
            }
        }
    }
}
