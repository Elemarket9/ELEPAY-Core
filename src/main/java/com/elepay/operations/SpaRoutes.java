package com.elepay.operations;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the web app's index page for client-side routes (checkout, merchant and operations consoles). */
@Controller
class SpaRoutes {
    @GetMapping({"/pay/{reference}", "/pay/l/{slug}", "/pay/m/{code}", "/merchant", "/merchant/**", "/ops", "/ops/**", "/app", "/app/**", "/developer", "/developer/**", "/kitchen", "/kitchen/**"})
    String index() {
        return "forward:/index.html";
    }
}
