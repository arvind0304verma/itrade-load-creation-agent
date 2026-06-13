package com.hwyhaul.agent.omsr;

import com.hwyhaul.agent.agent.AgentContext;
import com.hwyhaul.agent.agent.AgentState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
public class OmsrLoadFlowCoordinator {

    public static final int DEFAULT_DETAIL_SCRAPE_CONCURRENCY = 1;
    public static final int DEFAULT_MAX_LOADS_TO_EXTRACT = 0;
    public static final int DEFAULT_LOAD_API_CONCURRENCY = 1;

    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentMap<String, Execution> executions = new ConcurrentHashMap<>();

    public OmsrLoadFlowCoordinator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public OmsrFlowAccepted startFlow() {
        return startFlow(false, DEFAULT_DETAIL_SCRAPE_CONCURRENCY, DEFAULT_MAX_LOADS_TO_EXTRACT, DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startFlow(boolean skipHwyHaulApis) {
        return startFlow(skipHwyHaulApis, DEFAULT_DETAIL_SCRAPE_CONCURRENCY, DEFAULT_MAX_LOADS_TO_EXTRACT, DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startFlow(boolean skipHwyHaulApis, int detailScrapeConcurrency) {
        return startFlow(skipHwyHaulApis, detailScrapeConcurrency, DEFAULT_MAX_LOADS_TO_EXTRACT, DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startFlow(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract
    ) {
        return startFlow(skipHwyHaulApis, detailScrapeConcurrency, maxLoadsToExtract, DEFAULT_LOAD_API_CONCURRENCY);
    }

    public OmsrFlowAccepted startFlow(
            boolean skipHwyHaulApis,
            int detailScrapeConcurrency,
            int maxLoadsToExtract,
            int loadApiConcurrency
    ) {
        String runId = UUID.randomUUID().toString();
        int normalizedConcurrency = normalizeDetailScrapeConcurrency(detailScrapeConcurrency);
        int normalizedMaxLoadsToExtract = normalizeMaxLoadsToExtract(maxLoadsToExtract);
        int normalizedLoadApiConcurrency = normalizeLoadApiConcurrency(loadApiConcurrency);
        AgentContext context = new AgentContext();
        context.runId = runId;
        context.state = AgentState.INIT;
        context.skipHwyHaulApis = skipHwyHaulApis;
        context.detailScrapeConcurrency = normalizedConcurrency;
        context.maxLoadsToExtract = normalizedMaxLoadsToExtract;
        context.loadApiConcurrency = normalizedLoadApiConcurrency;
        executions.put(runId, new Execution(context));
        eventPublisher.publishEvent(new OmsrLoadEvents.FlowStarted(
                runId,
                skipHwyHaulApis,
                normalizedConcurrency,
                normalizedMaxLoadsToExtract,
                normalizedLoadApiConcurrency));
        return new OmsrFlowAccepted(runId, AgentState.INIT);
    }

    public AgentContext awaitCompletion(String runId, Duration timeout) throws Exception {
        Execution execution = requireExecution(runId);
        try {
            return execution.completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for OMSR load flow " + runId + ".", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("OMSR load flow " + runId + " failed.", cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for OMSR load flow " + runId
                    + " after " + timeout.toMillis() + " ms.", e);
        }
    }

    public AgentContext snapshot(String runId) {
        Execution execution = requireExecution(runId);
        synchronized (execution.monitor) {
            return copy(execution.context);
        }
    }

    public void update(String runId, Consumer<AgentContext> update) {
        Execution execution = requireExecution(runId);
        synchronized (execution.monitor) {
            update.accept(execution.context);
        }
    }

    public void complete(String runId, Consumer<AgentContext> update) {
        Execution execution = requireExecution(runId);
        AgentContext completedContext;
        synchronized (execution.monitor) {
            if (execution.completion.isDone()) {
                return;
            }
            update.accept(execution.context);
            completedContext = copy(execution.context);
        }
        execution.completion.complete(completedContext);
    }

    public void fail(String runId, Throwable error) {
        Execution execution = requireExecution(runId);
        synchronized (execution.monitor) {
            if (execution.completion.isDone()) {
                return;
            }
            execution.context.state = AgentState.FAILED;
            execution.context.errorMessage = errorMessage(error);
        }
        execution.completion.completeExceptionally(error);
    }

    private Execution requireExecution(String runId) {
        Execution execution = executions.get(runId);
        if (execution == null) {
            throw new NoSuchElementException("Unknown OMSR run id: " + runId);
        }
        return execution;
    }

    private AgentContext copy(AgentContext source) {
        AgentContext copy = new AgentContext();
        copy.runId = source.runId;
        copy.state = source.state;
        copy.skipHwyHaulApis = source.skipHwyHaulApis;
        copy.detailScrapeConcurrency = source.detailScrapeConcurrency;
        copy.maxLoadsToExtract = source.maxLoadsToExtract;
        copy.loadApiConcurrency = source.loadApiConcurrency;
        copy.capturedPayload = source.capturedPayload;
        copy.payload = source.payload;
        copy.payloads = source.payloads;
        copy.reasoning = source.reasoning;
        copy.omsrToken = source.omsrToken;
        copy.hwyHaulToken = source.hwyHaulToken;
        copy.loadApiResponse = source.loadApiResponse;
        copy.errorMessage = source.errorMessage;
        return copy;
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Unknown OMSR load flow failure.";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private int normalizeDetailScrapeConcurrency(int detailScrapeConcurrency) {
        return Math.max(DEFAULT_DETAIL_SCRAPE_CONCURRENCY, detailScrapeConcurrency);
    }

    private int normalizeMaxLoadsToExtract(int maxLoadsToExtract) {
        return Math.max(DEFAULT_MAX_LOADS_TO_EXTRACT, maxLoadsToExtract);
    }

    private int normalizeLoadApiConcurrency(int loadApiConcurrency) {
        return Math.max(DEFAULT_LOAD_API_CONCURRENCY, loadApiConcurrency);
    }

    private static class Execution {
        private final Object monitor = new Object();
        private final AgentContext context;
        private final CompletableFuture<AgentContext> completion = new CompletableFuture<>();

        private Execution(AgentContext context) {
            this.context = context;
        }
    }
}
