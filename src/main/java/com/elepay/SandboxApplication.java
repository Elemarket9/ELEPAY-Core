package com.elepay;

import com.elepay.agents.AgentsModuleConfiguration;
import com.elepay.bills.BillsModuleConfiguration;
import com.elepay.credit.CreditModuleConfiguration;
import com.elepay.growth.GrowthModuleConfiguration;
import com.elepay.lending.LendingModuleConfiguration;
import com.elepay.merchanttools.MerchantToolsModuleConfiguration;
import com.elepay.ussd.UssdModuleConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The single ELEPAY application. The legacy class name is retained for build and test compatibility. */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.elepay",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.elepay\\.(merchanttools|lending|credit|ussd|growth|bills|agents)\\..*"
                )
        }
)
@Import({
        MerchantToolsModuleConfiguration.class,
        LendingModuleConfiguration.class,
        CreditModuleConfiguration.class,
        UssdModuleConfiguration.class,
        GrowthModuleConfiguration.class,
        BillsModuleConfiguration.class,
        AgentsModuleConfiguration.class
})
@EnableScheduling
public class SandboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
    }
}
