package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}

class AuthCodeWithRefreshToken extends CommonSimulation {

  setUp("Authentication - Authorization Code Username/Password with Refresh Token", new KeycloakScenarioBuilder()
    .openLoginPage(true)
    .loginUsernamePassword()
    .exchangeCode()
    .repeatRefresh()
    .logout(true)
  )

}
