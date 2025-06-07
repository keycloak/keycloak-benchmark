/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.keycloak.Keycloak;

public class KeycloakServer {

    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", "999.0.0-SNAPSHOT");
    private static final String PROJECT_VERSION = System.getProperty("project.version", "999.0.0-SNAPSHOT");

    public static void main(String[] rawArgs) {
        List<String> args = new ArrayList<>(Arrays.asList(rawArgs));

        if (args.isEmpty()) {
            args.add("start-dev");
        }

        new KeycloakServer().start(args.toArray(new String[0]));
    }

    public void start(String... args) {
        Keycloak keycloak = Keycloak.builder()
                .setVersion(KEYCLOAK_VERSION)
                .addDependency("org.keycloak", "keycloak-benchmark-dataset", PROJECT_VERSION)
                .start(args);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                keycloak.stop();
            } catch (TimeoutException ignore) {
            }
        }));
    }
}
