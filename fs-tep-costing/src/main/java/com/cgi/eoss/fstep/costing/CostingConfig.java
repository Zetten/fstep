package com.cgi.eoss.fstep.costing;

import com.cgi.eoss.fstep.persistence.PersistenceConfig;
import com.cgi.eoss.fstep.persistence.service.CostingExpressionDataService;
import com.cgi.eoss.fstep.persistence.service.WalletDataService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

@Configuration
@Import({
        PropertyPlaceholderAutoConfiguration.class,

        PersistenceConfig.class
})
public class CostingConfig {

    @Bean
    public ExpressionParser costingExpressionParser() {
        return new SpelExpressionParser();
    }

    @Bean
    public CostingService costingService(ExpressionParser costingExpressionParser,
                                         CostingExpressionDataService costingDataService,
                                         WalletDataService walletDataService,
                                         @Value("${fstep.costing.defaultJobCostExpression:1}") String defaultJobCostExpression,
                                         @Value("${fstep.costing.defaultDownloadCostExpression:1}") String defaultDownloadCostExpression) {
        return new CostingServiceImpl(costingExpressionParser, costingDataService, walletDataService, defaultJobCostExpression, defaultDownloadCostExpression);
    }

}
