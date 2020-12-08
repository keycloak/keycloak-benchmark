package keycloak

import io.gatling.core.Predef._
import keycloak.OIDCScenarioBuilder._
import org.keycloak.performance.TestConfig


class ClientCredentialsSimulation extends CommonSimulation {

  val usersScenario = scenario("Client Credentials").exec(clientCredentialsScenario.chainBuilder)

  setUp(usersScenario.inject(defaultInjectionProfile).protocols(httpDefault))

  .assertions(
    global.failedRequests.count.lessThan(TestConfig.maxFailedRequests + 1),
    global.responseTime.mean.lessThan(TestConfig.maxMeanReponseTime)
  )
    
}
