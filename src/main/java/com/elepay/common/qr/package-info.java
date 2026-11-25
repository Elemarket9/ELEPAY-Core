/**
 * EMVCo / GhQR payload encoding shared by checkout and rails. Exposed as a named interface so
 * sub-package access from other application modules passes Spring Modulith verification.
 */
@org.springframework.modulith.NamedInterface("qr")
package com.elepay.common.qr;
