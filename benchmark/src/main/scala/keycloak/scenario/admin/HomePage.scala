package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}

class HomePage extends CommonSimulation {

  setUp("Admin Console - Home Page", new KeycloakScenarioBuilder()
    .openHomePage(false))

}
