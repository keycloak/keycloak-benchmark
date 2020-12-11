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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class TaskResponse {

    @JsonProperty("error")
    private String error;

    @JsonProperty("status")
    private String status;

    @JsonProperty("task-id")
    private String taskId;

    @JsonProperty("task-status-url")
    private String taskStatusUrl;

    public TaskResponse() {
    }

    public TaskResponse(String error, String status, String taskId, String taskStatusUrl) {
        this.error = error;
        this.status = status;
        this.taskId = taskId;
        this.taskStatusUrl = taskStatusUrl;
    }

    public static TaskResponse error(String errorMessage) {
        return new TaskResponse(errorMessage, null, null, null);
    }

    public static TaskResponse errorSomeTaskInProgress(String taskInProgress, String taskStatusUrl) {
        return new TaskResponse("Task not triggered. There is already existing task in progress. See task-id for details about existing task", null, taskInProgress, taskStatusUrl);
    }

    public static TaskResponse taskStarted(String taskMessage, String taskStatusUrl) {
        return new TaskResponse(null, "Task started successfully", taskMessage, taskStatusUrl);
    }

    public static TaskResponse existingTaskStatus(String taskMessage) {
        if (taskMessage == null) {
            throw new IllegalStateException("Illegal to call with null argument");
        }

        return new TaskResponse(null, "Task in progress", taskMessage, null);
    }

    public static TaskResponse noTaskInProgress() {
        return new TaskResponse(null, "No task in progress. New task can be started", null, null);
    }


    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTaskStatusUrl() {
        return taskStatusUrl;
    }

    public void setTaskStatusUrl(String taskStatusUrl) {
        this.taskStatusUrl = taskStatusUrl;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
