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

package org.apache.skywalking.apm.plugin.neo4j.v4x;

import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.MockContextSnapshot;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.test.helper.SegmentHelper;
import org.apache.skywalking.apm.agent.test.tools.*;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.neo4j.v4x.Neo4jPluginConfig.Plugin.Neo4j;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.neo4j.driver.Query;
import org.neo4j.driver.Value;
import org.neo4j.driver.internal.BoltServerAddress;
import org.neo4j.driver.internal.DatabaseName;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.value.MapValue;
import org.neo4j.driver.internal.value.StringValue;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.apache.skywalking.apm.network.trace.component.ComponentsDefine.NEO4J;
import static org.apache.skywalking.apm.plugin.neo4j.v4x.Neo4jPluginConstants.DB_TYPE;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TracingSegmentRunner.class)
@SuppressWarnings("unchecked")
public class TransactionRunInterceptorTest {

    private final Method method = MockMethod.class.getMethod("runAsync");
    @Rule
    public AgentServiceRule serviceRule = new AgentServiceRule();
    @SegmentStoragePoint
    private SegmentStorage segmentStorage;
    private TransactionRunInterceptor transactionRunInterceptor;
    @Mock
    private Query query;
    @Mock
    private Connection connection;
    @Mock
    private BoltServerAddress boltServerAddress;
    @Mock
    private DatabaseName databaseName;
    private MockUnmanagedTransaction mockUnmanagedTransaction;

    public TransactionRunInterceptorTest() throws NoSuchMethodException {
    }

    @Before
    public void setUp() throws Exception {
        transactionRunInterceptor = new TransactionRunInterceptor();
        when(boltServerAddress.toString()).thenReturn("127.0.0.1:7687");
        when(connection.serverAddress()).thenReturn(boltServerAddress);
        when(databaseName.databaseName()).thenReturn(Optional.of("neo4j"));
        when(connection.databaseName()).thenReturn(databaseName);
        when(query.text()).thenReturn("Match (m:Movie)-[a:ACTED_IN]-(p:Person) RETURN m,a,p");
        Map<String, Value> valueMap = new HashMap<>();
        valueMap.put("name", new StringValue("John"));
        when(query.parameters()).thenReturn(new MapValue(valueMap));
        Neo4j.TRACE_CYPHER_PARAMETERS = false;
        mockUnmanagedTransaction = new MockUnmanagedTransaction(connection, null, 10L);
        SessionRequiredInfo requiredInfo = new SessionRequiredInfo();
        requiredInfo.setContextSnapshot(MockContextSnapshot.INSTANCE.mockContextSnapshot());
        final AbstractSpan span = ContextManager.createExitSpan("Neo4j", connection.serverAddress().toString());
        Tags.DB_TYPE.set(span, DB_TYPE);
        Tags.DB_INSTANCE.set(span, connection.databaseName().databaseName().orElse(Neo4jPluginConstants.EMPTY_STRING));
        span.setComponent(NEO4J);
        SpanLayer.asDB(span);
        ContextManager.continued(requiredInfo.getContextSnapshot());
        span.prepareForAsync();
        ContextManager.stopSpan();
        requiredInfo.setSpan(span);
        mockUnmanagedTransaction.setSkyWalkingDynamicField(requiredInfo);
    }

    @Test
    public void testWithNoSpan() throws Throwable {
        ((SessionRequiredInfo) mockUnmanagedTransaction.getSkyWalkingDynamicField()).setSpan(null);
        transactionRunInterceptor
                .beforeMethod(mockUnmanagedTransaction, method, new Object[]{null}, new Class[0], null);
        final CompletionStage<String> result = (CompletionStage<String>) transactionRunInterceptor
                .afterMethod(mockUnmanagedTransaction, method, new Object[]{null}, new Class[0],
                        CompletableFuture.completedFuture("result"));
        assertThat(result.toCompletableFuture().get(), is("result"));
        final List<TraceSegment> traceSegments = segmentStorage.getTraceSegments();
        assertThat(traceSegments.size(), is(0));
    }

    @Test
    public void testWithQuery() throws Throwable {
        doInvokeInterceptorAndAssert();
    }

    @Test
    public void testTraceQueryParameters() throws Throwable {
        Neo4j.TRACE_CYPHER_PARAMETERS = true;
        doInvokeInterceptorAndAssert();
    }

    private void doInvokeInterceptorAndAssert() throws Throwable {
        transactionRunInterceptor
                .beforeMethod(mockUnmanagedTransaction, method, new Object[]{query}, new Class[0], null);
        final CompletionStage<String> result = (CompletionStage<String>) transactionRunInterceptor
                .afterMethod(mockUnmanagedTransaction, method, new Object[]{query}, new Class[]{Query.class},
                        CompletableFuture.completedFuture("result"));

        assertThat(result.toCompletableFuture().get(), is("result"));
        final List<TraceSegment> traceSegments = segmentStorage.getTraceSegments();
        assertThat(traceSegments.size(), is(1));
        final List<AbstractTracingSpan> spans = SegmentHelper.getSpans(traceSegments.get(0));
        assertNotNull(spans);
        assertThat(spans.size(), is(1));
        assertSpan(spans.get(0));
    }

    private void assertSpan(final AbstractTracingSpan span) {
        SpanAssert.assertLayer(span, SpanLayer.DB);
        SpanAssert.assertComponent(span, ComponentsDefine.NEO4J);
        if (Neo4j.TRACE_CYPHER_PARAMETERS) {
            SpanAssert.assertTagSize(span, 4);
            SpanAssert.assertTag(span, 3, "{name: \"John\"}");
        } else {
            SpanAssert.assertTagSize(span, 3);
        }
        SpanAssert.assertTag(span, 0, DB_TYPE);
        SpanAssert.assertTag(span, 1, "neo4j");
        SpanAssert.assertTag(span, 2, "Match (m:Movie)-[a:ACTED_IN]-(p:Person) RETURN m,a,p");
        assertTrue(span.isExit());
        assertThat(span.getOperationName(), is("Neo4j/Transaction/runAsync"));
    }
}