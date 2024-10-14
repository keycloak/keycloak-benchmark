package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class AuthorizationCode extends CommonSimulation {

  Config.preventLocalhostServerUris()

  setUp("Authentication - Authorization Code Username/Password", new KeycloakScenarioBuilder()
    .openLoginPage(true)
    .loginUsernamePassword()
    .exchangeCode()
    .repeatRefresh()
    .logout(true))

}
