/**
 * Read/write split and cell-aware JDBC DataSource routing shared across application modules
 * (operations, ledger, checkout, reconciliation, cell). Exposed as a named interface so
 * sub-package access passes Spring Modulith verification.
 */
@org.springframework.modulith.NamedInterface("datasource")
package com.elepay.common.datasource;
