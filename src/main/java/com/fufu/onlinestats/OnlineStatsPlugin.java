package com.fufu.onlinestats;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Plugin main class. Registers the custom model scheme, starts the history
 * persistence worker and releases the live registry when the plugin stops.
 */
@Component
public class OnlineStatsPlugin extends BasePlugin {

    private final OnlineVisitorRegistry registry;
    private final VisitorRecordService recordService;
    private final SchemeManager schemeManager;

    public OnlineStatsPlugin(PluginContext pluginContext, OnlineVisitorRegistry registry,
                             VisitorRecordService recordService, SchemeManager schemeManager) {
        super(pluginContext);
        this.registry = registry;
        this.recordService = recordService;
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(VisitorRecord.class);
        recordService.start();
    }

    @Override
    public void stop() {
        recordService.stop();
        registry.clear();
        schemeManager.unregister(Scheme.buildFromType(VisitorRecord.class));
    }
}
