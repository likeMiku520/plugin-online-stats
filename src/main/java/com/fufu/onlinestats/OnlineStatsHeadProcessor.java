package com.fufu.onlinestats;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * Injects the tracker script into the &lt;head&gt; of every public theme page and, on
 * article pages, the post name as a meta tag. The tracker itself is generic, so themes
 * that never expose the post name in the DOM still get their views attributed.
 */
@Component
public class OnlineStatsHeadProcessor implements TemplateHeadProcessor {

    private static final String META_POST_NAME = "halo-post-name";

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        IModelFactory modelFactory = context.getModelFactory();
        model.add(modelFactory.createText(
            "<script src=\"/apis/online-stats.halo.run/v1alpha1/online-stats\" defer></script>"));
        String postName = resolvePostName(context);
        if (postName != null && !postName.isBlank()) {
            model.add(modelFactory.createText("<meta name=\"" + META_POST_NAME
                + "\" content=\"" + escape(postName) + "\">"));
        }
        return Mono.empty();
    }

    /**
     * Halo exposes the current post under different context variables depending on the
     * page type and version, so probe the known ones.
     */
    private String resolvePostName(ITemplateContext context) {
        for (String variable : new String[] {"post", "singlePage"}) {
            String name = nameOf(context.getVariable(variable));
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private String nameOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            Object metadata = map.get("metadata");
            if (metadata instanceof java.util.Map<?, ?> meta) {
                Object name = meta.get("name");
                return name == null ? null : String.valueOf(name);
            }
            Object name = map.get("name");
            return name == null ? null : String.valueOf(name);
        }
        try {
            Object metadata = value.getClass().getMethod("getMetadata").invoke(value);
            if (metadata == null) {
                return null;
            }
            Object name = metadata.getClass().getMethod("getName").invoke(metadata);
            return name == null ? null : String.valueOf(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }
}