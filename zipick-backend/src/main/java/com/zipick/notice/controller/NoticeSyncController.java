package com.zipick.notice.controller;

import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MyHomeSyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/notices")
public class NoticeSyncController {

    private final MyHomeSyncService syncService;

    public NoticeSyncController(MyHomeSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/sync")
    public NoticeSyncResponse synchronize() {
        return syncService.synchronize();
    }
}
