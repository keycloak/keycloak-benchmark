package keycloak.scenario.authentication

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class ClientSecret extends CommonSimulation {

  setUp("Authentication - Client Secret", new KeycloakScenarioBuilder()
    .clientCredentialsGrant()
    .userThinkPause())

}
