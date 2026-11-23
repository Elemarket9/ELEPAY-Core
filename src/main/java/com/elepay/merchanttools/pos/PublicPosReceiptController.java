package com.elepay.merchanttools.pos;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public token-based, time-limited receipt lookup; read-only and no PII. */
@RestController
@RequestMapping("/v1/public/pos/receipts")
public class PublicPosReceiptController {
    private final PublicPosReceiptService receipts;
    public PublicPosReceiptController(PublicPosReceiptService receipts) { this.receipts = receipts; }
    @GetMapping("/{token}")
    public ResponseEntity<PublicPosReceiptService.ReceiptView> receipt(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipts.find(token));
    }
}
