package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}

class AuthorizationCode extends CommonSimulation {

  setUp("Authentication - Authorization Code Username/Password", new KeycloakScenarioBuilder()
    .openLoginPage(true)
    .loginUsernamePassword()
    .exchangeCode()
    .repeatRefresh()
    .logout(true))

}
