package org.keycloak.benchmark.gatling.chart;

import io.gatling.charts.component.Component;
import io.gatling.charts.component.ComponentLibrary;
import io.gatling.charts.stats.CountsVsTimePlot;
import io.gatling.charts.stats.IntVsTimePlot;
import io.gatling.charts.stats.PercentVsTimePlot;
import io.gatling.charts.stats.PercentilesVsTimePlot;
import io.gatling.charts.stats.PieSlice;
import io.gatling.charts.stats.Series;
import scala.collection.immutable.Seq;

/**
 * This is a dummy component which will show up as empty in Gatling's HTML report.
 * It is a minimal replacement for the Highcharts library which is excluded for licensing restrictions.
 *
 * @author Alexander Schwartz
 */
public class ComponentLibraryDummy implements ComponentLibrary {
    @Override
    public String getAllUsersJs(long runStart, Series<IntVsTimePlot> series) {
        return "";
    }

    @Override
    public Component getActiveSessionsComponent(long runStart, Seq<Series<IntVsTimePlot>> series) {
        return new ComponentDummy();
    }

    @Override
    public Component getRangesComponent(String chartTitle, String eventName, boolean large) {
        return new ComponentDummy();
    }

    @Override
    public Component getRequestCountPolarComponent() {
        return new ComponentDummy();
    }

    @Override
    public Component getDistributionComponent(String title, String yAxisName, Series<PercentVsTimePlot> durationsSuccess, Series<PercentVsTimePlot> durationsFailure) {
        return new ComponentDummy();
    }

    @Override
    public Component getPercentilesOverTimeComponent(String yAxisName, long runStart, Series<PercentilesVsTimePlot> successSeries) {
        return new ComponentDummy();
    }

    @Override
    public Component getRequestsComponent(long runStart, Series<CountsVsTimePlot> counts, Series<PieSlice> pieSeries) {
        return new ComponentDummy();
    }

    @Override
    public Component getResponsesComponent(long runStart, Series<CountsVsTimePlot> counts, Series<PieSlice> pieSeries) {
        return new ComponentDummy();
    }

    @Override
    public Component getResponseTimeScatterComponent(Series<IntVsTimePlot> successData, Series<IntVsTimePlot> failuresData) {
        return new ComponentDummy();
    }
}
