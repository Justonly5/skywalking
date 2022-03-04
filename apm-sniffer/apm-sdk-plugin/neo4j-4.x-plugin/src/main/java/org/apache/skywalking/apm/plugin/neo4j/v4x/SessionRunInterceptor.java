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
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.plugin.neo4j.v4x.Neo4jPluginConfig.Plugin.Neo4j;
import org.apache.skywalking.apm.plugin.neo4j.v4x.util.CypherUtils;
import org.neo4j.driver.Query;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;

/**
 * This interceptor do the following steps:
 * <pre>
 * 1. Create exit span before method, and set related tags.
 * 2. Call {@link AbstractSpan#prepareForAsync()} and {@link ContextManager#stopSpan()} method.
 * 3. Save span into {@link SessionRequiredInfo} object.
 * 4. Return a new CompletionStage after method and async finish the span.
 * </pre>
 */
public class SessionRunInterceptor implements InstanceMethodsAroundInterceptor {

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
            MethodInterceptResult result) throws Throwable {
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
            Object ret) throws Throwable {
        return ((CompletionStage<?>) ret).thenApply(resultCursor -> {
            Query query = (Query) allArguments[0];
            SessionRequiredInfo requiredInfo = (SessionRequiredInfo) objInst.getSkyWalkingDynamicField();
            if (query == null || requiredInfo == null || requiredInfo.getSpan() == null) {
                return resultCursor;
            }

            final AbstractSpan span = requiredInfo.getSpan();
            span.setOperationName("Neo4j/Session/" + method.getName());
            Tags.DB_STATEMENT.set(span, CypherUtils.limitBodySize(query.text()));
            if (Neo4j.TRACE_CYPHER_PARAMETERS) {
                Neo4jPluginConstants.CYPHER_PARAMETERS_TAG
                        .set(span, CypherUtils.limitParametersSize(query.parameters().toString()));
            }
            span.asyncFinish();
            return resultCursor;
        });
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
            Class<?>[] argumentsTypes, Throwable t) {
        SessionRequiredInfo requiredInfo = (SessionRequiredInfo) objInst.getSkyWalkingDynamicField();
        if (requiredInfo != null) {
            requiredInfo.getSpan().log(t);
        }
    }
}
