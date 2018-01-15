/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.test.bench;

import org.bouncycastle.operator.OperatorCreationException;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.ServerLoggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.node.MockNode;
import org.elasticsearch.node.Node;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.watcher.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.watcher.Watcher;
import org.elasticsearch.xpack.watcher.client.WatchSourceBuilder;
import org.elasticsearch.xpack.watcher.client.WatcherClient;
import org.elasticsearch.xpack.watcher.condition.ScriptCondition;
import org.elasticsearch.xpack.watcher.transport.actions.put.PutWatchRequest;
import org.elasticsearch.xpack.watcher.trigger.ScheduleTriggerEngineMock;
import org.elasticsearch.xpack.watcher.trigger.TriggerEngine;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;

import javax.security.auth.DestroyFailedException;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.indexAction;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.httpInput;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.searchInput;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.templateRequest;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.interval;

/**
 * Starts a master only node with watcher and benchmarks the executor service side, so no scheduling. The benchmark
 * uses the mock scheduler to trigger watches.
 *
 * A date node needs to be started outside this benchmark. This the removes non watcher noise like indexing.
 */
public class WatcherExecutorServiceBenchmark {

    private static final Settings SETTINGS = Settings.builder()
            .put("xpack.security.enabled", false)
            .put("cluster.name", "bench")
            .put("network.host", "localhost")
            .put("script.disable_dynamic", false)
            .put("discovery.zen.ping.unicast.hosts", "localhost")
            .put("http.cors.enabled", true)
            .put("cluster.routing.allocation.disk.threshold_enabled", false)
//                .put("recycler.page.limit.heap", "60%")
            .build();

    private static Client client;
    private static WatcherClient watcherClient;
    private static ScheduleTriggerEngineMock scheduler;

    protected static void start() throws Exception {
        Node node = new MockNode(Settings.builder().put(SETTINGS).put("node.data", false).build(),
                Arrays.asList(XPackBenchmarkPlugin.class));
        client = node.client();
        client.admin().cluster().prepareHealth("*").setWaitForGreenStatus().get();
        Thread.sleep(5000);
        watcherClient = node.injector().getInstance(WatcherClient.class);
        scheduler = node.injector().getInstance(ScheduleTriggerEngineMock.class);
    }

    public static final class SmallSearchInput extends WatcherExecutorServiceBenchmark {

        public static void main(String[] args) throws Exception {
            start();
            client.admin().indices().prepareCreate("test").get();
            client.prepareIndex("test", "test", "1").setSource("{}", XContentType.JSON).get();

            int numAlerts = 1000;
            for (int i = 0; i < numAlerts; i++) {
                final String name = "_name" + i;
                PutWatchRequest putAlertRequest = new PutWatchRequest(name, new WatchSourceBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(searchInput(templateRequest(new SearchSourceBuilder(), "test")))
                        .condition(new ScriptCondition(new Script(
                                ScriptType.INLINE,
                                Script.DEFAULT_SCRIPT_LANG,
                                "ctx.payload.hits.total > 0",
                                emptyMap()))));
                putAlertRequest.setId(name);
                watcherClient.putWatch(putAlertRequest).actionGet();
            }

            int numThreads = 50;
            int watchersPerThread = numAlerts / numThreads;
            Thread[] threads = new Thread[numThreads];
            for (int i = 0; i < numThreads; i++) {
                final int begin = i * watchersPerThread;
                final int end = (i + 1) * watchersPerThread;
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            for (int j = begin; j < end; j++) {
                                scheduler.trigger("_name" + j);
                            }
                        }
                    }
                };
                threads[i] = new Thread(r);
                threads[i].start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
        }

    }

    public static final class BigSearchInput extends WatcherExecutorServiceBenchmark {

        public static void main(String[] args) throws Exception {
            start();
            int numAlerts = 1000;
            for (int i = 0; i < numAlerts; i++) {
                final String name = "_name" + i;
                PutWatchRequest putAlertRequest = new PutWatchRequest(name, new WatchSourceBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(searchInput(templateRequest(new SearchSourceBuilder(), "test"))
                                .extractKeys("hits.total"))
                        .condition(new ScriptCondition(new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, "1 == 1", emptyMap())))
                        .addAction("_id", indexAction("index", "type")));
                putAlertRequest.setId(name);
                watcherClient.putWatch(putAlertRequest).actionGet();
            }

            int numThreads = 50;
            int watchersPerThread = numAlerts / numThreads;
            Thread[] threads = new Thread[numThreads];
            for (int i = 0; i < numThreads; i++) {
                final int begin = i * watchersPerThread;
                final int end = (i + 1) * watchersPerThread;
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            for (int j = begin; j < end; j++) {
                                scheduler.trigger("_name" + j);
                            }
                        }
                    }
                };
                threads[i] = new Thread(r);
                threads[i].start();
            }


            for (Thread thread : threads) {
                thread.join();
            }
        }

    }

    public static final class HttpInput extends WatcherExecutorServiceBenchmark {

        public static void main(String[] args) throws Exception {
            start();
            int numAlerts = 1000;
            for (int i = 0; i < numAlerts; i++) {
                final String name = "_name" + i;
                PutWatchRequest putAlertRequest = new PutWatchRequest(name, new WatchSourceBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(httpInput(HttpRequestTemplate.builder("localhost", 9200)))
                        .condition(new ScriptCondition(new Script(
                                ScriptType.INLINE,
                                Script.DEFAULT_SCRIPT_LANG,
                                "ctx.payload.tagline == \"You Know, for Search\"",
                                emptyMap()))));
                putAlertRequest.setId(name);
                watcherClient.putWatch(putAlertRequest).actionGet();
            }

            int numThreads = 50;
            int watchersPerThread = numAlerts / numThreads;
            Thread[] threads = new Thread[numThreads];
            for (int i = 0; i < numThreads; i++) {
                final int begin = i * watchersPerThread;
                final int end = (i + 1) * watchersPerThread;
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            for (int j = begin; j < end; j++) {
                                scheduler.trigger("_name" + j);
                            }
                        }
                    }
                };
                threads[i] = new Thread(r);
                threads[i].start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
        }

    }

    public static final class XPackBenchmarkPlugin extends XPackPlugin {

        public XPackBenchmarkPlugin(
                Settings settings,
                Path configPath) throws IOException, DestroyFailedException, OperatorCreationException, GeneralSecurityException {
            super(settings, configPath);
            watcher = new BenchmarkWatcher(settings);
        }

        public static class BenchmarkWatcher extends Watcher {

            public BenchmarkWatcher(Settings settings) {
                super(settings);
                ServerLoggers.getLogger(XPackBenchmarkPlugin.class, settings).info("using watcher benchmark plugin");
            }

            @Override
            protected TriggerEngine getTriggerEngine(Clock clock, ScheduleRegistry scheduleRegistry) {
                return new ScheduleTriggerEngineMock(settings, scheduleRegistry, clock);
            }
        }
    }

}
