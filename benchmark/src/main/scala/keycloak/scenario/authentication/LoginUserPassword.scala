package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class LoginUserPassword extends CommonSimulation {

  Config.preventLocalhostServerUris()

  setUp("Authentication - Login Username/Password", new KeycloakScenarioBuilder()
    .openLoginPage(true)
    .loginUsernamePassword())

}
