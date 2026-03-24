package com.interview.platform.controller;

import com.interview.platform.service.CommunicationLiveService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/communication")
public class CommunicationLiveController {

    private final CommunicationLiveService communicationLiveService;

    public CommunicationLiveController(CommunicationLiveService communicationLiveService) {
        this.communicationLiveService = communicationLiveService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startConversation() {
        String message = communicationLiveService.getInitialMessage();
        return ResponseEntity.ok(Map.of("message", message));
    }

    @PostMapping("/analyze-live")
    public ResponseEntity<Map<String, Object>> analyzeLive(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        Map<String, Object> analysis = communicationLiveService.analyzeSentence(text);
        return ResponseEntity.ok(analysis);
    }

    @PostMapping("/next")
    public ResponseEntity<Map<String, String>> nextMessage(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) request.get("history");
        String nextMessage = communicationLiveService.getNextMessage(history);
        return ResponseEntity.ok(Map.of("message", nextMessage));
    }

    @PostMapping("/analyze-overall")
    public ResponseEntity<Map<String, Object>> analyzeOverall(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) request.get("history");
        Map<String, Object> analysis = communicationLiveService.analyzeOverall(history);
        return ResponseEntity.ok(analysis);
    }
}
