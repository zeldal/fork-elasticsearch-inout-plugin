package crate.elasticsearch.action.searchinto;

import static org.elasticsearch.common.collect.Lists.newArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BroadcastShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.TransportBroadcastOperationAction;
import org.elasticsearch.cache.recycler.CacheRecycler;
import org.elasticsearch.cache.recycler.PageCacheRecycler;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.ShardSearchRequest;
import org.elasticsearch.search.query.QueryPhaseExecutionException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import crate.elasticsearch.action.searchinto.parser.ISearchIntoParser;
import crate.elasticsearch.script.ScriptProvider;
import crate.elasticsearch.searchinto.Writer;
import crate.elasticsearch.searchinto.WriterResult;


/**
 *
 */
public abstract class AbstractTransportSearchIntoAction extends
        TransportBroadcastOperationAction<SearchIntoRequest,
                SearchIntoResponse, ShardSearchIntoRequest,
                ShardSearchIntoResponse> {

    private final IndicesService indicesService;

    private final ScriptService scriptService;
    
    private ScriptProvider scriptProvider;

    private final ISearchIntoParser parser;

    private final CacheRecycler cacheRecycler;
    private final PageCacheRecycler pageRecycler;

    private final Writer writer;

    @Inject
    public AbstractTransportSearchIntoAction(Settings settings,
            ThreadPool threadPool, ClusterService clusterService,
            TransportService transportService,
            CacheRecycler cacheRecycler, PageCacheRecycler pageRecycler,
            IndicesService indicesService, ScriptService scriptService,
            ScriptProvider scriptProvider,
            ISearchIntoParser parser, Writer writer) {
        super(settings, threadPool, clusterService, transportService);
        this.indicesService = indicesService;
        this.cacheRecycler = cacheRecycler;
        this.pageRecycler = pageRecycler;
        this.scriptService = scriptService;
        this.scriptProvider = scriptProvider;
        this.parser = parser;
        this.writer = writer;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SEARCH;
    }

    @Override
    protected SearchIntoRequest newRequest() {
        return new SearchIntoRequest();
    }

    @Override
    protected ShardSearchIntoRequest newShardRequest() {
        return new ShardSearchIntoRequest();
    }

    @Override
    protected ShardSearchIntoRequest newShardRequest(ShardRouting shard,
            SearchIntoRequest request) {
        String[] filteringAliases = clusterService.state().metaData()
                .filteringAliases(shard.index(), request.indices());
        return new ShardSearchIntoRequest(shard.index(), shard.id(),
                filteringAliases, request);
    }

    @Override
    protected ShardSearchIntoResponse newShardResponse() {
        return new ShardSearchIntoResponse();
    }

    @Override
    protected GroupShardsIterator shards(ClusterState clusterState,
            SearchIntoRequest request, String[] concreteIndices) {
        Map<String, Set<String>> routingMap = clusterState.metaData()
                .resolveSearchRouting(request.routing(), request.indices());
        return clusterService.operationRouting().searchShards(clusterState,
                request.indices(), concreteIndices, routingMap,
                request.preference());
    }

    @Override
    protected ClusterBlockException checkGlobalBlock(ClusterState state,
            SearchIntoRequest request) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.READ);
    }

    @Override
    protected ClusterBlockException checkRequestBlock(ClusterState state,
            SearchIntoRequest searchIntoRequest, String[] concreteIndices) {
        return state.blocks().indicesBlockedException(ClusterBlockLevel.READ,
                concreteIndices);
    }

    @Override
    protected SearchIntoResponse newResponse(SearchIntoRequest request,
            AtomicReferenceArray shardsResponses, ClusterState clusterState) {
        int successfulShards = 0;
        int failedShards = 0;
        List<ShardOperationFailedException> shardFailures = null;
        List<ShardSearchIntoResponse> responses = new
                ArrayList<ShardSearchIntoResponse>();
        for (int i = 0; i < shardsResponses.length(); i++) {
            Object shardResponse = shardsResponses.get(i);
            if (shardResponse == null) {
                failedShards++;
            } else if (shardResponse instanceof
                    BroadcastShardOperationFailedException) {
                failedShards++;
                if (shardFailures == null) {
                    shardFailures = newArrayList();
                }
                shardFailures.add(new DefaultShardOperationFailedException(
                        (BroadcastShardOperationFailedException)
                                shardResponse));
            } else {
                responses.add((ShardSearchIntoResponse) shardResponse);
                successfulShards++;
            }
        }
        return new SearchIntoResponse(responses, shardsResponses.length(),
                successfulShards, failedShards, shardFailures);
    }


    @Override
    protected ShardSearchIntoResponse shardOperation(ShardSearchIntoRequest
            request) throws ElasticsearchException {

        IndexService indexService = indicesService.indexServiceSafe(
                request.index());
        IndexShard indexShard = indexService.shardSafe(request.shardId());

        SearchShardTarget shardTarget = new SearchShardTarget(
                clusterService.localNode().id(), request.index(),
                request.shardId());
        SearchIntoContext context = new SearchIntoContext(0,
            new ShardSearchRequest().types(request.types()).filteringAliases(request.filteringAliases()),
            shardTarget, indexShard.acquireSearcher("inout-plugin"), indexService, indexShard, scriptService,
                cacheRecycler, pageRecycler
        );
        SearchIntoContext.setCurrent(context);
        try {
            BytesReference source = request.source();
            parser.parseSource(context, source);
            scriptProvider.prepareContextForScriptExecution(context, scriptService);
            
            context.preProcess();
            try {
                if (context.explain()) {
                    return new ShardSearchIntoResponse(
                            shardTarget.nodeIdText(), request.index(),
                            request.shardId());
                } else {
                    WriterResult res = writer.execute(context);
                    return new ShardSearchIntoResponse(
                            shardTarget.nodeIdText(), request.index(),
                            request.shardId(), res);
                }

            } catch (Exception e) {
                throw new QueryPhaseExecutionException(context,
                        "failed to execute inout", e);
            }
        } finally {
            // this will also release the index searcher
            context.release();
            SearchContext.removeCurrent();
        }
    }
}
