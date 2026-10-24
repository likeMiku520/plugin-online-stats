package com.fufu.onlinestats;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * Injects the online-stats tracker script into the &lt;head&gt; of every public
 * theme page, so the plugin works without any theme modification.
 */
@Component
public class OnlineStatsHeadProcessor implements TemplateHeadProcessor {

    private static final String TRACKER_SCRIPT =
        "<script src=\"/apis/online-stats.halo.run/v1alpha1/online-stats\" defer></script>";

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        IModelFactory modelFactory = context.getModelFactory();
        model.add(modelFactory.createText(TRACKER_SCRIPT));
        return Mono.empty();
    }
}
