package com.svatsa.escrow.common;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link ActorArgumentResolver} so controllers can take an {@link Actor} parameter. */
@Configuration
public class ActorWebConfig implements WebMvcConfigurer {

    private final ActorArgumentResolver actorArgumentResolver;

    public ActorWebConfig(ActorArgumentResolver actorArgumentResolver) {
        this.actorArgumentResolver = actorArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actorArgumentResolver);
    }
}
