package keycloak.scenario.admin

import io.gatling.core.Predef._
import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class UserCrawl extends CommonSimulation {

  val userCrawlScenarioBuilder = new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .viewPagesOfUsers(Config.userPageSize, Config.userNumberOfPages)

  val userCrawlScenario = scenario("User Crawl").exec(userCrawlScenarioBuilder.chainBuilder)

  setUp(userCrawlScenario.inject(constantConcurrentUsers(1) during Config.measurementPeriod)
    .protocols(defaultHttpProtocol()))

    .assertions(
      global.failedRequests.percent.lte(Config.maxErrorPercentage),
      global.responseTime.mean.lte(Config.maxMeanResponseTime)
    )

}
