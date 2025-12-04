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
package org.keycloak.benchmark.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.benchmark.dataset.TaskResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * This tests the deployment of the dataset provider in an integration test.
 * To do this, it expects Keycloak to be extracted in the target folder by the maven-dependency-plugin.
 *
 * @author Alexander Schwartz
 */

public class DeploymentIT {

    private int port;
    private String keycloak;

    /**
     * This can be run from the dataset module folder from the IDE for simple testing.
     */
    public static void main(String[] args) throws IOException, URISyntaxException, ExecutionException, InterruptedException {
        packageProvider();
        new DeploymentIT().startService(args);
    }

    public DeploymentIT() {
        port = 8080;
        keycloak = "http://127.0.0.1:" + port + "/";
    }

    @BeforeEach
    public void setup() throws IOException {
        // use a random free port to allow running the tests while other instances of Keycloak run at the same time
        ServerSocket s = new ServerSocket(0);
        port = s.getLocalPort();
        s.close();
        keycloak = "http://127.0.0.1:" + port + "/";
    }

    @Test
    public void deployWithCurrentStore() throws IOException, ExecutionException, InterruptedException, URISyntaxException {
        runTestWithParameter();
    }

    private void runTestWithParameter(String... args) throws IOException, URISyntaxException, InterruptedException, ExecutionException {
        Path keycloakProvidersFolder = getKeycloakProvidersFolder();
        Path keycloakDatasetProviderJar = getKeycloakDatasetProviderJar();
        clearKeycloak(keycloakProvidersFolder);
        copyDatasetProviderToKeycloak(keycloakDatasetProviderJar, keycloakProvidersFolder);
        Process process = startKeycloak(keycloakProvidersFolder, args);
        try {
            waitForKeycloakStart();
            executeDatasetCommand("create-realms?count=1&clients-per-realm=2&users-per-realm=2");
            waitForDatasetCompleted();
            executeDatasetCommand("create-sessions?count=100&session-expiration-interval=3600");
            waitForDatasetCompleted();
            executeDatasetCommand("remove-realms?remove-all=true&realm-prefix=realm-0");
            waitForDatasetCompleted();
        } finally {
            stopKeycloak(process);
        }
    }

    private void executeDatasetCommand(String command) throws URISyntaxException, IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder().build();
        // waiting for the master realm to be set up; waiting for the health check is not sufficient
        HttpRequest clearStatus = HttpRequest.newBuilder(new URI(keycloak + "realms/master/dataset/status-completed"))
                .DELETE()
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .build();
        HttpResponse<String> clearStatusResponse = httpClient.send(clearStatus, HttpResponse.BodyHandlers.ofString());
        assertTrue(clearStatusResponse.statusCode() == 204 || clearStatusResponse.statusCode() == 404);

