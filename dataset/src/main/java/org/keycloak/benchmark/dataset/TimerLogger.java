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

import java.util.Date;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class TimerLogger {

    private final String taskMessage;
    private final long startTimeMs;

    private TimerLogger(String taskMessage, long startTimeMs) {
        this.taskMessage = taskMessage;
        this.startTimeMs = startTimeMs;
    }


    public static TimerLogger start(String startMessage) {
        return new TimerLogger(startMessage, Time.currentTimeMillis());
    }

    public void info(Logger logger, String event, Object... params) {
        String log = String.format(event, params);
        long timeMs = Time.currentTimeMillis() - startTimeMs;
        logger.infof(log + ", Time since start of the task '%s': %d ms", taskMessage, timeMs);
    }

    public void debug(Logger logger, String event, Object... params) {
        String log = String.format(event, params);
        long timeMs = Time.currentTimeMillis() - startTimeMs;
        logger.debugf(log + ", Time since of the operation '%s': %d ms", taskMessage, timeMs);
    }

    public String getTaskMessage() {
        return taskMessage;
    }

    @Override
    public String toString() {
        return String.format("%s, started: %s", taskMessage, new Date(startTimeMs).toString());
    }


}
