package com.hwyhaul.agent.omsr.handler;

import com.hwyhaul.agent.agent.AgentState;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.omsr.OmsrLoadEvents;
import com.hwyhaul.agent.omsr.OmsrLoadFlowCoordinator;
import com.hwyhaul.agent.playwright.OmsrPlaywrightLoadScraper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class OmsrScrapeEventHandler {

    private final OmsrLoadFlowCoordinator flowCoordinator;
    private final ApplicationEventPublisher eventPublisher;
    private final OmsrPlaywrightLoadScraper scraper;

    public OmsrScrapeEventHandler(
            OmsrLoadFlowCoordinator flowCoordinator,
            ApplicationEventPublisher eventPublisher,
            OmsrPlaywrightLoadScraper scraper
    ) {
        this.flowCoordinator = flowCoordinator;
        this.eventPublisher = eventPublisher;
        this.scraper = scraper;
    }

    @Async("omsrTaskExecutor")
    @EventListener
    public void handle(OmsrLoadEvents.FlowStarted event) {
        try {
            flowCoordinator.update(event.runId(), context -> context.state = AgentState.SCRAPE_DOM);
            CapturedOrdersPayload capturedPayload = scraper.scrapeFirstLoad(
                    event.runId(),
                    event.detailScrapeConcurrency(),
                    event.maxLoadsToExtract());
            flowCoordinator.update(event.runId(), context -> {
                context.capturedPayload = capturedPayload;
                context.omsrToken = capturedPayload == null ? null : capturedPayload.omsrToken;
            });
            eventPublisher.publishEvent(new OmsrLoadEvents.LoadsCaptured(event.runId(), capturedPayload));
        } catch (Exception e) {
            eventPublisher.publishEvent(new OmsrLoadEvents.FlowFailed(event.runId(), e));
        }
    }
}
