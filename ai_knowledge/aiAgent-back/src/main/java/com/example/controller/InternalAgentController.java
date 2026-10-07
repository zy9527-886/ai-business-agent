package com.example.controller;

import com.example.common.Result;
import com.example.entity.agent.VectorSearchRequest;
import com.example.service.AgentToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/agent/v1")
public class InternalAgentController {

    private final AgentToolService agentToolService;

    public InternalAgentController(AgentToolService agentToolService) {
        this.agentToolService = agentToolService;
    }

    @PostMapping("/knowledge/search")
    public Result searchKnowledge(@RequestBody VectorSearchRequest request) {
        return Result.success(agentToolService.searchKnowledge(request));
    }

    @GetMapping("/capabilities")
    public Result capabilities() {
        return Result.success(Map.of("knowledge", true, "businessDemo", true,
                "demoTools", List.of("orders", "tickets", "inventory")));
    }

    @GetMapping("/demo/orders/{orderNo}")
    public Result demoOrder(@PathVariable String orderNo) {
        return Result.success(agentToolService.queryDemoOrder(orderNo));
    }

    @GetMapping("/demo/tickets/{ticketNo}")
    public Result demoTicket(@PathVariable String ticketNo) {
        return Result.success(agentToolService.queryDemoTicket(ticketNo));
    }

    @GetMapping("/demo/inventory/{sku}")
    public Result demoInventory(@PathVariable String sku) {
        return Result.success(agentToolService.queryDemoInventory(sku));
    }
}

