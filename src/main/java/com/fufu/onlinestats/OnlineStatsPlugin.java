package com.fufu.onlinestats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Plugin main class. Registers the custom models, starts the history persistence worker
 * and keeps the official view counts in sync while the plugin is running.
 */
@Component
public class OnlineStatsPlugin extends BasePlugin {

    private final OnlineVisitorRegistry registry;
    private final VisitorRecordService recordService;
    private final StatsAggregationService statsService;
    private final SchemeManager schemeManager;
    private ScheduledExecutorService scheduler;

    public OnlineStatsPlugin(PluginContext pluginContext, OnlineVisitorRegistry registry,
                             VisitorRecordService recordService,
                             StatsAggregationService statsService,
                             SchemeManager schemeManager) {
        super(pluginContext);
        this.registry = registry;
        this.recordService = recordService;
        this.statsService = statsService;
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(VisitorRecord.class);
        schemeManager.register(ArticleStat.class);
        schemeManager.register(DailyStat.class);
        statsService.loadAll();
        recordService.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "online-stats-official");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                statsService.refreshOfficialCounts();
            } catch (Exception ignored) {
                // a failed refresh must never kill the scheduler
            }
        }, 20, 300, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        recordService.stop();
        registry.clear();
        schemeManager.unregister(Scheme.buildFromType(VisitorRecord.class));
        schemeManager.unregister(Scheme.buildFromType(ArticleStat.class));
        schemeManager.unregister(Scheme.buildFromType(DailyStat.class));
    }
}