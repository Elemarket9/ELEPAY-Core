package com.elepay.cell;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for cell-based sharding ({@code elepay.cells.*}).
 */
@Component
@ConfigurationProperties(prefix = "elepay.cells")
public class CellProperties {

    private boolean enabled = false;
    private String defaultCellId = "cell-0";
    private int virtualNodesPerCell = 150;
    private int cacheMaxSize = 100_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultCellId() {
        return defaultCellId;
    }

    public void setDefaultCellId(String defaultCellId) {
        this.defaultCellId = defaultCellId;
    }

    public int getVirtualNodesPerCell() {
        return virtualNodesPerCell;
    }

    public void setVirtualNodesPerCell(int virtualNodesPerCell) {
        this.virtualNodesPerCell = virtualNodesPerCell;
    }

    public int getCacheMaxSize() {
        return cacheMaxSize;
    }

    public void setCacheMaxSize(int cacheMaxSize) {
        this.cacheMaxSize = cacheMaxSize;
    }
}
