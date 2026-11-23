package com.elepay.emtech;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "elepay.emtech")
public class EmtechSettings {
    private boolean enabled = false;
    private String baseUrl = "";
    private String clientId = "";
    private String clientSecret = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(15);
    private int maxRetries = 3;
    private long retryBaseMs = 500;
    private int maxAttempts = 8;
    private long pollMs = 5000;
    private String emoneyAccountType = "";
    private String platformCity = "Accra";
    private String platformRegion = "GH-AA";
    private String platformCountry = "GH";
    private String platformLatitude = "5.603717";
    private String platformLongitude = "-0.186964";
    private String coarseIpCities = "";

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String baseUrl() { return baseUrl == null ? "" : baseUrl.trim(); }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String clientId() { return clientId == null ? "" : clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String clientSecret() { return clientSecret == null ? "" : clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public Duration connectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int maxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public long retryBaseMs() { return retryBaseMs; }
    public void setRetryBaseMs(long retryBaseMs) { this.retryBaseMs = retryBaseMs; }
    public int maxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long pollMs() { return pollMs; }
    public void setPollMs(long pollMs) { this.pollMs = pollMs; }
    public String emoneyAccountType() { return emoneyAccountType == null ? "" : emoneyAccountType.trim(); }
    public void setEmoneyAccountType(String emoneyAccountType) { this.emoneyAccountType = emoneyAccountType; }
    public String platformCity() { return platformCity; }
    public void setPlatformCity(String platformCity) { this.platformCity = platformCity; }
    public String platformRegion() { return platformRegion; }
    public void setPlatformRegion(String platformRegion) { this.platformRegion = platformRegion; }
    public String platformCountry() { return platformCountry; }
    public void setPlatformCountry(String platformCountry) { this.platformCountry = platformCountry; }
    public BigDecimal platformLatitude() { return new BigDecimal(platformLatitude); }
    public void setPlatformLatitude(String platformLatitude) { this.platformLatitude = platformLatitude; }
    public BigDecimal platformLongitude() { return new BigDecimal(platformLongitude); }
    public void setPlatformLongitude(String platformLongitude) { this.platformLongitude = platformLongitude; }
    public String coarseIpCities() { return coarseIpCities == null ? "" : coarseIpCities; }
    public void setCoarseIpCities(String coarseIpCities) { this.coarseIpCities = coarseIpCities; }

    public boolean credentialsPresent() {
        return !clientId().isBlank() && !clientSecret().isBlank() && !baseUrl().isBlank();
    }
}
