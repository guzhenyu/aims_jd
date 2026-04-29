package com.jingyicare.aims_jd;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import com.jingyicare.aims_jd.utils.*;

// 晶医设备采集
@RestController
public class AimsJdController {
    @GetMapping("/api/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("ok");
    }
}

