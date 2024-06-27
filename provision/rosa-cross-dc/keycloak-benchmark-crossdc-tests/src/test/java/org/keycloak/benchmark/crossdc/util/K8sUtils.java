package org.keycloak.benchmark.crossdc.util;

import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.client.KubernetesClient;

public class K8sUtils {
   public static void scaleDeployment(KubernetesClient k8s, String name, String namespace, int replicas) {
      k8s.apps()
            .deployments()
            .inNamespace(namespace)
            .withName(name)
            .scale(replicas);

      k8s.apps()
            .deployments()
            .inNamespace(namespace)
            .withName(name)
            .waitUntilReady(30, TimeUnit.SECONDS);
   }
}
