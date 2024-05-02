package org.keycloak.benchmark.crossdc.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PropertyUtils {
   public static String getRequired(String property) {
      var prop = System.getProperty(property);
      assertNotNull(prop, String.format("Property '%s' must be set", property));
      return prop;
   }
}
