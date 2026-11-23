package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.json.JsonMapper;

class JsonParsingLimitsTest {

    @Test
    void deeplyNestedAndGiantNumberPayloadsAreRejected() {
        JsonParsingLimits.apply();
        var mapper = JsonMapper.builder().build();
        String deep = "[".repeat(65) + "]".repeat(65);
        assertThatThrownBy(() -> mapper.readTree(deep)).isInstanceOf(StreamConstraintsException.class);
        String bigNumber = "{\"amount\":" + "9".repeat(101) + "}";
        assertThatThrownBy(() -> mapper.readTree(bigNumber)).isInstanceOf(StreamConstraintsException.class);
        assertThat(mapper.readTree("[".repeat(60) + "]".repeat(60))).isNotNull();
    }
}
