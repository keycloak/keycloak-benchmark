package org.keycloak.benchmark.crossdc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TagDescription;
import software.amazon.awssdk.services.globalaccelerator.GlobalAcceleratorClient;
import software.amazon.awssdk.services.globalaccelerator.model.Accelerator;
import software.amazon.awssdk.services.globalaccelerator.model.EndpointConfiguration;
import software.amazon.awssdk.services.globalaccelerator.model.EndpointDescription;
import software.amazon.awssdk.services.globalaccelerator.model.EndpointGroup;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.GetHealthCheckRequest;
import software.amazon.awssdk.services.route53.model.HealthCheck;
import software.amazon.awssdk.services.route53.model.UpdateHealthCheckRequest;
import software.amazon.awssdk.utils.builder.SdkBuilder;

public class AWSClient {

   private static final Logger LOG = Logger.getLogger(AWSClient.class);

   public static long getLambdaInvocationCount(String name, String region, Instant startTime) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           CloudWatchClient cloudWatch = CloudWatchClient.builder()
                 .region(Region.of(region))
                 .httpClient(httpClient)
                 .build()) {

         var metric = Metric.builder()
               .namespace("AWS/Lambda")
               .metricName("Invocations")
               .dimensions(
                     Dimension.builder()
                           .name("FunctionName")
                           .value(name)
                           .build()
               )
               .build();

         var metricStat = MetricStat.builder()
               .metric(metric)
               .period(1)
               .stat(Statistic.SUM.toString())
               .build();

         var metricQuery = MetricDataQuery.builder()
               .id("invocations")
               .metricStat(metricStat)
               .build();

         var metricDataReq = GetMetricDataRequest.builder()
               .metricDataQueries(metricQuery)
               .startTime(startTime)
               .endTime(Instant.now())
               .build();

         var metricsDataResults = cloudWatch.getMetricData(metricDataReq).metricDataResults();
         assertEquals(metricsDataResults.size(), 1);

         var metrics = metricsDataResults.get(0).values();
         System.out.println(metrics);
         return metrics
               .stream()
               .collect(Collectors.summarizingInt(Double::intValue))
               .getSum();
      }
   }

   public static String getHealthCheckId(String domainName) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           Route53Client route53 = Route53Client.builder().httpClient(httpClient).build()) {
         for (HealthCheck hc : route53.listHealthChecks(SdkBuilder::build).healthChecks()) {
            if (domainName.equals(hc.healthCheckConfig().fullyQualifiedDomainName())) {
               LOG.infof("Found Route53 HealthCheck '%s' for Domain='%s'", hc.id(), domainName);
               return hc.id();
            }
         }
      }
      return null;
   }

   public static void updateRoute53HealthCheckPath(String healthCheckId, String path) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           Route53Client route53 = Route53Client.builder().httpClient(httpClient).build()) {

         LOG.infof("Updating Route53 HealthCheck '%s' to path='%s'", healthCheckId, path);
         route53.updateHealthCheck(
               UpdateHealthCheckRequest.builder()
                     .healthCheckId(healthCheckId)
                     .resourcePath(path)
                     .build()
         );
      }
   }

   public static String getRoute53HealthCheckPath(String healthCheckId) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           Route53Client route53 = Route53Client.builder().httpClient(httpClient).build()) {

         return route53.getHealthCheck(
               GetHealthCheckRequest.builder()
                     .healthCheckId(healthCheckId)
                     .build()
         ).healthCheck().healthCheckConfig().resourcePath();
      }
   }

   public static void waitForTheHealthCheckToBeInState(String healthCheckId, StateValue stateValue) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           CloudWatchClient cloudWatch = CloudWatchClient.builder().region(Region.US_EAST_1).httpClient(httpClient).build()) {
         LOG.infof("Waiting for CloudWatch Alarm '%s' to be in state %s", healthCheckId, stateValue);
         cloudWatch.waiter().waitUntilAlarmExists(
               DescribeAlarmsRequest.builder()
                     .alarmNames(healthCheckId)
                     .stateValue(stateValue)
                     .build(),
               WaiterOverrideConfiguration.builder()
                     .maxAttempts(150) // by default this is 40 and it seems it takes precedence before 10 minutes
                     .waitTimeout(Duration.ofMinutes(10))
                     .build()
         );
      }
   }

   public static Accelerator getAccelerator(GlobalAcceleratorClient gaClient, String acceleratorDns) {
      return gaClient.listAccelerators().accelerators()
            .stream()
            .filter(a -> acceleratorDns.contains(a.dnsName()))
            .findFirst()
            .orElseThrow();
   }

   public static AcceleratorMetadata getAcceleratorMeta(String acceleratorDns) {
      return acceleratorClient((httpClient, gaClient) -> getAcceleratorMeta(gaClient, acceleratorDns));
   }

   public static AcceleratorMetadata getAcceleratorMeta(GlobalAcceleratorClient gaClient, String acceleratorDns) {
      Accelerator accelerator = getAccelerator(gaClient, acceleratorDns);
      return new AcceleratorMetadata(
            accelerator.name(),
            getEndpointGroup(gaClient, accelerator)
      );
   }

   public static void acceleratorFallback(String acceleratorDns) {
      acceleratorClient((httpClient, gaClient) -> {
         // Retrieve Accelerator instance based upon DNS
         var acceleratorMeta = getAcceleratorMeta(gaClient, acceleratorDns);
         var endpointGroup = acceleratorMeta.endpointGroup;
         var region = endpointGroup.endpointGroupRegion();

         List<String> endpoints;
         try (ElasticLoadBalancingV2Client elbClient =
                    ElasticLoadBalancingV2Client.builder()
                          .region(Region.of(region))
                          .httpClient(httpClient)
                          .build()
         ) {
            // Get all LBs associated with the Accelerator
            var elbs = elbClient.describeLoadBalancers()
                  .loadBalancers()
                  .stream()
                  .filter(lb -> lb.type() == LoadBalancerTypeEnum.NETWORK)
                  .map(LoadBalancer::loadBalancerArn)
                  .toList();

            endpoints = elbClient.describeTags(b -> b.resourceArns(elbs))
                  .tagDescriptions()
                  .stream()
                  .filter(td -> td.tags()
                        .contains(
                              Tag.builder()
                                    .key("accelerator")
                                    .value(acceleratorMeta.name())
                                    .build()
                        )
                  )
                  .map(TagDescription::resourceArn)
                  .toList();
         }

         var endpointConfigs = endpoints.stream()
               .map(elb -> EndpointConfiguration.builder()
                     .clientIPPreservationEnabled(false)
                     .endpointId(elb)
                     .weight(128)
                     .build()
               ).toList();

         // Add all LBs to the Accelerator EndpointGroup
         return gaClient.updateEndpointGroup(
               g -> g.endpointGroupArn(endpointGroup.endpointGroupArn())
                     .endpointConfigurations(endpointConfigs)
         );
      });
   }

   private static EndpointGroup getEndpointGroup(GlobalAcceleratorClient gaClient, Accelerator accelerator) {
      var listenerArn = gaClient.listListeners(b -> b.acceleratorArn(accelerator.acceleratorArn()))
            .listeners()
            .stream()
            .findFirst()
            .orElseThrow()
            .listenerArn();

      return gaClient.listEndpointGroups(b -> b.listenerArn(listenerArn))
            .endpointGroups()
            .stream()
            .findFirst()
            .orElseThrow();
   }

   public static List<String> getAcceleratorEndpoints(String acceleratorDns) {
      return getAcceleratorMeta(acceleratorDns)
            .endpointGroup
            .endpointDescriptions()
            .stream()
            .map(EndpointDescription::endpointId)
            .toList();
   }

   private static <T> T acceleratorClient(BiFunction<SdkHttpClient, GlobalAcceleratorClient, T> fn) {
      try (
            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            GlobalAcceleratorClient gaClient = GlobalAcceleratorClient.builder()
                  .region(Region.US_WEST_2)
                  .httpClient(httpClient)
                  .build()
      ) {
         return fn.apply(httpClient, gaClient);
      }
   }

   public record AcceleratorMetadata(String name, EndpointGroup endpointGroup) {
   }
}
