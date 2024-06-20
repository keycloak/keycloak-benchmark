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

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class Task {

    public static final String KEY_SUCCESS = "success";
    public static final String KEY_END_TIME_MS = "endTimeMs";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_START_TIME_MS = "startTimeMs";
    private final String taskMessage;
    private final long startTimeMs;
    private Boolean success;
    private Long endTimeMs;

    private Task(String taskMessage, long startTimeMs) {
        this.taskMessage = taskMessage;
        this.startTimeMs = startTimeMs;
    }

    public static Task start(String startMessage) {
        return new Task(startMessage, Time.currentTimeMillis());
    }

    public static Task fromMap(Map<String, String> map) {
        Task task = new Task(map.get(KEY_MESSAGE), Long.parseLong(map.get(KEY_START_TIME_MS)));
        if (map.get(KEY_SUCCESS) != null) {
            task.success = Boolean.parseBoolean(map.get(KEY_SUCCESS));
        }
        if (map.get(KEY_END_TIME_MS) != null) {
            task.endTimeMs = Long.parseLong(map.get(KEY_END_TIME_MS));
        }
        return task;
    }

    public Map<String, String> toMap() {
        Map<String, String> result = new HashMap<>();
        result.put(KEY_MESSAGE, taskMessage);
        result.put(KEY_START_TIME_MS, Long.toString(startTimeMs));
        if (endTimeMs != null) {
            result.put(KEY_END_TIME_MS, Long.toString(endTimeMs));
        }
        if (success != null) {
            result.put(KEY_SUCCESS, Boolean.toString(success));
        }
        return result;
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
        boolean running = endTimeMs == null;
        Long endTimeMs = this.endTimeMs;

        if (running) {
            endTimeMs = Time.currentTimeMillis();
        }

        return String.format("%s, running: %s, time: %ss, started: %s, ended: %s", taskMessage, running, Duration.ofMillis(endTimeMs - startTimeMs).toSeconds(), new Date(startTimeMs), new Date(endTimeMs));
    }

    public Boolean isSuccess() {
        return success;
    }

    public void SetSuccess(boolean success) {
        this.success = success;
        this.endTimeMs = Time.currentTimeMillis();
    }
}
