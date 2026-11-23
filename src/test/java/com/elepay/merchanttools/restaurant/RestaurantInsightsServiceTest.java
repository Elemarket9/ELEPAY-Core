package com.elepay.merchanttools.restaurant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RestaurantInsightsServiceTest {
    @Test void spreadsheetFormulaPayloadsAreQuotedAsText() {
        assertThat(RestaurantInsightsService.csvCell("=2+3")).isEqualTo("\"'=2+3\"");
        assertThat(RestaurantInsightsService.csvCell("Rice,\"special\""))
                .isEqualTo("\"Rice,\"\"special\"\"\"");
        assertThat(RestaurantInsightsService.csvCell("@SUM")).startsWith("\"'");
    }
}
