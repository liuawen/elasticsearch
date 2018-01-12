/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ccr.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ccr.action.ShardFollowTasksExecutor.ChunkProcessor;
import org.elasticsearch.xpack.ccr.action.ShardFollowTasksExecutor.ChunksCoordinator;
import org.elasticsearch.xpack.ccr.action.bulk.BulkShardOperationsAction;
import org.elasticsearch.xpack.ccr.action.bulk.BulkShardOperationsRequest;
import org.elasticsearch.xpack.ccr.action.bulk.BulkShardOperationsResponse;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ChunksCoordinatorTests extends ESTestCase {

    public void testCreateChunks() {
        Client client = mock(Client.class);
        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        ChunksCoordinator coordinator = new ChunksCoordinator(client, ccrExecutor, 1024, 1, leaderShardId, followShardId, e -> {});
        coordinator.createChucks(0, 1024);
        List<long[]> result = new ArrayList<>(coordinator.getChunks());
        assertThat(result.size(), equalTo(1));
        assertThat(result.get(0)[0], equalTo(0L));
        assertThat(result.get(0)[1], equalTo(1024L));

        coordinator.getChunks().clear();
        coordinator.createChucks(0, 2048);
        result = new ArrayList<>(coordinator.getChunks());
        assertThat(result.size(), equalTo(2));
        assertThat(result.get(0)[0], equalTo(0L));
        assertThat(result.get(0)[1], equalTo(1024L));
        assertThat(result.get(1)[0], equalTo(1025L));
        assertThat(result.get(1)[1], equalTo(2048L));

        coordinator.getChunks().clear();
        coordinator.createChucks(0, 4096);
        result = new ArrayList<>(coordinator.getChunks());
        assertThat(result.size(), equalTo(4));
        assertThat(result.get(0)[0], equalTo(0L));
        assertThat(result.get(0)[1], equalTo(1024L));
        assertThat(result.get(1)[0], equalTo(1025L));
        assertThat(result.get(1)[1], equalTo(2048L));
        assertThat(result.get(2)[0], equalTo(2049L));
        assertThat(result.get(2)[1], equalTo(3072L));
        assertThat(result.get(3)[0], equalTo(3073L));
        assertThat(result.get(3)[1], equalTo(4096L));

        coordinator.getChunks().clear();
        coordinator.createChucks(4096, 8196);
        result = new ArrayList<>(coordinator.getChunks());
        assertThat(result.size(), equalTo(5));
        assertThat(result.get(0)[0], equalTo(4096L));
        assertThat(result.get(0)[1], equalTo(5120L));
        assertThat(result.get(1)[0], equalTo(5121L));
        assertThat(result.get(1)[1], equalTo(6144L));
        assertThat(result.get(2)[0], equalTo(6145L));
        assertThat(result.get(2)[1], equalTo(7168L));
        assertThat(result.get(3)[0], equalTo(7169L));
        assertThat(result.get(3)[1], equalTo(8192L));
        assertThat(result.get(4)[0], equalTo(8193L));
        assertThat(result.get(4)[1], equalTo(8196L));
    }

    public void testCoordinator() throws Exception {
        Client client = mock(Client.class);
        mockShardChangesApiCall(client);
        mockBulkShardOperationsApiCall(client);
        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        Consumer<Exception> handler = e -> assertThat(e, nullValue());
        int concurrentProcessors = randomIntBetween(1, 4);
        int batchSize = randomIntBetween(1, 1000);
        ChunksCoordinator coordinator = new ChunksCoordinator(client, ccrExecutor, batchSize, concurrentProcessors,
                leaderShardId, followShardId, handler);

        int numberOfOps = randomIntBetween(batchSize, batchSize * 20);
        long from = randomInt(1000);
        long to = from + numberOfOps;
        coordinator.createChucks(from, to);
        int expectedNumberOfChunks = numberOfOps / batchSize;
        if (numberOfOps % batchSize > 0) {
            expectedNumberOfChunks++;
        }
        assertThat(coordinator.getChunks().size(), equalTo(expectedNumberOfChunks));

        coordinator.start();
        assertThat(coordinator.getChunks().size(), equalTo(0));
        verify(client, times(expectedNumberOfChunks)).execute(same(ShardChangesAction.INSTANCE),
                any(ShardChangesAction.Request.class), any(ActionListener.class));
        verify(client, times(expectedNumberOfChunks)).execute(same(BulkShardOperationsAction.INSTANCE),
                any(BulkShardOperationsRequest.class), any(ActionListener.class));
    }

    public void testCoordinator_failure() throws Exception {
        Exception expectedException = new RuntimeException("throw me");
        Client client = mock(Client.class);
        boolean shardChangesActionApiCallFailed;
        if (randomBoolean()) {
            shardChangesActionApiCallFailed = true;
            doThrow(expectedException).when(client).execute(same(ShardChangesAction.INSTANCE),
                    any(ShardChangesAction.Request.class), any(ActionListener.class));
        } else {
            shardChangesActionApiCallFailed = false;
            mockShardChangesApiCall(client);
            doThrow(expectedException).when(client).execute(same(BulkShardOperationsAction.INSTANCE),
                    any(BulkShardOperationsRequest.class), any(ActionListener.class));
        }
        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        Consumer<Exception> handler = e -> {
            assertThat(e, notNullValue());
            assertThat(e, sameInstance(expectedException));
        };
        ChunksCoordinator coordinator = new ChunksCoordinator(client, ccrExecutor, 10, 1, leaderShardId, followShardId, handler);
        coordinator.createChucks(0, 20);
        assertThat(coordinator.getChunks().size(), equalTo(2));

        coordinator.start();
        assertThat(coordinator.getChunks().size(), equalTo(1));
        verify(client, times(1)).execute(same(ShardChangesAction.INSTANCE), any(ShardChangesAction.Request.class),
                any(ActionListener.class));
        verify(client, times(shardChangesActionApiCallFailed ? 0 : 1)).execute(same(BulkShardOperationsAction.INSTANCE),
                any(BulkShardOperationsRequest.class), any(ActionListener.class));
    }

    public void testCoordinator_concurrent() throws Exception {
        Client client = mock(Client.class);
        mockShardChangesApiCall(client);
        mockBulkShardOperationsApiCall(client);
        Executor ccrExecutor = command -> new Thread(command).start();
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        AtomicBoolean calledOnceChecker = new AtomicBoolean(false);
        AtomicReference<Exception> failureHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Consumer<Exception> handler = e -> {
            if (failureHolder.compareAndSet(null, e) == false) {
                // This handler should only be called once, irregardless of the number of concurrent processors
                calledOnceChecker.set(true);
            }
            latch.countDown();
        };
        ChunksCoordinator coordinator = new ChunksCoordinator(client, ccrExecutor, 1000, 4, leaderShardId, followShardId, handler);
        coordinator.createChucks(0, 1000000);
        assertThat(coordinator.getChunks().size(), equalTo(1000));

        coordinator.start();
        latch.await();
        assertThat(coordinator.getChunks().size(), equalTo(0));
        verify(client, times(1000)).execute(same(ShardChangesAction.INSTANCE), any(ShardChangesAction.Request.class),
                any(ActionListener.class));
        verify(client, times(1000)).execute(same(BulkShardOperationsAction.INSTANCE), any(BulkShardOperationsRequest.class),
                any(ActionListener.class));
        assertThat(calledOnceChecker.get(), is(false));
    }

    public void testChunkProcessor() {
        Client client = mock(Client.class);
        mockShardChangesApiCall(client);
        mockBulkShardOperationsApiCall(client);
        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        boolean[] invoked = new boolean[1];
        Exception[] exception = new Exception[1];
        Consumer<Exception> handler = e -> {invoked[0] = true;exception[0] = e;};
        ChunkProcessor chunkProcessor = new ChunkProcessor(client, ccrExecutor, leaderShardId, followShardId, handler);
        chunkProcessor.start(0, 10);
        assertThat(invoked[0], is(true));
        assertThat(exception[0], nullValue());
    }

    public void testChunkProcessorRetry() {
        Client client = mock(Client.class);
        mockBulkShardOperationsApiCall(client);
        int testRetryLimit = randomIntBetween(1, ShardFollowTasksExecutor.PROCESSOR_RETRY_LIMIT - 1);
        mockShardCangesApiCallWithRetry(client, testRetryLimit, new ConnectException("connection exception"));

        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        boolean[] invoked = new boolean[1];
        Exception[] exception = new Exception[1];
        Consumer<Exception> handler = e -> {invoked[0] = true;exception[0] = e;};
        ChunkProcessor chunkProcessor = new ChunkProcessor(client, ccrExecutor, leaderShardId, followShardId, handler);
        chunkProcessor.start(0, 10);
        assertThat(invoked[0], is(true));
        assertThat(exception[0], nullValue());
        assertThat(chunkProcessor.retryCounter.get(), equalTo(testRetryLimit + 1));
    }

    public void testChunkProcessorRetryTooManyTimes() {
        Client client = mock(Client.class);
        mockBulkShardOperationsApiCall(client);
        int testRetryLimit = ShardFollowTasksExecutor.PROCESSOR_RETRY_LIMIT + 1;
        mockShardCangesApiCallWithRetry(client, testRetryLimit, new ConnectException("connection exception"));

        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        boolean[] invoked = new boolean[1];
        Exception[] exception = new Exception[1];
        Consumer<Exception> handler = e -> {invoked[0] = true;exception[0] = e;};
        ChunkProcessor chunkProcessor = new ChunkProcessor(client, ccrExecutor, leaderShardId, followShardId, handler);
        chunkProcessor.start(0, 10);
        assertThat(invoked[0], is(true));
        assertThat(exception[0], notNullValue());
        assertThat(exception[0].getMessage(), equalTo("retrying failed [17] times, aborting..."));
        assertThat(exception[0].getCause().getMessage(), equalTo("connection exception"));
        assertThat(chunkProcessor.retryCounter.get(), equalTo(testRetryLimit));
    }

    public void testChunkProcessorNoneRetryableError() {
        Client client = mock(Client.class);
        mockBulkShardOperationsApiCall(client);
        mockShardCangesApiCallWithRetry(client, 3, new RuntimeException("unexpected"));

        Executor ccrExecutor = Runnable::run;
        ShardId leaderShardId = new ShardId("index1", "index1", 0);
        ShardId followShardId = new ShardId("index2", "index1", 0);

        boolean[] invoked = new boolean[1];
        Exception[] exception = new Exception[1];
        Consumer<Exception> handler = e -> {invoked[0] = true;exception[0] = e;};
        ChunkProcessor chunkProcessor = new ChunkProcessor(client, ccrExecutor, leaderShardId, followShardId, handler);
        chunkProcessor.start(0, 10);
        assertThat(invoked[0], is(true));
        assertThat(exception[0], notNullValue());
        assertThat(exception[0].getMessage(), equalTo("unexpected"));
        assertThat(chunkProcessor.retryCounter.get(), equalTo(0));
    }

    private void mockShardCangesApiCallWithRetry(Client client, int testRetryLimit, Exception e) {
        int[] retryCounter = new int[1];
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            assert args.length == 3;
            ShardChangesAction.Request request = (ShardChangesAction.Request) args[1];
            @SuppressWarnings("unchecked")
            ActionListener<ShardChangesAction.Response> listener = (ActionListener) args[2];
            if (retryCounter[0]++ <= testRetryLimit) {
                listener.onFailure(e);
            } else {
                long delta = request.getMaxSeqNo() - request.getMinSeqNo();
                Translog.Operation[] operations = new Translog.Operation[(int) delta];
                for (int i = 0; i < operations.length; i++) {
                    operations[i] = new Translog.NoOp(request.getMinSeqNo() + i, 1, "test");
                }
                ShardChangesAction.Response response = new ShardChangesAction.Response(operations);
                listener.onResponse(response);
            }
            return null;
        }).when(client).execute(same(ShardChangesAction.INSTANCE), any(ShardChangesAction.Request.class), any(ActionListener.class));
    }

    private void mockShardChangesApiCall(Client client) {
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            assert args.length == 3;
            ShardChangesAction.Request request = (ShardChangesAction.Request) args[1];
            @SuppressWarnings("unchecked")
            ActionListener<ShardChangesAction.Response> listener = (ActionListener) args[2];

            long delta = request.getMaxSeqNo() - request.getMinSeqNo();
            Translog.Operation[] operations = new Translog.Operation[(int) delta];
            for (int i = 0; i < operations.length; i++) {
                operations[i] = new Translog.NoOp(request.getMinSeqNo() + i, 1, "test");
            }
            ShardChangesAction.Response response = new ShardChangesAction.Response(operations);
            listener.onResponse(response);
            return null;
        }).when(client).execute(same(ShardChangesAction.INSTANCE), any(ShardChangesAction.Request.class), any(ActionListener.class));
    }

    private void mockBulkShardOperationsApiCall(Client client) {
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            assert args.length == 3;
            @SuppressWarnings("unchecked")
            ActionListener<BulkShardOperationsResponse> listener = (ActionListener) args[2];
            listener.onResponse(new BulkShardOperationsResponse());
            return null;
        }).when(client).execute(same(BulkShardOperationsAction.INSTANCE), any(BulkShardOperationsRequest.class),
                any(ActionListener.class));
    }

}
