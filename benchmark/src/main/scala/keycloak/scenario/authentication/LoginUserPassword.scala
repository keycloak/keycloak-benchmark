package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}

class LoginUserPassword extends CommonSimulation {

  setUp("Authentication - Login Username/Password", new KeycloakScenarioBuilder()
    .openLoginPage(true)
    .loginUsernamePassword())

}