        HttpRequest masterStatus = HttpRequest.newBuilder(new URI(keycloak + "realms/master/dataset/" + command))
                .GET()
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .build();
        HttpResponse<String> healthStatus = httpClient.send(masterStatus, HttpResponse.BodyHandlers.ofString());
        assertEquals(healthStatus.statusCode(), 200);
    }

    private void waitForDatasetCompleted() throws URISyntaxException {
        // waiting for the master realm to be set up; waiting for the health check is not sufficient
        HttpRequest masterStatus = HttpRequest.newBuilder(new URI(keycloak + "realms/master/dataset/status-completed"))
                .GET()
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .build();
        HttpClient httpClient = HttpClient.newBuilder().build();
        Awaitility.await().atMost(60, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            HttpResponse<String> healthStatus = httpClient.send(masterStatus, HttpResponse.BodyHandlers.ofString());
            if (healthStatus.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                TaskResponse taskResponse = mapper.readValue(healthStatus.body(), TaskResponse.class);
                return Objects.equals(taskResponse.task().get("success"), "true");
            }
            return false;
        });
    }

    private void waitForKeycloakStart() throws URISyntaxException {
        // waiting for the master realm to be set up; waiting for the health check is not sufficient
        HttpRequest masterStatus = HttpRequest.newBuilder(new URI(keycloak + "realms/master/.well-known/openid-configuration"))
                .GET()
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .build();
        HttpClient httpClient = HttpClient.newBuilder().build();
        Awaitility.await().atMost(60, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            HttpResponse<String> healthStatus = httpClient.send(masterStatus, HttpResponse.BodyHandlers.ofString());
            return healthStatus.statusCode() == 200;
        });
    }

    private void stopKeycloak(Process process) throws ExecutionException, InterruptedException, IOException {
        CompletableFuture<Process> processCompletableFuture = process.onExit();
        if (isWindows()) {
            ProcessBuilder processBuilder = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()));
            Process killProcess = processBuilder.start();
            int exitCodeKill = killProcess.waitFor();
            assertEquals(0, exitCodeKill);
        }
        process.destroy();
        processCompletableFuture.get();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        return os.contains("win");
    }

    private Process startKeycloak(Path keycloakProvidersFolder, String[] args) throws IOException {
        List<String> cli = new ArrayList<>();
        if (isWindows()) {
            Path executable = keycloakProvidersFolder.resolve("..").resolve("bin").resolve("kc.bat").normalize();
            assertTrue(executable.toFile().exists());
            cli.add("cmd.exe");
            cli.add("/c");
            cli.add(executable.toString().replaceAll("/", "\\"));
        } else {
            Path executable = keycloakProvidersFolder.resolve("..").resolve("bin").resolve("kc.sh").normalize();
            assertTrue(executable.toFile().exists());
            cli.add(executable.toString());
        }
        cli.addAll(Arrays.asList("--verbose", "start-dev", "--http-port", Integer.toString(port)));
        cli.addAll(Arrays.asList(args));
        ProcessBuilder processBuilder = new ProcessBuilder(cli);
        processBuilder.environment().put("KEYCLOAK_ADMIN", "admin");
        processBuilder.environment().put("KEYCLOAK_ADMIN_PASSWORD", "admin");
        processBuilder
                .redirectErrorStream(true) // redirect error stream to output stream
                .redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process keycloak = processBuilder.start();
        assertTrue(keycloak.isAlive(), "keycloak should be running");
        return keycloak;
    }

    private void copyDatasetProviderToKeycloak(Path keycloakDatasetProviderJar, Path keycloakProvidersFolder) throws IOException {
        Files.copy(keycloakDatasetProviderJar, keycloakProvidersFolder.resolve(keycloakDatasetProviderJar.getFileName()));
    }

    private void clearKeycloak(Path keycloakProvidersFolder) throws IOException {
        try (Stream<Path> files = Files.list(keycloakProvidersFolder).filter(path -> path.getFileName().toString().endsWith(".jar"))) {
            for (Path path : files.collect(Collectors.toList())) {
                Files.delete(path);
            }
        }
        FileUtils.deleteDirectory(keycloakProvidersFolder.resolve("..").resolve("data").toFile());
    }

    private Path getKeycloakDatasetProviderJar() throws IOException {
        Path target = Path.of("target");
        try (Stream<Path> files = Files.list(target).filter(path -> path.getFileName().toString().startsWith("keycloak-benchmark-dataset") && path.getFileName().toString().endsWith(".jar"))) {
            Iterator<Path> iterator = files.iterator();
            Path provider = iterator.next();
            assumeFalse(iterator.hasNext(), "should only have one sibling");
            return provider;
        }
    }

    private Path getKeycloakProvidersFolder() throws IOException {
        Path keycloakExtracted = Path.of("target", "keycloak");
        try (Stream<Path> files = Files.list(keycloakExtracted)) {
            Iterator<Path> iterator = files.iterator();
            Path keycloak = iterator.next();
            assumeFalse(iterator.hasNext(), "should only have one sibling");
            Path providers = keycloak.resolve("providers");
            assertNotNull(providers);
            return providers;
        }
    }

    /**
     * Use Maven to package the dataset provider.
     * Only used when running it via the main method / from the IDE.
     */
    private static void packageProvider() throws IOException, InterruptedException {
        assertTrue(Path.of("../mvnw").toFile().exists(), "should be run from the module path 'dataset'");
        List<String> cli = new ArrayList<>();
        if (isWindows()) {
            cli.addAll(Arrays.asList("cmd", "/c", "mvnw.cmd"));
        } else {
            cli.add("./mvnw");
        }
        cli.addAll(Arrays.asList("-am", "-pl", "dataset", "package", "-DskipTests"));
        ProcessBuilder processBuilder = new ProcessBuilder(cli);
        processBuilder
                .directory(Path.of("..").toFile())
                .redirectErrorStream(true) // redirect error stream to output stream
                .redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process keycloak = processBuilder.start();
        keycloak.waitFor();
        assertEquals( 0, keycloak.exitValue());
    }

    private void startService(String[] args) throws IOException, ExecutionException, InterruptedException, URISyntaxException {
        Path keycloakProvidersFolder = getKeycloakProvidersFolder();
        Path keycloakDatasetProviderJar = getKeycloakDatasetProviderJar();
        clearKeycloak(keycloakProvidersFolder);
        copyDatasetProviderToKeycloak(keycloakDatasetProviderJar, keycloakProvidersFolder);
        ArrayList<String> allArgs = new ArrayList<>();
        allArgs.add("--debug");
        allArgs.addAll(Arrays.asList(args));
        Process process = startKeycloak(keycloakProvidersFolder, allArgs.toArray(new String[]{}));
        try {
            waitForKeycloakStart();
            process.waitFor();
        } finally {
            stopKeycloak(process);
        }
    }

}
