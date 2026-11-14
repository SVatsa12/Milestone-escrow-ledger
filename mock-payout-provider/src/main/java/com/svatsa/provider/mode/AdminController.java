package com.svatsa.provider.mode;

import com.svatsa.provider.payouts.PayoutService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test and chaos control. Not part of the "bank" API.
 *
 * <pre>
 *   POST /admin/mode  {"mode":"RANDOM","delayMs":2000,"weights":{"SUCCESS":80,"FAIL":20},"seed":42}
 *   GET  /admin/mode
 *   POST /admin/reset   (truncates provider.payouts; the mode is unchanged)
 * </pre>
 */
@RestController
public class AdminController {

    private final ModeState modeState;
    private final PayoutService payoutService;

    public AdminController(ModeState modeState, PayoutService payoutService) {
        this.modeState = modeState;
        this.payoutService = payoutService;
    }

    @PostMapping("/admin/mode")
    public ResponseEntity<Object> setMode(@RequestBody ModeConfig config) {
        try {
            return ResponseEntity.ok(modeState.set(config));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "detail", e.getMessage()));
        }
    }

    @GetMapping("/admin/mode")
    public ModeConfig getMode() {
        return modeState.current();
    }

    @PostMapping("/admin/reset")
    public Map<String, String> reset() {
        payoutService.reset();
        return Map.of("status", "RESET");
    }
}
