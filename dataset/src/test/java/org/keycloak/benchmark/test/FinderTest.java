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

package org.keycloak.benchmark.test;

import java.util.function.Function;

import org.junit.Assert;
import org.junit.Test;
import org.keycloak.benchmark.dataset.config.ConfigUtil;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class FinderTest {

    @Test
    public void testFinder() {
        assertFinder(-1);
        assertFinder(263);
        assertFinder(100);
        assertFinder(2345);
        assertFinder(41);
        assertFinder(77581);
    }

    private void assertFinder(int index) {
        SimpleFinder simpleFinder = new SimpleFinder(index);
        Assert.assertEquals(index + 1, ConfigUtil.findFreeEntityIndex(simpleFinder));
    }


    private static class SimpleFinder implements Function<Integer, Boolean> {

        private final int lastEntityIndex;

        public SimpleFinder(int lastIndex) {
            this.lastEntityIndex = lastIndex;
        }

        @Override
        public Boolean apply(Integer index) {
            return index <= this.lastEntityIndex;
        }
    }


}
