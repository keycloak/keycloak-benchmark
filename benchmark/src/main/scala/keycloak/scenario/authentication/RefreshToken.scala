package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}

class RefreshToken extends CommonSimulation {

  setUp("Authentication - Refresh Token", new KeycloakScenarioBuilder()
    .openLoginPage(true)
    .loginUsernamePassword()
    .exchangeCode()
    .refreshToken()
    .logout(true))

}
