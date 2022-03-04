/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.library.util.prometheus.metrics;

import com.google.common.collect.Maps;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

@EqualsAndHashCode
@ToString
@Getter
public abstract class Metric {

    private final String name;
    private final Map<String, String> labels;
    private final long timestamp;

    protected Metric(String name, Map<String, String> labels, long timestamp) {
        this.name = name;
        this.labels = Maps.newHashMap(labels);
        this.timestamp = timestamp;
    }

    public abstract Metric sum(Metric m);

    public abstract Double value();
}