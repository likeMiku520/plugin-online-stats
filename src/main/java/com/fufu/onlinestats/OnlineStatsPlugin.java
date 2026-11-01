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
    private final ChatMessageService chatService;
    private final SchemeManager schemeManager;
    private final run.halo.app.plugin.ReactiveSettingFetcher settingFetcher;
    private ScheduledExecutorService scheduler;

    public OnlineStatsPlugin(PluginContext pluginContext, OnlineVisitorRegistry registry,
                             VisitorRecordService recordService,
                             StatsAggregationService statsService,
                             ChatMessageService chatService,
                             SchemeManager schemeManager,
                             run.halo.app.plugin.ReactiveSettingFetcher settingFetcher) {
        super(pluginContext);
        this.registry = registry;
        this.recordService = recordService;
        this.statsService = statsService;
        this.chatService = chatService;
        this.schemeManager = schemeManager;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public void start() {
        schemeManager.register(VisitorRecord.class);
        schemeManager.register(ArticleStat.class);
        schemeManager.register(DailyStat.class);
        schemeManager.register(ChatMessage.class);
        statsService.loadAll();
        recordService.start();
        chatService.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "online-stats-official");
            thread.setDaemon(true);
            return thread;
        });
        // The first read can happen before the resource type is ready; retry early so the
        // page does not sit on an empty index until the slow loop comes round.
        scheduler.schedule(() -> {
            try {
                statsService.refreshIndexes();
            } catch (Exception ignored) {
                // the scheduled refresh will try again
            }
        }, 5, TimeUnit.SECONDS);
        scheduler.schedule(() -> {
            try {
                statsService.refreshIndexes();
            } catch (Exception ignored) {
                // the scheduled refresh will try again
            }
        }, 30, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                statsService.refreshOfficialCounts();
                // Re-reading the statistics also repairs a load that failed once at
                // start-up, which otherwise left the page showing empty rows for data
                // that was still in the store.
                statsService.refreshIndexes();
                settingFetcher.fetch(OnlineStatsSettings.GROUP, OnlineStatsSettings.class)
                    .defaultIfEmpty(OnlineStatsSettings.empty())
                    .subscribe(settings -> {
                        statsService
                            .ensureAdjustment(settings.officialViewsAdjustmentOrDefault());
                        // Conversations follow the visitor retention window.
                        chatService.prune(settings.historyRetentionDaysOrDefault());
                    });
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
        chatService.stop();
        registry.clear();
        statsService.shutdown();
        schemeManager.unregister(Scheme.buildFromType(VisitorRecord.class));
        schemeManager.unregister(Scheme.buildFromType(ArticleStat.class));
        schemeManager.unregister(Scheme.buildFromType(DailyStat.class));
        schemeManager.unregister(Scheme.buildFromType(ChatMessage.class));
    }
}