package keycloak.scenario

import io.gatling.commons.validation.Validation
import io.gatling.core.Predef._
import io.gatling.http.Predef.{http, Proxy}
import org.keycloak.benchmark.gatling.log.LogProcessor
import org.keycloak.benchmark.Config
import org.keycloak.benchmark.WorkloadModel

import scala.Console.println


/**
 * @author tomas Kyjovsky &lt;tkyjovsk@redhat.com&gt;
 */
abstract class CommonSimulation extends Simulation {

  def toStringSimulationProperties : String = {
    " Name: " + getClass.getName
  }

  def rampDownNotStarted(): Validation[Boolean] = {
    System.currentTimeMillis < Config.measurementEndTime
  }

  def defaultHttpProtocol() = {
    var default = http
      .acceptHeader("application/json")
      .disableFollowRedirect

    if (Config.httpProxy) {

      default = default.proxy(Proxy(Config.httpProxyHost, Config.httpProxyPort))

    }

    if (Config.shareConnections) {
      // When a local system cannot handle a large number of connections, using shared connections
      // may help by sharing existing connections among multiple users.  However, this sharing will
      // occur if while there are pauses within a scenario, allowing such an opportunity.
      default = default.shareConnections
      // when sharing connections across users, the number of connections in the pool shouldn't be limited
      // to the default of 6 connections when using HTTP/1.1.
      default = default.maxConnectionsPerHost(9999)
    }

    if (Config.useAllLocalAddresses) {
      // since the test may involve tens of thousands of connections from a single testing
      // system to a single host:port on a server, we may need to add additional addresses to
      // increase the number of TCP connections we can make and this enables the use of those.

      // Don't use this by default, as it has side effects when accessing localhost which has an IPv6 address,
      // while the system itself doesn't have an IPv6 address, and it will report
      //     Can't connect to IPv6 remote localhost/[0:0:0:0:0:0:0:1]:8180 + from IPv4 local one /xxx.xxx.xxx.xxx:0`
      // A different remedy might be to disable IPv6 address resolution, as described in https://github.com/gatling/gatling/issues/2013,
      // but that would have other implications.
      default = default.useAllLocalAddresses
    }

    if (Config.inferHtmlResources) {
      default.inferHtmlResources()
    }

    default
  }

  def setUp(name: String, builder : KeycloakScenarioBuilder): Unit = {
    val scn = scenario(name).exec(builder.chainBuilder)
    setUp(
      (
        if (Config.workloadModel == WorkloadModel.CLOSED)
          scn.inject(
            rampConcurrentUsers(0) to (Config.concurrentUsers) during(Config.rampUpPeriod),
            constantConcurrentUsers(Config.concurrentUsers) during(Config.warmUpPeriod + Config.measurementPeriod)
          )
        else
          scn.inject(
            rampUsersPerSec(0.001) to Config.usersPerSec during (Config.rampUpPeriod),
            constantUsersPerSec(Config.usersPerSec) during (Config.warmUpPeriod + Config.measurementPeriod)
          )
      ).protocols(defaultHttpProtocol())
    ).assertions(
      global.failedRequests.percent.lte(Config.maxErrorPercentage),
      global.responseTime.mean.lte(Config.maxMeanResponseTime)
    )

  }

  before {
    println("==========================================================")
    Console.println("Target servers: " + Config.serverUrisList)
    Console.println("Scenario:\n" + toStringSimulationProperties);
    Console.println("Population:\n" + Config.toStringPopulationConfig);
    Console.println("Runtime:\n" + Config.toStringRuntimeParameters);
    println("Service Level Agreements:\n" + Config.toStringSLA)
    println("Timestamps: \n" + Config.toStringTimestamps)
    Config.validateConfiguration
    println("==========================================================")
  }

  after {
    if (Config.filterResults) {
      new LogProcessor(getClass).filterLog(
        Config.measurementStartTime,
        Config.measurementEndTime,
        false, false)
    }
  }

}
