package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class AuthCodeWithRefreshToken extends CommonSimulation {

  setUp("Authentication - Authorization Code Username/Password with Refresh Token", new KeycloakScenarioBuilder()
    .openLoginPage(false/*pauseAfter*/)
    .loginUsernamePassword()
    .exchangeCode()
    .repeatRefresh(Config.refreshTokenCount, Config.refreshTokenPeriod)
    .logout(true)
  )

}
