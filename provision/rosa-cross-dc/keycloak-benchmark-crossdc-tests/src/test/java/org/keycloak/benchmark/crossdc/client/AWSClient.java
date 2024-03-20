package org.keycloak.benchmark.crossdc.client;

import java.time.Duration;

import org.jboss.logging.Logger;
import org.keycloak.benchmark.crossdc.AbstractCrossDCTest;

import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.HealthCheck;
import software.amazon.awssdk.services.route53.model.UpdateHealthCheckRequest;
import software.amazon.awssdk.utils.builder.SdkBuilder;

public class AWSClient {

   private static final Logger LOG = Logger.getLogger(AWSClient.class);

   public static void updateRoute53HealthCheckPath(String domainName, String path) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           Route53Client route53 = Route53Client.builder().httpClient(httpClient).build();
           CloudWatchClient cloudWatch = CloudWatchClient.builder().region(Region.US_EAST_1).httpClient(httpClient).build()) {

         String healthCheckId = null;
         for (HealthCheck hc : route53.listHealthChecks(SdkBuilder::build).healthChecks()) {
            if (domainName.equals(hc.healthCheckConfig().fullyQualifiedDomainName())) {
               healthCheckId = hc.id();
               break;
            }
         }
         LOG.infof("Updating Route53 HealthCheck '%s' for Domain='%s' to path='%s'", healthCheckId, domainName, path);
         route53.updateHealthCheck(
               UpdateHealthCheckRequest.builder()
                     .healthCheckId(healthCheckId)
                     .resourcePath(path)
                     .build()
         );

         // Wait for the HealthCheck Alarm to be in the OK state
         LOG.infof("Waiting for CloudWatch Alarm '%s' to be in state OK", healthCheckId);
         cloudWatch.waiter().waitUntilAlarmExists(
               DescribeAlarmsRequest.builder()
                     .alarmNames(healthCheckId)
                     .stateValue(StateValue.OK)
                     .build(),
               WaiterOverrideConfiguration.builder()
                     .waitTimeout(Duration.ofMinutes(10))
                     .build()
         );
      }
   }
}
