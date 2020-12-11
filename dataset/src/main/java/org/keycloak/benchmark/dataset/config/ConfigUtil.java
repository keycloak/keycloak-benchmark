/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.keycloak.benchmark.dataset.config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Function;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.HttpRequest;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ConfigUtil {

    private static final Logger logger = Logger.getLogger(ConfigUtil.class);


    /**
     * Create the config class based on the HTTP query parameters and the defaults.
     *
     * @param httpRequest
     * @param operation
     * @return
     */
    public static DatasetConfig createConfigFromQueryParams(HttpRequest httpRequest, DatasetOperation operation) {
        DatasetConfig config = new DatasetConfig();

        StringBuilder toString = new StringBuilder("DatasetConfig [ ");

        for (Field f : DatasetConfig.class.getDeclaredFields()) {
            QueryParamFill qpf = f.getAnnotation(QueryParamFill.class);
            if (qpf != null) {
                boolean applicable = Arrays.asList(qpf.operations()).contains(operation);
                if (!applicable) continue;

                String val = httpRequest.getUri().getQueryParameters().getFirst(qpf.paramName());
                if (val == null) {
                    if (qpf.required()) {
                        throw new DatasetException("Required parameter '" + qpf.paramName() + "' missing");
                    }
                    val = qpf.defaultValue();
                }
                f.setAccessible(true);
                try {
                    f.set(config, val);
                } catch (Exception e) {
                    throw new DatasetException("Failed to fill the field '" + qpf.paramName() + "'", e);
                }
                toString.append("\n " + qpf.paramName() + ": " + val);
            }

            QueryParamIntFill qpfInt = f.getAnnotation(QueryParamIntFill.class);
            if (qpfInt != null) {
                boolean applicable = Arrays.asList(qpfInt.operations()).contains(operation);
                if (!applicable) continue;

                String valStr = httpRequest.getUri().getQueryParameters().getFirst(qpfInt.paramName());
                Integer val;
                if (valStr == null) {
                    if (qpfInt.required()) {
                        throw new DatasetException("Required parameter '" + qpfInt.paramName() + "' missing");
                    }
                    val = qpfInt.defaultValue();
                } else {
                    val = Integer.parseInt(valStr);
                }
                f.setAccessible(true);
                try {
                    f.set(config, val);
                } catch (Exception e) {
                    throw new DatasetException("Failed to fill the field '" + qpfInt.paramName() +"'", e);
                }
                toString.append("\n " + qpfInt.paramName() + ": " + val);
            }
        }
        toString.append("\n]");
        config.setToString(toString.toString());

        return config;
    }


    /**
     * Find the first available index where the new entities can be created.
     *
     * For example if there are already realms "realm0, realm1, ... realm157", then this method will return 158, which means
     * that we can start creating realms from "realm158" and bigger.
     *
     * @param finder
     * @return
     */
    public static int findFreeEntityIndex(Function<Integer, Boolean> finder) {
        int lastFound = -1;
        int lastFailed = -1;
        int current = 100;

        // Try to find very first entity
        if (finder.apply(0)) {
            lastFound = 0;
        } else {
            return 0;
        }

        while (lastFailed == -1 || (lastFailed - lastFound > 1)) {
            //logger.info("Current: " + current);
            boolean found = finder.apply(current);
            if (found) {
                lastFound = current;
            } else {
                lastFailed = current;
            }
            if (lastFailed == -1) {
                current = current * 2;
            } else {
                current = (lastFound + lastFailed) / 2;
            }
        }

        return lastFailed;
    }
}
