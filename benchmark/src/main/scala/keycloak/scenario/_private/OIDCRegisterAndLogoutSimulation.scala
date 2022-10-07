package keycloak.scenario._private

import io.gatling.core.Predef._
import keycloak.scenario.CommonSimulation
import keycloak.scenario.KeycloakScenarioBuilder._
import org.keycloak.benchmark.Config


class OIDCRegisterAndLogoutSimulation extends CommonSimulation {

  val usersScenario = scenario("Registering Users").exec(registerAndLogoutScenario.chainBuilder)

  setUp(usersScenario.inject(rampUsersPerSec(0.001) to Config.usersPerSec during (Config.rampUpPeriod),
    constantUsersPerSec(Config.usersPerSec) during (Config.warmUpPeriod + Config.measurementPeriod)).protocols(defaultHttpProtocol()))

    .assertions(
      global.failedRequests.percent.lte(Config.maxErrorPercentage),
      global.responseTime.mean.lte(Config.maxMeanResponseTime)
    )

}
