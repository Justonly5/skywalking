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

package org.apache.skywalking.oap.server.storage.plugin.elasticsearch.query;

import org.apache.skywalking.oap.server.core.query.PaginationUtils;
import org.apache.skywalking.oap.server.core.query.enumeration.Order;
import org.apache.skywalking.oap.server.core.query.input.Duration;
import org.apache.skywalking.oap.server.core.query.type.event.EventQueryCondition;
import org.apache.skywalking.oap.server.core.query.type.event.EventType;
import org.apache.skywalking.oap.server.core.query.type.event.Events;
import org.apache.skywalking.oap.server.core.query.type.event.Source;
import org.apache.skywalking.oap.server.core.source.Event;
import org.apache.skywalking.oap.server.core.storage.query.IEventQueryDAO;
import org.apache.skywalking.oap.server.library.client.elasticsearch.ElasticSearchClient;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.IndexController;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.MatchCNameBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.isNull;

public class ESEventQueryDAO extends EsDAO implements IEventQueryDAO {
    public ESEventQueryDAO(ElasticSearchClient client) {
        super(client);
    }

    @Override
    public Events queryEvents(final EventQueryCondition condition) throws Exception {
        final SearchSourceBuilder sourceBuilder = buildQuery(condition);
        return getEventsResultByCurrentBuilder(sourceBuilder);
    }

    @Override
    public Events queryEvents(List<EventQueryCondition> conditionList) throws Exception {
        final SearchSourceBuilder sourceBuilder = buildQuery(conditionList);
        return getEventsResultByCurrentBuilder(sourceBuilder);
    }

    private Events getEventsResultByCurrentBuilder(final SearchSourceBuilder sourceBuilder) throws IOException {
        final SearchResponse response = getClient()
                .search(IndexController.LogicIndicesRegister.getPhysicalTableName(Event.INDEX_NAME), sourceBuilder);
        final Events events = new Events();
        events.setTotal((int) response.getHits().totalHits);
        events.setEvents(Stream.of(response.getHits().getHits())
                .map(this::parseSearchHit)
                .collect(Collectors.toList()));
        return events;
    }

    private void buildMustQueryListByCondition(final EventQueryCondition condition, final List<QueryBuilder> mustQueryList) {
        if (!isNullOrEmpty(condition.getUuid())) {
            mustQueryList.add(QueryBuilders.termQuery(Event.UUID, condition.getUuid()));
        }

        final Source source = condition.getSource();
        if (source != null) {
            if (!isNullOrEmpty(source.getService())) {
                mustQueryList.add(QueryBuilders.termQuery(Event.SERVICE, source.getService()));
            }
            if (!isNullOrEmpty(source.getServiceInstance())) {
                mustQueryList.add(QueryBuilders.termQuery(Event.SERVICE_INSTANCE, source.getServiceInstance()));
            }
            if (!isNullOrEmpty(source.getEndpoint())) {
                mustQueryList.add(QueryBuilders.matchPhraseQuery(
                        MatchCNameBuilder.INSTANCE.build(Event.ENDPOINT),
                        source.getEndpoint()
                ));
            }
        }

        if (!isNullOrEmpty(condition.getName())) {
            mustQueryList.add(QueryBuilders.termQuery(Event.NAME, condition.getName()));
        }

        if (condition.getType() != null) {
            mustQueryList.add(QueryBuilders.termQuery(Event.TYPE, condition.getType().name()));
        }

        final Duration startTime = condition.getTime();
        if (startTime != null) {
            if (startTime.getStartTimestamp() > 0) {
                mustQueryList.add(QueryBuilders.rangeQuery(Event.START_TIME)
                        .gt(startTime.getStartTimestamp()));
            }
            if (startTime.getEndTimestamp() > 0) {
                mustQueryList.add(QueryBuilders.rangeQuery(Event.END_TIME)
                        .lt(startTime.getEndTimestamp()));
            }
        }
    }

    protected SearchSourceBuilder buildQuery(final List<EventQueryCondition> conditionList) {
        final SearchSourceBuilder sourceBuilder = SearchSourceBuilder.searchSource();
        BoolQueryBuilder linkShouldBuilder = QueryBuilders.boolQuery();
        sourceBuilder.query(linkShouldBuilder);
        conditionList.forEach(condition -> {
            final BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
            final List<QueryBuilder> mustQueryList = boolQueryBuilder.must();
            linkShouldBuilder.should(boolQueryBuilder);
            buildMustQueryListByCondition(condition, mustQueryList);
        });
        EventQueryCondition condition = conditionList.get(0);
        final Order queryOrder = isNull(condition.getOrder()) ? Order.DES : condition.getOrder();
        sourceBuilder.sort(Event.START_TIME, Order.DES.equals(queryOrder) ? SortOrder.DESC : SortOrder.ASC);

        final PaginationUtils.Page page = PaginationUtils.INSTANCE.exchange(condition.getPaging());
        sourceBuilder.from(page.getFrom());
        sourceBuilder.size(page.getLimit());
        return sourceBuilder;
    }

    protected SearchSourceBuilder buildQuery(final EventQueryCondition condition) {
        final SearchSourceBuilder sourceBuilder = SearchSourceBuilder.searchSource();
        final BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        sourceBuilder.query(boolQueryBuilder);

        final List<QueryBuilder> mustQueryList = boolQueryBuilder.must();

        buildMustQueryListByCondition(condition, mustQueryList);

        final Order queryOrder = isNull(condition.getOrder()) ? Order.DES : condition.getOrder();
        sourceBuilder.sort(Event.START_TIME, Order.DES.equals(queryOrder) ? SortOrder.DESC : SortOrder.ASC);

        final PaginationUtils.Page page = PaginationUtils.INSTANCE.exchange(condition.getPaging());
        sourceBuilder.from(page.getFrom());
        sourceBuilder.size(page.getLimit());

        return sourceBuilder;
    }

    protected org.apache.skywalking.oap.server.core.query.type.event.Event parseSearchHit(final SearchHit searchHit) {
        final org.apache.skywalking.oap.server.core.query.type.event.Event event = new org.apache.skywalking.oap.server.core.query.type.event.Event();

        event.setUuid((String) searchHit.getSourceAsMap().get(Event.UUID));

        String service = searchHit.getSourceAsMap().getOrDefault(Event.SERVICE, "").toString();
        String serviceInstance = searchHit.getSourceAsMap().getOrDefault(Event.SERVICE_INSTANCE, "").toString();
        String endpoint = searchHit.getSourceAsMap().getOrDefault(Event.ENDPOINT, "").toString();
        event.setSource(new Source(service, serviceInstance, endpoint));

        event.setName((String) searchHit.getSourceAsMap().get(Event.NAME));
        event.setType(EventType.parse(searchHit.getSourceAsMap().get(Event.TYPE).toString()));
        event.setMessage((String) searchHit.getSourceAsMap().get(Event.MESSAGE));
        event.setParameters((String) searchHit.getSourceAsMap().get(Event.PARAMETERS));
        event.setStartTime(Long.parseLong(searchHit.getSourceAsMap().get(Event.START_TIME).toString()));
        String endTimeStr = searchHit.getSourceAsMap().getOrDefault(Event.END_TIME, "0").toString();
        if (!endTimeStr.isEmpty() && !Objects.equals(endTimeStr, "0")) {
            event.setEndTime(Long.parseLong(endTimeStr));
        }

        return event;
    }
}
