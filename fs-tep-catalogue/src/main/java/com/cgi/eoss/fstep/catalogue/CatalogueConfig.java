package com.cgi.eoss.fstep.catalogue;

import com.cgi.eoss.fstep.persistence.PersistenceConfig;
import com.cgi.eoss.fstep.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@Import({
        PropertyPlaceholderAutoConfiguration.class,

        PersistenceConfig.class,
        SecurityConfig.class
})
@ComponentScan(basePackageClasses = CatalogueConfig.class)
public class CatalogueConfig {

    @Bean
    public Path outputProductBasedir(@Value("${fstep.catalogue.outputProducts.baseDir:/data/outputProducts}") String baseDir) {
        return Paths.get(baseDir);
    }

    @Bean
    public Path referenceDataBasedir(@Value("${fstep.catalogue.refData.baseDir:/data/refData}") String baseDir) {
        return Paths.get(baseDir);
    }

    @Bean
    public ObjectMapper jsonMapper() {
        return new ObjectMapper();
    }

}
