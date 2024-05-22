package org.keycloak.benchmark.gatling.chart;

import io.gatling.charts.component.Component;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import java.util.Collections;

/**
 * This is a dummy component which will show up as empty in Gatling's HTML report.
 * It is a minimal replacement for the Highcharts library which is excluded for licensing restrictions.

 * @author Alexander Schwartz
 */
public class ComponentDummy implements Component {
    @Override
    public String html() {
        return "";
    }

    @Override
    public String js() {
        return "";
    }

    @Override
    public Seq<String> jsFiles() {
        return CollectionConverters.asScala(Collections.<String>emptyIterator()).toSeq();
    }
}
