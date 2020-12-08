package keycloak

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import keycloak.OIDCScenarioBuilder._

import org.keycloak.performance.TestConfig


class OIDCRegisterAndLogoutSimulation extends CommonSimulation {

  val usersScenario = scenario("Registering Users").exec(registerAndLogoutScenario.chainBuilder)

  setUp(usersScenario.inject(defaultInjectionProfile).protocols(httpDefault))

  .assertions(
    global.failedRequests.count.lessThan(TestConfig.maxFailedRequests + 1),
    global.responseTime.mean.lessThan(TestConfig.maxMeanReponseTime)
  )

}
