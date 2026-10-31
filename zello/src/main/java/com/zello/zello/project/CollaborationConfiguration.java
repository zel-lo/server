package com.zello.zello.project;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CollaborationConfiguration {
    @Bean
    Clock collaborationClock() { return Clock.systemUTC(); }
}
