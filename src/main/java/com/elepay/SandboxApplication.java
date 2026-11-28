package com.elepay;

import com.elepay.merchanttools.MerchantToolsModuleConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The single ELEPAY application. The legacy class name is retained for build and test compatibility. */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.elepay",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.elepay\\.merchanttools\\..*"
        )
)
@Import(MerchantToolsModuleConfiguration.class)
@EnableScheduling
public class SandboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
    }
}
