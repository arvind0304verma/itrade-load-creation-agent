package com.hwyhaul.agent.playwright;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hwyhaul.agent.config.OmsrBrowserConfig;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.hwyhaul.agent.omsr.mongo.OmsrProcessedLoadStore;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OmsrPlaywrightLoadScraper {

    private static final Logger log = LoggerFactory.getLogger(OmsrPlaywrightLoadScraper.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final double DEFAULT_TIMEOUT_MS = 60_000;
    // Pagination "Next" clicks should fail fast when a matched link is not actionable
    // (e.g. a disabled last-page control) instead of blocking for the full default timeout.
    private static final double PAGINATION_CLICK_TIMEOUT_MS = 15_000;
    private static final double ROW_WAIT_TIMEOUT_MS = 15_000;
    private static final double ADDRESS_POPUP_TIMEOUT_MS = 4_000;
    private static final double FILTER_POPUP_TIMEOUT_MS = 15_000;
    private static final int ORDER_LIST_SCROLL_STEPS = 60;
    private static final int ORDER_LIST_STABLE_SCROLL_SIGNATURES = 3;
    private static final BigDecimal IMPLAUSIBLE_PALLET_COUNT_THRESHOLD = BigDecimal.valueOf(100);
    private static final String NUMERIC_VALUE_PATTERN = "[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/New_York");
    private static final String OMSR_ORDER_IFRAME_SELECTOR = "iframe#mainFrame, iframe[src*='trans_order_status' i], iframe[src*='logistics_t' i]";
    private static final String OMSR_ORDER_FRAME_URL_MARKER = "trans_order_status";
    private static final String OMSR_LOAD_DETAILS_LINK_SELECTOR = "a[href*='load_details']";
    private static final String OMSR_ORDER_STATUS_FILTER_BUTTON_SELECTOR =
            "input[type='button'][name='Submit4'][value='Filter'], input[name='Submit4'], input[value='Filter'][onclick*='openlogisFilterWindow']";
    private static final String OMSR_ORDER_STATUS_ACTION_CONTROL_SELECTOR = "input[type='button'], input[type='submit'], button";
    private static final Pattern OMSR_LOAD_NUMBER_PATTERN = Pattern.compile("^\\d{5,15}$");
    private static final Pattern COMMA_SEPARATED_ADDRESS_PATTERN = Pattern.compile(
            "^(?<street>.+?),\\s*(?<city>.+?),\\s*(?<state>[A-Z]{2})(?:\\s+(?<zip>\\d{5}(?:-\\d{4})?))?(?:\\s*,?\\s*(?:United States|USA|US))?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPACE_SEPARATED_ADDRESS_PATTERN = Pattern.compile(
            "^(?<street>.+?)\\s+(?<city>.+?)\\s+(?<state>[A-Z]{2})(?:\\s+(?<zip>\\d{5}(?:-\\d{4})?))?(?:\\s*,?\\s*(?:United States|USA|US))?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMMA_ADDRESS_SEARCH_PATTERN = Pattern.compile(
            "\\b\\d{1,8}\\s+[A-Za-z0-9][A-Za-z0-9 .#&'\\-]+,\\s*[A-Za-z .'-]+,\\s*[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?(?:\\s*,?\\s*(?:United States|USA|US))?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPACE_ADDRESS_SEARCH_PATTERN = Pattern.compile(
            "\\b\\d{1,8}\\s+[A-Za-z0-9][A-Za-z0-9 .#&'\\-]+\\s+[A-Za-z .'-]+\\s+[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?(?:\\s*,?\\s*(?:United States|USA|US))?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PO_NUMBER_LABEL_PATTERN = Pattern.compile(
            "^(?:po\\s*number|po\\s*no\\.?|purchase\\s*order(?:\\s*(?:number|no\\.?))?)\\s*[:\\-]?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PO_NUMBER_INLINE_PATTERN = Pattern.compile(
            "^(?:po\\s*number|po\\s*no\\.?|purchase\\s*order(?:\\s*(?:number|no\\.?))?)\\s*[:\\-]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PO_NUMBER_VALUE_PATTERN = Pattern.compile("\\b(?=[A-Za-z0-9-]*\\d)[A-Za-z0-9-]{4,}\\b");

    private final OmsrBrowserConfig browserConfig;
    private final Optional<OmsrProcessedLoadStore> processedLoadStore;
    private volatile String omsrToken;
    private String lastOrderDetailResponseJson;

    public OmsrPlaywrightLoadScraper(OmsrBrowserConfig browserConfig) {
        this(browserConfig, Optional.empty());
    }

    @Autowired
    public OmsrPlaywrightLoadScraper(
            OmsrBrowserConfig browserConfig,
            Optional<OmsrProcessedLoadStore> processedLoadStore
    ) {
        this.browserConfig = browserConfig;
        this.processedLoadStore = processedLoadStore == null ? Optional.empty() : processedLoadStore;
    }

    private record OmsrLoadSummary(CapturedOrdersPayload.CapturedOrder load, String detailUrl) {
    }

    private record IndexedOmsrLoadSummary(int index, OmsrLoadSummary summary) {
    }

    private record IndexedCapturedOrder(int index, CapturedOrdersPayload.CapturedOrder load) {
    }

    private record OmsrAuthenticatedSession(String storageState, String sessionStorageState) {
    }

    private record ShippingTotals(String totalQuantity, String caseCount, String pallets, String cubes, String weight) {
    }

    private record ShippingToken(String label, String value) {
    }

    public CapturedOrdersPayload scrapeFirstLoad() {
        return scrapeFirstLoad(null, 1, 0);
    }

    public CapturedOrdersPayload scrapeFirstLoad(String runId) {
        return scrapeFirstLoad(runId, 1, 0);
    }

    public CapturedOrdersPayload scrapeFirstLoad(String runId, int detailScrapeConcurrency) {
        return scrapeFirstLoad(runId, detailScrapeConcurrency, 0);
    }

    public CapturedOrdersPayload scrapeFirstLoad(
            String runId,
            int detailScrapeConcurrency,
            int maxLoadsToExtract
    ) {
        int normalizedConcurrency = normalizeDetailScrapeConcurrency(detailScrapeConcurrency);
        int normalizedMaxLoadsToExtract = normalizeMaxLoadsToExtract(maxLoadsToExtract);
        validateLoginConfiguration();
        omsrToken = null;
        lastOrderDetailResponseJson = null;

        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(browserConfig.isHeadless())
        );
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("en-US")
                .setExtraHTTPHeaders(Map.of("accept-language", "en-US,en;q=0.9")));
        Page page = context.newPage();
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
        page.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MS);
        page.onResponse(this::captureOmsrTokenFromResponse);

        try {
            OmsrAuthenticatedSession authenticatedSession = login(page, context);

            CapturedOrdersPayload payload = new CapturedOrdersPayload();
            payload.omsrToken = omsrToken;
            payload.loads = new ArrayList<>();

            Frame ordersFrame = goToOrdersScreen(page);
            int maxLoads = browserConfig.getMaxLoadsFromFirstScreen();
            List<OmsrLoadSummary> loadSummaries = collectOrderLoadSummaries(ordersFrame, maxLoads);
            if (loadSummaries.isEmpty()) {
                if (hasNoDataPlaceholder(ordersFrame)) {
                    captureDebugSnapshot(page, "omsr-orders-no-data");
                    log.debug("OMSR order list returned no rows.");
                    return payload;
                }
                captureDebugSnapshot(page, "omsr-orders-rows-not-found");
                throw new IllegalStateException("No OMSR load rows found on the order status page.");
            }

            loadSummaries = skipAlreadyExtractedLoads(loadSummaries);
            loadSummaries = limitLoadsToExtract(loadSummaries, normalizedMaxLoadsToExtract);
            if (loadSummaries.isEmpty()) {
                log.info("All collected OMSR loads were already extracted; returning an empty payload.");
                return payload;
            }

            if (normalizedConcurrency == 1 || loadSummaries.size() == 1) {
                scrapeLoadDetailsSequentially(runId, payload, page, ordersFrame, loadSummaries);
            } else {
                payload.loads.addAll(scrapeLoadDetailsInParallel(
                        runId,
                        loadSummaries,
                        authenticatedSession,
                        normalizedConcurrency));
            }
            payload.omsrToken = firstNonBlank(payload.omsrToken, omsrToken);
            return payload;
        } finally {
            logout(page);
            close(context, browser, playwright);
        }
    }

    private void scrapeLoadDetailsSequentially(
            String runId,
            CapturedOrdersPayload payload,
            Page page,
            Frame ordersFrame,
            List<OmsrLoadSummary> loadSummaries
    ) {
        Frame detailFrame = ordersFrame;
        for (OmsrLoadSummary summary : loadSummaries) {
            CapturedOrdersPayload.CapturedOrder load = summary.load();
            lastOrderDetailResponseJson = null;
            detailFrame = openLoadDetail(page, detailFrame, summary);

            Map<String, String> detailAttributes = scrapeDetailAttributes(detailFrame);
            load.pageUrl = detailFrame.url();
            load.detailAttributes = new LinkedHashMap<>(detailAttributes);
            enrichLoadFromDetailAttributes(load, detailAttributes, detailFrame);
            enrichLoadFromOrderDetailResponse(load, lastOrderDetailResponseJson);

            payload.loads.add(load);
            markLoadExtracted(runId, load);
            logExtractedLoad(detailFrame, load);
        }
    }

    private List<CapturedOrdersPayload.CapturedOrder> scrapeLoadDetailsInParallel(
            String runId,
            List<OmsrLoadSummary> loadSummaries,
            OmsrAuthenticatedSession authenticatedSession,
            int detailScrapeConcurrency
    ) {
        int workerCount = Math.min(normalizeDetailScrapeConcurrency(detailScrapeConcurrency), loadSummaries.size());
        log.info("Scraping {} OMSR load detail page(s) with detailScrapeConcurrency={}.",
                loadSummaries.size(), workerCount);

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<List<IndexedCapturedOrder>>> tasks = new ArrayList<>();
            for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                List<IndexedOmsrLoadSummary> assignedLoads = new ArrayList<>();
                for (int loadIndex = workerIndex; loadIndex < loadSummaries.size(); loadIndex += workerCount) {
                    assignedLoads.add(new IndexedOmsrLoadSummary(loadIndex, loadSummaries.get(loadIndex)));
                }
                int workerNumber = workerIndex + 1;
                tasks.add(() -> scrapeAssignedLoadDetails(runId, assignedLoads, authenticatedSession, workerNumber));
            }

            List<Future<List<IndexedCapturedOrder>>> futures = executor.invokeAll(tasks);
            List<IndexedCapturedOrder> indexedLoads = new ArrayList<>();
            for (Future<List<IndexedCapturedOrder>> future : futures) {
                try {
                    indexedLoads.addAll(future.get());
                } catch (ExecutionException e) {
                    throw loadDetailWorkerFailure(e);
                }
            }

            indexedLoads.sort(Comparator.comparingInt(IndexedCapturedOrder::index));
            return indexedLoads.stream()
                    .map(IndexedCapturedOrder::load)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while scraping OMSR load details in parallel.", e);
        } finally {
            shutdownExecutor(executor);
        }
    }

    private List<IndexedCapturedOrder> scrapeAssignedLoadDetails(
            String runId,
            List<IndexedOmsrLoadSummary> assignedLoads,
            OmsrAuthenticatedSession authenticatedSession,
            int workerNumber
    ) {
        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(browserConfig.isHeadless())
        );
        BrowserContext context = browser.newContext(newAuthenticatedContextOptions(authenticatedSession));
        applySessionStorageInitScript(context, authenticatedSession);

        Page page = context.newPage();
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
        page.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MS);
        AtomicReference<String> orderDetailResponseJson = new AtomicReference<>();
        page.onResponse(response -> captureOmsrTokenFromResponse(response, orderDetailResponseJson));

        try {
            List<IndexedCapturedOrder> capturedLoads = new ArrayList<>();
            log.debug("OMSR detail scrape worker {} processing {} load(s).", workerNumber, assignedLoads.size());
            for (IndexedOmsrLoadSummary assignedLoad : assignedLoads) {
                CapturedOrdersPayload.CapturedOrder load = scrapeLoadDetailOnWorkerPage(
                        runId,
                        assignedLoad.summary(),
                        page,
                        orderDetailResponseJson);
                capturedLoads.add(new IndexedCapturedOrder(assignedLoad.index(), load));
            }
            return capturedLoads;
        } finally {
            close(context, browser, playwright);
        }
    }

    private CapturedOrdersPayload.CapturedOrder scrapeLoadDetailOnWorkerPage(
            String runId,
            OmsrLoadSummary summary,
            Page page,
            AtomicReference<String> orderDetailResponseJson
    ) {
        CapturedOrdersPayload.CapturedOrder load = summary.load();
        orderDetailResponseJson.set(null);

        String beforeUrl = page.url();
        page.navigate(summary.detailUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
        if (isAuthPage(page)) {
            captureDebugSnapshot(page, "omsr-load-detail-auth-redirect-" + safeDebugName(load.externalOrderId));
            throw new IllegalStateException("OMSR load detail page redirected back to login for load "
                    + load.externalOrderId + ". The restored worker session was not authenticated.");
        }

        Frame detailFrame = bestEffortDetailFrame(page);
        waitForLoadDetail(detailFrame, beforeUrl, orderDetailResponseJson);

        Map<String, String> detailAttributes = scrapeDetailAttributes(detailFrame);
        load.pageUrl = detailFrame.url();
        load.detailAttributes = new LinkedHashMap<>(detailAttributes);
        enrichLoadFromDetailAttributes(load, detailAttributes, detailFrame);
        enrichLoadFromOrderDetailResponse(load, orderDetailResponseJson.get());

        markLoadExtracted(runId, load);
        logExtractedLoad(detailFrame, load);
        return load;
    }

    private RuntimeException loadDetailWorkerFailure(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("OMSR detail scrape worker failed.", cause);
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Browser.NewContextOptions newAuthenticatedContextOptions(OmsrAuthenticatedSession authenticatedSession) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setLocale("en-US")
                .setExtraHTTPHeaders(Map.of("accept-language", "en-US,en;q=0.9"));
        if (authenticatedSession != null
                && authenticatedSession.storageState() != null
                && !authenticatedSession.storageState().isBlank()
                && !authenticatedSession.storageState().trim().equals("{}")) {
            options.setStorageState(authenticatedSession.storageState());
        }
        return options;
    }

    private void applySessionStorageInitScript(BrowserContext context, OmsrAuthenticatedSession authenticatedSession) {
        if (authenticatedSession == null
                || authenticatedSession.sessionStorageState() == null
                || authenticatedSession.sessionStorageState().isBlank()
                || authenticatedSession.sessionStorageState().trim().equals("{}")) {
            return;
        }

        try {
            String sessionStorageJsonLiteral = mapper.writeValueAsString(authenticatedSession.sessionStorageState());
            context.addInitScript("""
                    (() => {
                      try {
                        const items = JSON.parse(%s);
                        Object.entries(items || {}).forEach(([key, value]) => {
                          if (value !== null && value !== undefined) {
                            sessionStorage.setItem(key, String(value));
                          }
                        });
                      } catch (error) {
                      }
                    })();
                    """.formatted(sessionStorageJsonLiteral));
        } catch (Exception e) {
            log.debug("Unable to restore OMSR worker session storage: {}", e.getMessage());
        }
    }

    private int normalizeDetailScrapeConcurrency(int detailScrapeConcurrency) {
        return Math.max(1, detailScrapeConcurrency);
    }

    private int normalizeMaxLoadsToExtract(int maxLoadsToExtract) {
        return Math.max(0, maxLoadsToExtract);
    }

    private List<OmsrLoadSummary> limitLoadsToExtract(List<OmsrLoadSummary> loadSummaries, int maxLoadsToExtract) {
        if (loadSummaries == null || loadSummaries.isEmpty() || maxLoadsToExtract <= 0) {
            return loadSummaries == null ? List.of() : loadSummaries;
        }
        if (loadSummaries.size() <= maxLoadsToExtract) {
            return loadSummaries;
        }

        log.info("Limiting OMSR detail extraction to {} load(s); {} collected load(s) remain after skip filtering.",
                maxLoadsToExtract, loadSummaries.size());
        return loadSummaries.stream()
                .limit(maxLoadsToExtract)
                .toList();
    }

    private String safeDebugName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private OmsrAuthenticatedSession login(Page page, BrowserContext context) {
        try {
            page.navigate(browserConfig.getAuthUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
            page.locator(browserConfig.getUserNameSelector()).fill(browserConfig.getUsername());
            page.locator(browserConfig.getPasswordSelector()).fill(browserConfig.getPassword());
            page.locator(browserConfig.getSubmitSelector()).click();

            waitForLoginResult(page);
            captureTokenFromStorage(page);
            String storageState = collectAuthenticationState(context, page, "omsr-auth-state");
            String sessionStorageState = collectSessionStorageState(page, "omsr-session-storage");

            String loginFailureMessage = extractLoginFailureMessage(page);
            if (loginFailureMessage != null) {
                captureDebugSnapshot(page, "omsr-login-failed");
                throw new IllegalStateException("OMSR login failed: " + loginFailureMessage
                        + " Verify agent.omsr.username and agent.omsr.password.");
            }

            if (omsrToken == null || omsrToken.isBlank()) {
                log.warn("OMSR login completed without capturing an OMSR token. Current URL: {}", page.url());
            } else {
                log.debug("OMSR login completed. Current URL: {}, omsrTokenPresent=true", page.url());
            }
            return new OmsrAuthenticatedSession(storageState, sessionStorageState);
        } catch (PlaywrightException e) {
            captureDebugSnapshot(page, "omsr-login-error");
            throw e;
        }
    }

    private void logout(Page page) {
        if (!browserConfig.isLogoutEnabled() || page == null) {
            return;
        }

        try {
            if (isAuthPage(page)) {
                return;
            }

            boolean logoutRequested = clickLogoutFromSite(page) || navigateToLogoutUrl(page);
            if (!logoutRequested) {
                log.warn("OMSR logout was enabled but no logout UI or logout URL was available.");
                return;
            }

            if (waitForLogoutResult(page, 10_000)) {
                log.debug("OMSR logout completed. Current URL: {}", page.url());
            } else {
                log.warn("OMSR logout was requested but did not reach the login/logout page within the timeout. Current URL: {}",
                        page.url());
            }
        } catch (Exception e) {
            log.warn("Unable to logout from OMSR site before closing the browser: {}", e.getMessage());
        }
    }

    private boolean clickLogoutFromSite(Page page) {
        return clickConfiguredLogoutSelector(page)
                || clickLogoutCandidateInFrames(page)
                || revealLogoutMenuAndClickLogout(page);
    }

    private boolean clickConfiguredLogoutSelector(Page page) {
        String selector = browserConfig.getLogoutSelector();
        if (selector == null || selector.isBlank()) {
            return false;
        }

        try {
            Locator logout = page.locator(selector);
            if (count(logout) == 0) {
                return false;
            }
            logout.first().click(new Locator.ClickOptions().setTimeout(5_000));
            return true;
        } catch (PlaywrightException e) {
            log.debug("Configured OMSR logout selector was not clickable: {}", e.getMessage());
            return false;
        }
    }

    private boolean revealLogoutMenuAndClickLogout(Page page) {
        String selector = browserConfig.getLogoutMenuSelector();
        if (selector == null || selector.isBlank()) {
            return false;
        }

        try {
            Locator menuTriggers = page.locator(selector);
            int triggerCount = Math.min(count(menuTriggers), 5);
            for (int index = 0; index < triggerCount; index++) {
                try {
                    menuTriggers.nth(index).click(new Locator.ClickOptions().setTimeout(5_000));
                    sleep(250);
                    if (clickConfiguredLogoutSelector(page) || clickLogoutCandidateInFrames(page)) {
                        return true;
                    }
                } catch (PlaywrightException e) {
                    log.debug("OMSR logout menu trigger {} was not clickable: {}", index, e.getMessage());
                }
            }
        } catch (PlaywrightException e) {
            log.debug("Unable to inspect OMSR logout menu selector: {}", e.getMessage());
        }
        return false;
    }

    private boolean clickLogoutCandidateInFrames(Page page) {
        for (Frame frame : page.frames()) {
            try {
                Object clicked = frame.evaluate("""
                        () => {
                          const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                          const isVisible = el => {
                            if (!el) return false;
                            const style = window.getComputedStyle(el);
                            if (!style || style.display === "none" || style.visibility === "hidden" || style.pointerEvents === "none") {
                              return false;
                            }
                            const rect = el.getBoundingClientRect();
                            return rect.width > 0 && rect.height > 0;
                          };
                          const candidates = Array.from(document.querySelectorAll(
                            "a, button, [role='button'], [role='menuitem'], [onclick], [data-testid], [aria-label]"
                          ));
                          const logout = candidates
                            .filter(isVisible)
                            .find(el => {
                              const text = normalize(el.innerText || el.textContent).toLowerCase();
                              const label = normalize(el.getAttribute("aria-label") || el.getAttribute("data-testid")).toLowerCase();
                              const href = normalize(el.getAttribute("href")).toLowerCase();
                              const onclick = normalize(el.getAttribute("onclick")).toLowerCase();
                              return text === "logout"
                                || text === "log out"
                                || text === "sign out"
                                || label.includes("logout")
                                || label.includes("signout")
                                || label.includes("sign out")
                                || href.includes("/logout")
                                || onclick.includes("logout");
                            });
                          if (!logout) {
                            return false;
                          }
                          logout.click();
                          return true;
                        }
                        """);
                if (Boolean.TRUE.equals(clicked)) {
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("Unable to inspect OMSR frame for logout link: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean navigateToLogoutUrl(Page page) {
        String logoutUrl = absolutePageUrl(browserConfig.getLogoutUrl());
        if (logoutUrl == null || logoutUrl.isBlank()) {
            return false;
        }

        page.navigate(logoutUrl, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.COMMIT)
                .setTimeout(10_000));
        return true;
    }

    private boolean waitForLogoutResult(Page page, double timeoutMs) {
        long deadline = System.nanoTime() + (long) (timeoutMs * 1_000_000);
        while (System.nanoTime() < deadline) {
            if (isAuthPage(page) || isLogoutPage(page) || hasLoginForm(page)) {
                return true;
            }
            sleep(100);
        }
        return false;
    }

    private boolean isLogoutPage(Page page) {
        return page.url().toLowerCase(Locale.ROOT).contains("/logout");
    }

    private boolean hasLoginForm(Page page) {
        try {
            Object value = page.evaluate("""
                    () => document.querySelector("input[type='password'], input[name='password']") !== null
                    """);
            return Boolean.TRUE.equals(value);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private Frame goToOrdersScreen(Page page) {
        navigateToOrdersUrl(page);
        if (isAuthPage(page)) {
            captureDebugSnapshot(page, "omsr-orders-redirected-to-auth");
            throw new IllegalStateException("OMSR orders page redirected back to login. The session was not authenticated.");
        }
        Frame ordersFrame = resolveOrdersFrame(page);
        ordersFrame = applyConfirmedOrderStatusFilter(page, ordersFrame);
        waitForOptionalRows(ordersFrame);
        return ordersFrame;
    }

    private Frame applyConfirmedOrderStatusFilter(Page page, Frame ordersFrame) {
        String beforeSignature = orderListSignature(ordersFrame);
        Page filterPage = openOrderStatusFilterWindow(page, ordersFrame);
        try {
            filterPage.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
            filterPage.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MS);
            filterPage.waitForLoadState();

            if (!selectConfirmedOrderStatusOption(filterPage)) {
                captureDebugSnapshot(filterPage, "omsr-confirmed-status-filter-option-not-found");
                throw new IllegalStateException("Unable to select Confirmed from the OMSR Order Status filter window.");
            }

            sleep(100);
            if (!isPageClosed(filterPage) && !submitOmsrFilterWindow(filterPage)) {
                captureDebugSnapshot(filterPage, "omsr-confirmed-status-filter-submit-not-found");
                throw new IllegalStateException("Unable to apply the OMSR Order Status Confirmed filter.");
            }

            Frame filteredFrame = waitForOrdersFrameAfterFilter(page, beforeSignature);
            if (!isPageClosed(filterPage)) {
                log.debug("OMSR Order Status filter window remained open after submit; closing it after parent order list became ready.");
            }
            log.debug("Applied OMSR Order Status Confirmed filter before collecting load rows.");
            return filteredFrame;
        } catch (PlaywrightException e) {
            captureDebugSnapshot(page, "omsr-confirmed-status-filter-failed");
            throw e;
        } finally {
            if (!isPageClosed(filterPage)) {
                try {
                    filterPage.close();
                } catch (PlaywrightException ignored) {
                    // The popup may already be closing after applying the filter.
                }
            }
        }
    }

    private Page openOrderStatusFilterWindow(Page page, Frame ordersFrame) {
        Locator filterButton = omsrOpenStatusFilterButton(ordersFrame);
        if (filterButton == null) {
            captureDebugSnapshot(page, "omsr-open-status-filter-button-not-found");
            throw new IllegalStateException("Unable to locate the OMSR Order Status Filter button.");
        }

        Page filterPage = page.waitForPopup(
                new Page.WaitForPopupOptions().setTimeout(FILTER_POPUP_TIMEOUT_MS),
                () -> filterButton.click(new Locator.ClickOptions().setTimeout(FILTER_POPUP_TIMEOUT_MS))
        );
        if (filterPage == null) {
            captureDebugSnapshot(page, "omsr-open-status-filter-popup-not-found");
            throw new IllegalStateException("OMSR Order Status Filter button did not open a filter window.");
        }
        return filterPage;
    }

    private Locator omsrOpenStatusFilterButton(Frame ordersFrame) {
        if (ordersFrame == null) {
            return null;
        }

        Locator exactButton = ordersFrame.locator(OMSR_ORDER_STATUS_FILTER_BUTTON_SELECTOR);
        if (count(exactButton) > 0) {
            return exactButton.first();
        }

        Locator controls = ordersFrame.locator(OMSR_ORDER_STATUS_ACTION_CONTROL_SELECTOR);
        int controlCount = count(controls);
        Locator plainFilterButton = null;
        for (int index = 0; index < controlCount; index++) {
            Locator control = controls.nth(index);
            String name = nullToEmpty(attribute(control, "name"));
            String value = nullToEmpty(attribute(control, "value"));
            String onclick = nullToEmpty(attribute(control, "onclick"));
            String controlText = nullToEmpty(text(control));
            String combined = String.join(" ",
                    name,
                    value,
                    onclick,
                    controlText
            ).toLowerCase(Locale.ROOT);
            if (combined.contains("filter") && combined.contains("openlogisfilterwindow")) {
                return control;
            }
            if (combined.contains("submit4") && combined.contains("filter")) {
                return control;
            }

            String label = normalizedActionControlLabel(value, controlText,
                    attribute(control, "aria-label"),
                    attribute(control, "title"),
                    name
            );
            if ("filter".equals(label)) {
                if (hasOmsrOrderStatusActionNeighbors(controls, index, controlCount)) {
                    return control;
                }
                if (plainFilterButton == null) {
                    plainFilterButton = control;
                }
            }
        }
        return plainFilterButton;
    }

    private boolean hasOmsrOrderStatusActionNeighbors(Locator controls, int index, int controlCount) {
        Set<String> labels = new LinkedHashSet<>();
        int start = Math.max(0, index - 3);
        int end = Math.min(controlCount, index + 4);
        for (int neighborIndex = start; neighborIndex < end; neighborIndex++) {
            if (neighborIndex == index) {
                continue;
            }
            labels.add(normalizedActionControlLabel(controls.nth(neighborIndex)));
        }

        return labels.contains("cdf")
                || (labels.contains("clear") && labels.contains("submit"));
    }

    private String normalizedActionControlLabel(Locator control) {
        if (control == null) {
            return "";
        }
        return normalizedActionControlLabel(
                attribute(control, "value"),
                text(control),
                attribute(control, "aria-label"),
                attribute(control, "title"),
                attribute(control, "name")
        );
    }

    private String normalizedActionControlLabel(String... values) {
        String value = firstNonBlank(values);
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private boolean selectConfirmedOrderStatusOption(Page filterPage) {
        Object value = evaluateFilterPopupUntilValue(filterPage, """
                () => {
                  const normalize = value => String(value || "").replace(/\\u00a0/g, " ").replace(/\\s+/g, " ").trim();
                  const key = value => normalize(value).toLowerCase();
                  const cssEscape = value => window.CSS && CSS.escape
                    ? CSS.escape(value)
                    : String(value).replace(/["\\\\]/g, "\\\\$&");
                  const visible = element => {
                    if (!element) return false;
                    const style = window.getComputedStyle(element);
                    const rect = element.getBoundingClientRect();
                    return style.display !== "none"
                      && style.visibility !== "hidden"
                      && (rect.width > 0 || rect.height > 0 || element.options?.length > 0);
                  };
                  const optionMatchesConfirmed = option => {
                    const text = key(option.textContent);
                    const value = key(option.value);
                    const strippedText = text.replace(/^[-|\\s]+|[-|\\s]+$/g, "");
                    return text === "confirmed"
                      || value === "confirmed"
                      || strippedText === "confirmed";
                  };
                  const nearbyText = select => {
                    const parts = [];
                    if (select.id) {
                      const label = document.querySelector(`label[for="${cssEscape(select.id)}"]`);
                      if (label) parts.push(label.innerText || label.textContent || "");
                    }
                    let current = select;
                    for (let depth = 0; depth < 4 && current; depth++) {
                      current = current.parentElement;
                      if (current) parts.push(current.innerText || current.textContent || "");
                    }
                    return key(parts.join(" "));
                  };
                  const candidates = Array.from(document.querySelectorAll("select"))
                    .map(select => {
                      const confirmedOption = Array.from(select.options || []).find(optionMatchesConfirmed);
                      if (!confirmedOption) return null;

                      const identity = key([
                        select.name,
                        select.id,
                        select.getAttribute("aria-label"),
                        select.title
                      ].filter(Boolean).join(" "));
                      const nearby = nearbyText(select);
                      let score = 0;
                      if (/order\\s*status|status\\s*order/.test(identity)) score += 60;
                      if (identity.includes("status")) score += 30;
                      if (/order\\s*status/.test(nearby)) score += 60;
                      if (nearby.includes("status")) score += 20;
                      if (visible(select)) score += 5;
                      return { select, confirmedOption, score, identity, nearby };
                    })
                    .filter(Boolean)
                    .sort((left, right) => right.score - left.score);

                  if (!candidates.length) return null;
                  const best = candidates[0];
                  if (best.score <= 0 && candidates.length > 1) return null;

                  if (best.select.multiple) {
                    Array.from(best.select.options || []).forEach(option => {
                      option.selected = option === best.confirmedOption;
                    });
                  } else {
                    best.select.value = best.confirmedOption.value;
                    best.confirmedOption.selected = true;
                  }

                  best.select.dispatchEvent(new Event("input", { bubbles: true }));
                  best.select.dispatchEvent(new Event("change", { bubbles: true }));
                  if (window.jQuery) {
                    try {
                      const jqSelect = window.jQuery(best.select);
                      jqSelect.trigger("change");
                      if (typeof jqSelect.multiselect === "function") {
                        jqSelect.multiselect("refresh");
                      }
                    } catch (error) {
                    }
                  }

                  return `${best.select.name || best.select.id || "select"}=${normalize(best.confirmedOption.textContent || best.confirmedOption.value)}`;
                }
                """, false);
        boolean selected = value != null && !value.toString().isBlank();
        if (selected) {
            log.debug("Selected OMSR Order Status filter option: {}", value);
        }
        return selected;
    }

    private boolean submitOmsrFilterWindow(Page filterPage) {
        Object value = evaluateFilterPopupUntilValue(filterPage, """
                () => {
                  const normalize = value => String(value || "").replace(/\\u00a0/g, " ").replace(/\\s+/g, " ").trim();
                  const key = value => normalize(value).toLowerCase();
                  const visible = element => {
                    if (!element) return false;
                    const style = window.getComputedStyle(element);
                    const rect = element.getBoundingClientRect();
                    return style.display !== "none"
                      && style.visibility !== "hidden"
                      && rect.width > 0
                      && rect.height > 0;
                  };
                  const controls = Array.from(document.querySelectorAll("input, button, a"))
                    .filter(element => {
                      const tag = element.tagName.toLowerCase();
                      const type = key(element.getAttribute("type") || "");
                      return !element.disabled
                        && visible(element)
                        && (tag !== "input" || ["button", "submit", "image", ""].includes(type));
                    })
                    .map(element => {
                      const label = key(element.value || element.innerText || element.textContent || element.title || element.name || "");
                      const onclick = key(element.getAttribute("onclick") || "");
                      let score = 0;
                      if (/^(apply|filter|submit|ok|go|save|search)$/.test(label)) score += 50;
                      if (/apply\\s+filter|use\\s+filter|run\\s+filter/.test(label)) score += 60;
                      if (["clear", "cancel", "close", "reset"].includes(label)) score -= 100;
                      if (onclick.includes("mysubmit") || onclick.includes("submit")) score += 20;
                      if (key(element.getAttribute("type") || "") === "submit") score += 15;
                      return { element, label, score };
                    })
                    .filter(candidate => candidate.score > 0)
                    .sort((left, right) => right.score - left.score);

                  if (controls.length) {
                    controls[0].element.click();
                    return controls[0].label || "clicked";
                  }

                  const form = document.forms && document.forms.length ? document.forms[0] : null;
                  if (form) {
                    if (typeof form.requestSubmit === "function") {
                      form.requestSubmit();
                    } else {
                      form.submit();
                    }
                    return "form-submit";
                  }

                  return null;
                }
                """, true);
        boolean submitted = value != null && !value.toString().isBlank();
        if (submitted) {
            log.debug("Submitted OMSR Order Status filter window using: {}", value);
        }
        return submitted;
    }

    private Object evaluateFilterPopupUntilValue(Page filterPage, String expression, boolean closedPageMeansSuccess) {
        PlaywrightException lastError = null;
        long deadline = System.nanoTime() + (long) (FILTER_POPUP_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            if (isPageClosed(filterPage)) {
                return closedPageMeansSuccess ? "popup-closed" : null;
            }

            try {
                Object value = filterPage.evaluate(expression);
                if (value != null && !value.toString().isBlank()) {
                    return value;
                }
            } catch (PlaywrightException e) {
                if (isPageClosed(filterPage) && closedPageMeansSuccess) {
                    return "popup-closed";
                }
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
            }
            sleep(100);
        }

        if (lastError != null) {
            log.debug("OMSR filter popup evaluation kept navigating before it became usable: {}", lastError.getMessage());
        }
        return null;
    }

    private Frame waitForOrdersFrameAfterFilter(Page page, String beforeSignature) {
        long deadline = System.nanoTime() + (long) (DEFAULT_TIMEOUT_MS * 1_000_000);
        long sameSignatureReadySince = 0;

        while (System.nanoTime() < deadline) {
            Frame frame = bestEffortOrdersFrame(page);
            if (frame != null) {
                boolean ready = firstDataRow(frame) != null || hasNoDataPlaceholder(frame);
                String afterSignature = orderListSignature(frame);
                if (ready) {
                    if (beforeSignature == null
                            || afterSignature == null
                            || !afterSignature.equals(beforeSignature)) {
                        return frame;
                    }

                    if (sameSignatureReadySince == 0) {
                        sameSignatureReadySince = System.nanoTime();
                    } else if (System.nanoTime() - sameSignatureReadySince >= 1_000_000_000L) {
                        return frame;
                    }
                }
            }
            sleep(100);
        }

        captureDebugSnapshot(page, "omsr-confirmed-status-filter-timeout");
        throw new IllegalStateException("Timed out waiting for OMSR order list after applying the Confirmed status filter.");
    }

    private boolean isPageClosed(Page page) {
        if (page == null) {
            return true;
        }
        try {
            return page.isClosed();
        } catch (PlaywrightException e) {
            return true;
        }
    }

    private void navigateToOrdersUrl(Page page) {
        PlaywrightException lastNavigationError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.navigate(browserConfig.getOrdersUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
                waitForOrdersOrAuthUrl(page, DEFAULT_TIMEOUT_MS);
                return;
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastNavigationError = e;
                log.debug("OMSR orders navigation hit a transient browser error on attempt {}. Current URL: {}",
                        attempt, page.url());
                if (waitForOrdersOrAuthUrl(page, 10_000)) {
                    return;
                }
            }
        }

        captureDebugSnapshot(page, "omsr-orders-navigation-failed");
        if (lastNavigationError != null) {
            throw lastNavigationError;
        }
    }

    private CapturedOrdersPayload.CapturedOrder extractSummary(Locator row, Map<String, Integer> headerIndex) {
        List<Locator> cells = dataCells(row);
        if (isLegacyOrderStatusRow(cells, headerIndex)) {
            return extractLegacyOrderStatusSummary(cells, headerIndex);
        }

        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();

        load.externalOrderId = loadNumberValue(cells, headerIndex);
        load.pickupNumber = rowValue(cells, headerIndex, 1, "pickup #", "pickup number");
        load.customerName = rowValue(cells, headerIndex, 3, "customer name", "customer", "buyer");
        load.poNumbers = splitPoNumberValues(rowValue(cells, headerIndex, 4, "po number", "po no", "purchase order number", "purchase order no"));
        load.palletCount = rowValue(cells, headerIndex, 5, "pallets", "total pallets", "# of pallets");
        load.shipDate = rowValue(cells, headerIndex, 6, "ship date", "shipping date", "shipped date");
        load.deliveryDate = rowValue(cells, headerIndex, 7, "arrival date", "delivery date", "delivered date");
        load.commodity = rowValue(cells, headerIndex, 8, "commodity");
        load.weight = rowValue(cells, headerIndex, 9, "weight", "gross weight", "net weight");
        load.rate = rowValue(cells, headerIndex, -1, "rate");
        load.status = statusValue(cells, headerIndex);

        if (load.externalOrderId == null) {
            load.externalOrderId = cleanedCellValue(text(cells.isEmpty() ? row : cells.get(0)), "load #", "load number", "load id");
        }

        return load;
    }

    private boolean isLegacyOrderStatusRow(List<Locator> cells, Map<String, Integer> headerIndex) {
        if (cells == null || cells.size() < 5) {
            return false;
        }
        if (headerIndex != null && !headerIndex.isEmpty() && !isLegacyOrderStatusHeader(headerIndex)) {
            return false;
        }

        String loadNumber = loadNumberValue(cells, headerIndex);
        String status = firstNonBlank(
                valueForHeader(cells, headerIndex, "status name", "status"),
                cells.size() > 2 ? cleanedCellValue(text(cells.get(2))) : null);
        return loadNumber != null
                && OMSR_LOAD_NUMBER_PATTERN.matcher(loadNumber).matches()
                && looksLikeStatusValue(status);
    }

    private boolean isLegacyOrderStatusHeader(Map<String, Integer> headerIndex) {
        return headerIndex != null
                && findHeaderIndex(headerIndex, "load number") != null
                && findHeaderIndex(headerIndex, "status name") != null
                && findHeaderIndex(headerIndex, "mode") != null
                && findHeaderIndex(headerIndex, "p/u count") != null
                && findHeaderIndex(headerIndex, "pallets") == null
                && findHeaderIndex(headerIndex, "weight") == null;
    }

    private CapturedOrdersPayload.CapturedOrder extractLegacyOrderStatusSummary(
            List<Locator> cells,
            Map<String, Integer> headerIndex
    ) {
        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();
        load.externalOrderId = loadNumberValue(cells, headerIndex);
        load.customerName = rowValue(cells, headerIndex, 1, "buyer", "customer name", "customer");
        load.status = statusValue(cells, headerIndex);
        load.shipDate = rowValue(cells, headerIndex, 3, "ship date");
        load.deliveryDate = rowValue(cells, headerIndex, 4, "arrival date", "delivery date");
        load.rate = rowValue(cells, headerIndex, -1, "rate");
        return load;
    }

    private String loadNumberValue(List<Locator> cells, Map<String, Integer> headerIndex) {
        String byHeader = valueForHeader(cells, headerIndex, "load #", "load number", "load number info", "load id");
        if (looksLikeOmsrLoadNumber(byHeader)) {
            return byHeader;
        }

        if (cells != null && !cells.isEmpty()) {
            String firstCellValue = cleanedCellValue(text(cells.get(0)), "load #", "load number", "load id");
            if (looksLikeOmsrLoadNumber(firstCellValue)) {
                return firstCellValue;
            }
            return firstCellValue;
        }
        return byHeader;
    }

    private boolean looksLikeOmsrLoadNumber(String value) {
        return value != null && OMSR_LOAD_NUMBER_PATTERN.matcher(value.trim()).matches();
    }

    private void clickLoadRowLink(Locator row, Page page) {
        Locator loadDetailsLinks = row.locator(OMSR_LOAD_DETAILS_LINK_SELECTOR);
        if (count(loadDetailsLinks) > 0) {
            clickLoadLink(loadDetailsLinks.first(), page);
            return;
        }

        List<Locator> cells = dataCells(row);
        for (Locator cell : cells) {
            Locator exactLoadLink = cell.locator(OMSR_LOAD_DETAILS_LINK_SELECTOR);
            if (count(exactLoadLink) > 0) {
                clickLoadLink(exactLoadLink.first(), page);
                return;
            }
        }

        if (looksLikeOmsrLoadRow(row)) {
            if (!cells.isEmpty()) {
                Locator firstCellLink = cells.get(0).locator(browserConfig.getLoadLinkSelector());
                if (count(firstCellLink) > 0) {
                    clickLoadLink(firstCellLink.first(), page);
                    return;
                }
            }

            Locator rowLink = row.locator(browserConfig.getLoadLinkSelector());
            if (count(rowLink) > 0) {
                clickLoadLink(rowLink.first(), page);
                return;
            }
        }

        captureDebugSnapshot(page, "omsr-load-link-not-found");
        throw new IllegalStateException("Unable to locate the OMSR load number link in the table row.");
    }

    private void clickLoadLink(Locator link, Page page) {
        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                link.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                return;
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(500);
            }
        }

        captureDebugSnapshot(page, "omsr-load-link-click-failed");
        if (lastError != null) {
            throw lastError;
        }
    }

    private Map<String, String> scrapeDetailAttributes(Frame frame) {
        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Object value = frame.evaluate("""
                        () => {
                          const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                          const attrs = {};
                          const put = (label, content) => {
                            label = normalize(label).replace(/[:*]+$/, "");
                            content = normalize(content);
                            if (!label || !content || label === content || label.length > 80 || content.length > 500) return;
                            attrs[label] = content;
                          };
                          const sectionScope = element => {
                            const container = element.closest(".ant-card, section, article, [role='region']") || element.parentElement;
                            const headings = container ? Array.from(container.querySelectorAll(".ant-card-head-title, h1, h2, h3, h4, h5, h6, [role='heading']")) : [];
                            const heading = headings.map(node => normalize(node.innerText)).find(Boolean);
                            if (!heading) {
                              return "";
                            }

                            const lower = heading.toLowerCase();
                            if (lower.includes("pickup") || lower.includes("origin") || lower.includes("shipper")) {
                              return "pickup";
                            }
                            if (lower.includes("dropoff") || lower.includes("drop off") || lower.includes("delivery") || lower.includes("destination") || lower.includes("receiver")) {
                              return "dropoff";
                            }
                            return "";
                          };

                          document.querySelectorAll(".ant-descriptions-item").forEach(item => {
                            put(item.querySelector(".ant-descriptions-item-label")?.innerText, item.querySelector(".ant-descriptions-item-content")?.innerText);
                          });

                          document.querySelectorAll("[class*='textWithLabel' i]").forEach(block => {
                            const children = Array.from(block.children)
                              .map(child => normalize(child.innerText))
                              .filter(Boolean);
                            if (children.length < 2) {
                              return;
                            }

                            const label = children[0];
                            const content = children[1];
                            const scope = sectionScope(block);
                            put(scope ? `${scope} ${label}` : label, content);
                          });

                          document.querySelectorAll("dl").forEach(dl => {
                            Array.from(dl.querySelectorAll("dt")).forEach(dt => put(dt.innerText, dt.nextElementSibling?.innerText));
                          });

                          document.querySelectorAll("label").forEach(label => {
                            const inputId = label.getAttribute("for");
                            const input = inputId ? document.getElementById(inputId) : label.parentElement?.querySelector("input, textarea, [role='combobox']");
                            put(label.innerText, input?.value || input?.innerText);
                          });

                          document.querySelectorAll("tr").forEach(row => {
                            const cells = Array.from(row.querySelectorAll("th, td"));
                            if (cells.length === 2) put(cells[0].innerText, cells[1].innerText);
                          });

                          document.querySelectorAll("[data-testid], [aria-label]").forEach(el => {
                            put(el.getAttribute("data-testid") || el.getAttribute("aria-label"), el.innerText || el.value);
                          });

                          document.querySelectorAll("[class*='address' i], [data-testid*='address' i], [aria-label*='address' i]").forEach(el => {
                            const label = el.closest("section, article, .ant-card, .ant-descriptions, [class*='stop' i], [class*='pickup' i], [class*='drop' i]")?.innerText || "";
                            put(label.includes("Pickup") || label.includes("Origin") ? "pickup address" : "delivery address", el.innerText || el.value);
                          });

                          return attrs;
                        }
                        """);

                Map<String, String> result = new LinkedHashMap<>();
                if (value instanceof Map<?, ?> map) {
                    map.forEach((key, mapValue) -> {
                        if (key != null && mapValue != null) {
                            result.put(String.valueOf(key), String.valueOf(mapValue));
                        }
                    });
                }
                return result;
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(100L * attempt);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return new LinkedHashMap<>();
    }

    private void enrichLoadFromDetailAttributes(CapturedOrdersPayload.CapturedOrder load, Map<String, String> attributes, Frame frame) {
        load.externalOrderId = firstNonBlank(load.externalOrderId, valueFor(attributes, "load #", "load number", "load id"));
        load.pickupNumber = firstNonBlank(load.pickupNumber, valueFor(attributes, "pickup #", "pickup number"));
        load.customerName = firstNonBlank(load.customerName, valueFor(attributes, "customer name", "customer"));
        load.shipDate = firstNonBlank(load.shipDate, valueFor(attributes, "ship date", "shipping date", "shipped date"));
        load.deliveryDate = firstNonBlank(load.deliveryDate, valueFor(attributes, "delivery date", "delivered date"));
        load.commodity = firstNonBlank(load.commodity, valueFor(attributes, "commodity"));
        load.rate = firstNonBlank(load.rate, freightNumericValue(valueFor(attributes, "rate", "freight rate", "load rate", "line haul rate", "line haul", "total rate", "carrier rate")));
        String bodyText = frameBodyText(frame);
        applyShippingTotals(load, attributes, bodyText);
        load.palletCount = firstNonBlank(load.palletCount, valueFor(attributes, "pallets", "total pallets", "# of pallets"));
        load.weight = firstNonBlank(load.weight, valueFor(attributes, "weight", "gross weight", "net weight"));
        load.status = firstNonBlank(load.status, valueFor(attributes, "status"));

        // OMSR can render PO Number as plain text instead of a structured field.
        List<String> poNumbers = new ArrayList<>();
        addUniquePoNumbers(poNumbers, load.poNumbers);
        addUniquePoNumbers(poNumbers, poNumbersFromAttributes(attributes));
        addUniquePoNumbers(poNumbers, poNumbersFromText(bodyText));
        if (!poNumbers.isEmpty()) {
            load.poNumbers = poNumbers;
        }

        load.pickup = buildStop(attributes, true);
        load.dropoff = buildStop(attributes, false);

        applyPopupCapture(load.pickup, captureAddressPopup(frame, "Shipping", "SW/H", "Pickup", "Origin", "Shipper", "Ship From"), true);
        applyPopupCapture(load.dropoff, captureAddressPopup(frame, "Receiving", "RW/H", "SW/R", "Dropoff", "Drop Off", "Delivery", "Destination", "Receiver"), false);

        fillAddressFromBodyText(bodyText, load);
    }

    private void applyShippingTotals(
            CapturedOrdersPayload.CapturedOrder load,
            Map<String, String> attributes,
            String bodyText
    ) {
        if (load == null) {
            return;
        }

        ShippingTotals totals = shippingTotals(attributes, bodyText);
        load.totalQuantity = firstNonBlank(freightNumericValue(totals.totalQuantity()), freightNumericValue(load.totalQuantity));
        load.caseCount = firstNonBlank(
                freightNumericValue(totals.caseCount()),
                freightNumericValue(load.caseCount),
                load.totalQuantity);
        load.palletCount = firstNonBlank(freightNumericValue(totals.pallets()), freightNumericValue(load.palletCount));
        load.cubeCount = firstNonBlank(freightNumericValue(totals.cubes()), freightNumericValue(load.cubeCount));
        load.weight = firstNonBlank(freightNumericValue(totals.weight()), freightNumericValue(load.weight));
    }

    private ShippingTotals shippingTotals(Map<String, String> attributes, String bodyText) {
        ShippingTotals fromText = shippingTotalsFromText(bodyText);
        return new ShippingTotals(
                firstNonBlank(
                        fromText.totalQuantity(),
                        valueFor(attributes, "shipping total quantity", "total quantity", "total qty")),
                firstNonBlank(
                        fromText.caseCount(),
                        valueFor(attributes, "shipping case count", "case count", "total cases", "total # of cases", "# of cases", "cases")),
                firstNonBlank(
                        fromText.pallets(),
                        valueFor(attributes, "shipping pallet count", "pallet count", "shipping pallets", "total pallets", "total # of pallets", "pallets", "# of pallets")),
                firstNonBlank(
                        fromText.cubes(),
                        valueFor(attributes, "shipping cubes", "cubes", "cube")),
                firstNonBlank(
                        fromText.weight(),
                        valueFor(attributes, "shipping weight", "weight", "gross weight", "net weight", "shipped weight", "shipped gross weight"))
        );
    }

    private ShippingTotals shippingTotalsFromText(String text) {
        if (text == null || text.isBlank()) {
            return new ShippingTotals(null, null, null, null, null);
        }

        List<String> lines = Arrays.stream(text.split("\\R"))
                .map(this::normalizeWhitespace)
                .filter(line -> line != null && !line.isBlank())
                .toList();
        int shippingStart = shippingSectionStart(lines);
        if (shippingStart < 0) {
            return new ShippingTotals(null, null, null, null, null);
        }

        Map<String, String> values = new LinkedHashMap<>();
        String currentLabel = null;
        String pendingValue = null;
        for (int i = shippingStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isShippingSectionBoundary(line)) {
                break;
            }

            for (ShippingToken token : shippingTokens(line)) {
                if (token.value() != null) {
                    if (currentLabel != null && !values.containsKey(currentLabel)) {
                        values.put(currentLabel, token.value());
                        currentLabel = null;
                        pendingValue = null;
                    } else {
                        pendingValue = token.value();
                    }
                    continue;
                }

                if (token.label() == null) {
                    continue;
                }

                if (pendingValue != null && currentLabel == null && !values.containsKey(token.label())) {
                    if ("totalQuantity".equals(token.label()) && isSmallWholeNumber(pendingValue)) {
                        currentLabel = token.label();
                    } else {
                        values.put(token.label(), pendingValue);
                    }
                    pendingValue = null;
                } else {
                    currentLabel = token.label();
                    pendingValue = null;
                }
            }
        }

        return realignShippingTotals(new ShippingTotals(
                values.get("totalQuantity"),
                values.get("caseCount"),
                values.get("pallets"),
                values.get("cubes"),
                values.get("weight")
        ));
    }

    private int shippingSectionStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String key = normalizeKey(lines.get(i));
            if (key.equals("shipping") || key.equals("shippingdetails") || key.equals("shippinginformation")) {
                return i;
            }
        }
        return -1;
    }

    private boolean isShippingSectionBoundary(String line) {
        String key = normalizeKey(line);
        return key.equals("receiving")
                || key.equals("receivingdetails")
                || key.equals("receivinginformation")
                || key.equals("receiver")
                || key.equals("dropoff")
                || key.equals("dropoffdetails")
                || key.equals("delivery")
                || key.equals("destination")
                || key.equals("billing")
                || key.equals("carrier")
                || key.equals("documents")
                || key.equals("notes")
                || key.equals("orderdetails")
                || key.equals("loadinformation");
    }

    private List<ShippingToken> shippingTokens(String line) {
        String normalized = normalizeWhitespace(line);
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }

        List<ShippingToken> tokens = embeddedShippingTokens(normalized);
        if (!tokens.isEmpty()) {
            return tokens;
        }

        String label = shippingTotalLabel(normalized);
        if (label != null) {
            return List.of(new ShippingToken(label, null));
        }

        if (looksLikeNumericValue(normalized)) {
            return List.of(new ShippingToken(null, normalizeNumericValue(normalized)));
        }

        return List.of();
    }

    private String shippingTotalLabel(String value) {
        String key = normalizeKey(value);
        if (key.equals("totalquantity") || key.equals("totalqty") || key.equals("quantity")) {
            return "totalQuantity";
        }
        if (key.equals("case")
                || key.equals("cases")
                || key.equals("casecount")
                || key.equals("totalcases")
                || key.equals("totalofcases")
                || key.equals("totalnumberofcases")
                || key.equals("ofcases")
                || key.equals("numberofcases")
                || key.equals("noofcases")
                || key.equals("shippedcases")
                || key.equals("shpdqty")) {
            return "caseCount";
        }
        if (key.equals("pallet")
                || key.equals("pallets")
                || key.equals("palletcount")
                || key.equals("totalpallets")
                || key.equals("totalofpallets")
                || key.equals("totalnumberofpallets")
                || key.equals("ofpallets")
                || key.equals("numberofpallets")
                || key.equals("noofpallets")) {
            return "pallets";
        }
        if (key.equals("cube") || key.equals("cubes")) {
            return "cubes";
        }
        if (key.equals("weight")
                || key.equals("grossweight")
                || key.equals("netweight")
                || key.equals("shippedweight")
                || key.equals("shippedgrossweight")
                || key.equals("shpdweight")
                || key.equals("shpdgwt")
                || key.equals("grossshipweight")) {
            return "weight";
        }
        return null;
    }

    private List<ShippingToken> embeddedShippingTokens(String line) {
        Matcher matcher = Pattern.compile(
                "(?i)(total\\s*(?:quantity|qty)|case\\s*count|total\\s*#?\\s*of\\s*cases|#\\s*of\\s*cases|no\\.?\\s*of\\s*cases|shipped\\s*cases|shpd\\s*qty|cases?|pallet\\s*count|total\\s*#?\\s*of\\s*pallets|#\\s*of\\s*pallets|no\\.?\\s*of\\s*pallets|total\\s*pallets|pallets?|cubes?|gross\\s*ship\\s*weight|gross\\s*weight|net\\s*weight|shipped\\s*(?:gross\\s*)?weight|shpd\\s*(?:weight|gwt)|weight|"
                        + NUMERIC_VALUE_PATTERN
                        + ")"
        ).matcher(line);

        List<ShippingToken> tokens = new ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1);
            String label = shippingTotalLabel(value);
            if (label != null) {
                tokens.add(new ShippingToken(label, null));
            } else if (looksLikeNumericValue(value)) {
                tokens.add(new ShippingToken(null, normalizeNumericValue(value)));
            }
        }
        boolean hasLabel = tokens.stream().anyMatch(token -> token.label() != null);
        return tokens.size() > 1 || (!hasLabel && tokens.size() == 1) ? tokens : List.of();
    }

    private boolean looksLikeNumericValue(String value) {
        return value != null && value.trim().matches(NUMERIC_VALUE_PATTERN);
    }

    private String normalizeNumericValue(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized == null || !normalized.matches(NUMERIC_VALUE_PATTERN)) {
            return null;
        }
        return normalized.replace(",", "");
    }

    private String freightNumericValue(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }

        Matcher matcher = Pattern.compile(NUMERIC_VALUE_PATTERN).matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().replace(",", "");
    }

    private ShippingTotals realignShippingTotals(ShippingTotals totals) {
        if (totals == null) {
            return new ShippingTotals(null, null, null, null, null);
        }

        if (totals.totalQuantity() == null
                && isImplausiblePalletCount(totals.pallets())
                && totals.cubes() != null
                && totals.weight() != null) {
            return new ShippingTotals(totals.pallets(), totals.caseCount(), totals.cubes(), totals.weight(), null);
        }

        return totals;
    }

    private boolean isImplausiblePalletCount(String value) {
        BigDecimal decimal = numericDecimal(value);
        return decimal != null && decimal.compareTo(IMPLAUSIBLE_PALLET_COUNT_THRESHOLD) > 0;
    }

    private boolean isSmallWholeNumber(String value) {
        BigDecimal decimal = numericDecimal(value);
        return decimal != null
                && decimal.scale() <= 0
                && decimal.signum() > 0
                && decimal.compareTo(BigDecimal.TEN) <= 0;
    }

    private BigDecimal numericDecimal(String value) {
        String normalized = normalizeNumericValue(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }

        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private CapturedOrdersPayload.Stop buildStop(Map<String, String> attributes, boolean pickup) {
        CapturedOrdersPayload.Stop stop = new CapturedOrdersPayload.Stop();
        String id = pickup
                ? firstNonBlank(
                        valueFor(attributes, "origin id", "pickup id", "shipper id"),
                        valueFor(attributes, "load id"))
                : firstNonBlank(
                        valueFor(attributes, "destination id", "delivery id", "dropoff id", "receiver id"),
                        valueFor(attributes, "load id"));

        String sectionValue = pickup
                ? firstNonBlank(
                        valueFor(attributes, "origin address", "pickup address", "shipper address", "origin", "pickup", "ship from", "sw/h", "swh"),
                        valueFor(attributes, "pickup location"))
                : firstNonBlank(
                        valueFor(attributes, "delivery address", "destination address", "receiver address", "delivery", "dropoff", "drop off", "rw/h", "rwh", "sw/r"),
                        valueFor(attributes, "delivery location"));

        stop.addressId = id;
        stop.location = sectionValue;
        stop.streetAddress = sectionValue;

        AddressParts parts = parseAddress(sectionValue);
        if (parts != null) {
            stop.streetAddress = firstNonBlank(stop.streetAddress, parts.street);
            stop.city = firstNonBlank(stop.city, parts.city);
            stop.state = firstNonBlank(stop.state, parts.state);
            stop.zip = firstNonBlank(stop.zip, parts.zip);
        }

        stop.city = firstNonBlank(stop.city, pickup
                ? valueFor(attributes, "pickup city", "origin city")
                : valueFor(attributes, "delivery city", "destination city"));
        stop.state = firstNonBlank(stop.state, pickup
                ? valueFor(attributes, "pickup state", "origin state")
                : valueFor(attributes, "delivery state", "destination state"));
        stop.zip = firstNonBlank(stop.zip, pickup
                ? valueFor(attributes, "pickup zip", "origin zip", "pickup zipcode")
                : valueFor(attributes, "delivery zip", "destination zip", "delivery zipcode"));

        stop.dateTime = pickup
                ? valueFor(attributes, "pickup appointment", "appointment #", "pickup date time", "ship date")
                : valueFor(attributes, "delivery appointment", "appointment #", "delivery date time", "delivery date");

        return stop;
    }

    private PopupCapture captureAddressPopup(Frame frame, String... displayTexts) {
        if (frame == null || displayTexts == null || displayTexts.length == 0) {
            return null;
        }

        Page page = frame.page();
        for (String displayText : displayTexts) {
            if (displayText == null || displayText.isBlank()) {
                continue;
            }

            PopupCapture capture = captureAddressPopup(page, frame, displayText);
            if (capture != null) {
                return capture;
            }
        }

        return null;
    }

    private PopupCapture captureAddressPopup(Page page, Frame sourceFrame, String displayText) {
        if (page == null || sourceFrame == null || displayText == null || displayText.isBlank()) {
            return null;
        }

        boolean sectionTarget = isSectionAddressTarget(displayText);
        PopupTarget popupTarget = sectionTarget ? null : resolvePopupTarget(sourceFrame, displayText);

        try {
            Page popupPage = page.waitForPopup(
                    new Page.WaitForPopupOptions().setTimeout(ADDRESS_POPUP_TIMEOUT_MS),
                    () -> {
                        if (sectionTarget) {
                            clickSectionAddressField(sourceFrame, displayText);
                        } else {
                            clickAddressFieldBelowLabel(sourceFrame, displayText);
                        }
                    });
            if (popupPage != null) {
                popupPage.waitForLoadState();
                PopupCapture popupCapture = capturePopupContent(bestEffortPopupFrame(popupPage));
                if (popupCapture != null) {
                    return popupCapture;
                }
            }
        } catch (PlaywrightException e) {
            if (!isTimeoutError(e)) {
                throw e;
            }
        }

        if (popupTarget != null) {
            PopupCapture directCapture = capturePopup(page, sourceFrame, popupTarget);
            if (directCapture != null) {
                return directCapture;
            }
        }

        PopupCapture popupCapture = waitForPopupContent(page, sourceFrame);
        if (popupCapture != null) {
            return popupCapture;
        }

        return null;
    }

    private PopupTarget resolvePopupTarget(Frame frame, String displayText) {
        if (frame == null || displayText == null || displayText.isBlank()) {
            return null;
        }

        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Object value = frame.evaluate("""
                        async (displayText) => {
                          const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                          const normalizeKey = value => normalize(value).toLowerCase().replace(/[^a-z0-9]/g, "");
                          const isTextVisible = el => {
                            if (!el) return false;
                            const style = window.getComputedStyle(el);
                            if (!style || style.display === "none" || style.visibility === "hidden") {
                              return false;
                            }
                            const rect = el.getBoundingClientRect();
                            return rect.width > 0 && rect.height > 0;
                          };
                          const isVisible = el => {
                            if (!el) return false;
                            const style = window.getComputedStyle(el);
                            if (!style || style.display === "none" || style.visibility === "hidden" || style.pointerEvents === "none") {
                              return false;
                            }
                            const rect = el.getBoundingClientRect();
                            return rect.width > 0 && rect.height > 0;
                          };
                          const clickableSelector = "a, button, [role='button'], [role='link'], [onclick], [tabindex]:not([tabindex='-1']), input, select, textarea";
                          const isPopupLink = anchor => {
                            if (!anchor) {
                              return false;
                            }
                            const href = normalize(anchor.getAttribute("href") || "");
                            const onclick = normalize(anchor.getAttribute("onclick") || "");
                            return /setPopupdiv\\(/i.test(href)
                              || /setPopupdiv\\(/i.test(onclick)
                              || /popup_[a-z_]+\\.cfm\\?/i.test(href)
                              || /popup_[a-z_]+\\.cfm\\?/i.test(onclick);
                          };
                          const findPopupLink = root => {
                            if (!root) {
                              return null;
                            }

                            const links = [];
                            if (root.matches && root.matches(clickableSelector)) {
                              links.push(root);
                            }
                            if (root.querySelectorAll) {
                              links.push(...Array.from(root.querySelectorAll(clickableSelector)));
                            }

                            return links
                              .filter(isVisible)
                              .filter(isPopupLink)
                              .sort((a, b) => {
                                const rectA = a.getBoundingClientRect();
                                const rectB = b.getBoundingClientRect();
                                return rectA.top - rectB.top || rectA.left - rectB.left;
                              })[0] || null;
                          };

                          const target = normalize(displayText).toLowerCase();
                          const labelCandidates = Array.from(document.querySelectorAll("body *"))
                            .filter(el => isTextVisible(el))
                            .filter(el => {
                              const text = normalize(el.innerText).toLowerCase();
                              const textKey = normalizeKey(text);
                              const targetKey = normalizeKey(target);
                              return text === target
                                || text.startsWith(target + ":")
                                || text.startsWith(target + " ")
                                || text.startsWith(target + "-")
                                || textKey === targetKey
                                || textKey.startsWith(targetKey);
                            })
                            .sort((a, b) => normalize(a.innerText).length - normalize(b.innerText).length);

                          const labelEl = labelCandidates[0];
                          if (!labelEl) {
                            return "";
                          }

                          const rect = labelEl.getBoundingClientRect();
                          const containers = [
                            labelEl.closest("tr, [role='row'], .ant-descriptions-item, dl, .ant-card, section, article, .ant-space, .ant-row, .ant-col"),
                            labelEl.parentElement,
                            labelEl.parentElement?.parentElement
                          ].filter(Boolean);

                          for (const container of containers) {
                            const candidate = findPopupLink(container);
                            if (candidate) {
                              return candidate.getAttribute("href") || candidate.getAttribute("onclick") || "";
                            }
                          }

                          const anchors = Array.from(document.querySelectorAll("a"))
                            .filter(isVisible)
                            .filter(isPopupLink)
                            .sort((a, b) => {
                              const rectA = a.getBoundingClientRect();
                              const rectB = b.getBoundingClientRect();
                              const aBelow = rectA.top >= rect.bottom - 2 ? 1 : 0;
                              const bBelow = rectB.top >= rect.bottom - 2 ? 1 : 0;
                              return bBelow - aBelow || rectA.top - rectB.top || rectA.left - rectB.left;
                            });

                          const candidate = anchors[0];
                          return candidate ? (candidate.getAttribute("href") || candidate.getAttribute("onclick") || "") : "";
                        }
                        """, displayText);

                PopupTarget popupTarget = parsePopupTarget(value == null ? null : String.valueOf(value));
                if (popupTarget != null) {
                    return popupTarget;
                }
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(100L * attempt);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private PopupCapture capturePopup(Page page, Frame sourceFrame, PopupTarget popupTarget) {
        if (page == null || sourceFrame == null || popupTarget == null) {
            return null;
        }

        Page popupPage = null;
        try {
            popupPage = page.context().newPage();
            popupPage.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
            popupPage.setDefaultNavigationTimeout(DEFAULT_TIMEOUT_MS);
            String popupUrl = resolvePopupUrl(sourceFrame.url(), popupTarget);
            if (popupUrl == null || popupUrl.isBlank()) {
                return null;
            }
            popupPage.navigate(popupUrl,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
            popupPage.waitForLoadState();

            PopupCapture popupCapture = capturePopupContent(bestEffortPopupFrame(popupPage));
            if (popupCapture != null) {
                return popupCapture;
            }

            return capturePopupContent(popupPage.mainFrame());
        } catch (PlaywrightException e) {
            if (!isTransientNavigationError(e) && !isTimeoutError(e)) {
                throw e;
            }
            return null;
        } finally {
            if (popupPage != null) {
                try {
                    popupPage.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup.
                }
            }
        }
    }

    private String resolvePopupUrl(String baseUrl, PopupTarget popupTarget) {
        if (popupTarget == null) {
            return null;
        }

        String relativeUrl = popupTarget.url;
        if (relativeUrl == null || relativeUrl.isBlank()) {
            relativeUrl = resolvePopupRelativeUrl(popupTarget.type, popupTarget.id);
        }
        if (relativeUrl == null || relativeUrl.isBlank()) {
            return null;
        }

        try {
            if (baseUrl != null && !baseUrl.isBlank()) {
                return URI.create(baseUrl).resolve(relativeUrl).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the relative URL.
        }
        return relativeUrl;
    }

    private String resolvePopupRelativeUrl(String type, String id) {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            return null;
        }

        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        String normalizedId = id.trim();
        return switch (normalizedType) {
            case "company" -> "../common/popups/popup_company_details.cfm?mcid=" + normalizedId;
            case "location" -> "../common/popups/popup_location_details.cfm?locid=" + normalizedId;
            case "member" -> "../common/popups/popup_member_details.cfm?memberid=" + normalizedId;
            case "poaddress" -> "../common/popups/popup_po_address_details.cfm?poid=" + normalizedId;
            case "invoiceaddress" -> "../common/popups/popup_po_address_details.cfm?invoiceid=" + normalizedId;
            case "assignedbrokers" -> "../common/popups/popup_assigned_brokers.cfm?distributorID=" + normalizedId;
            default -> null;
        };
    }

    private PopupTarget parsePopupTarget(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String normalized = normalizeWhitespace(rawValue);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }

        Matcher setPopupdivMatcher = Pattern.compile(
                "setPopupdiv\\(\\s*['\"](?<type>[^'\"]+)['\"]\\s*,\\s*['\"]?(?<id>[^'\")\\s]+)['\"]?",
                Pattern.CASE_INSENSITIVE
        ).matcher(normalized);
        if (setPopupdivMatcher.find()) {
            String type = setPopupdivMatcher.group("type");
            String id = setPopupdivMatcher.group("id");
            return new PopupTarget(type, id, null);
        }

        for (PopupPattern pattern : List.of(
                new PopupPattern("Company", Pattern.compile("popup_company_details\\.cfm\\?mcid=([0-9]+)", Pattern.CASE_INSENSITIVE)),
                new PopupPattern("Location", Pattern.compile("popup_location_details\\.cfm\\?locid=([0-9]+)", Pattern.CASE_INSENSITIVE)),
                new PopupPattern("Member", Pattern.compile("popup_member_details\\.cfm\\?memberid=([0-9]+)", Pattern.CASE_INSENSITIVE)),
                new PopupPattern("PoAddress", Pattern.compile("popup_po_address_details\\.cfm\\?poid=([0-9]+)", Pattern.CASE_INSENSITIVE)),
                new PopupPattern("InvoiceAddress", Pattern.compile("popup_po_address_details\\.cfm\\?invoiceid=([0-9]+)", Pattern.CASE_INSENSITIVE)),
                new PopupPattern("AssignedBrokers", Pattern.compile("popup_assigned_brokers\\.cfm\\?distributorID=([0-9]+)", Pattern.CASE_INSENSITIVE))
        )) {
            Matcher matcher = pattern.pattern.matcher(normalized);
            if (matcher.find()) {
                return new PopupTarget(pattern.type, matcher.group(1), null);
            }
        }

        return null;
    }

    private PopupCapture waitForPopupContent(Page page, Frame sourceFrame) {
        long deadline = System.nanoTime() + (long) (ADDRESS_POPUP_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            Frame popupFrame = findPopupFrame(page, sourceFrame);
            if (popupFrame != null) {
                PopupCapture popupCapture = capturePopupContent(popupFrame);
                if (popupCapture != null) {
                    return popupCapture;
                }
            }

            if (hasPopupOverlay(page)) {
                PopupCapture parentCapture = capturePopupContent(page.mainFrame());
                if (parentCapture != null) {
                    return parentCapture;
                }
            }

            sleep(100);
        }

        return null;
    }

    private Frame findPopupFrame(Page page, Frame sourceFrame) {
        if (page == null) {
            return null;
        }

        for (Frame candidate : page.frames()) {
            if (candidate == null || candidate == sourceFrame) {
                continue;
            }

            if (isPopupFrameUrl(candidate.url())) {
                return candidate;
            }
        }

        return null;
    }

    private boolean isPopupFrameUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String normalizedUrl = url.toLowerCase(Locale.ROOT);
        return normalizedUrl.contains("popup_")
                || normalizedUrl.contains("popup")
                || normalizedUrl.contains("location_details.cfm")
                || normalizedUrl.contains("po_address_details.cfm");
    }

    private boolean hasPopupOverlay(Page page) {
        if (page == null) {
            return false;
        }

        try {
            Object value = page.evaluate("""
                    () => {
                      const isVisible = el => {
                        if (!el) return false;
                        const style = window.getComputedStyle(el);
                        if (!style || style.display === "none" || style.visibility === "hidden") {
                          return false;
                        }
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                      };

                      const selectors = [
                        "#ModalContainerDiv",
                        "#ContainerDiv",
                        "#modalPoP",
                        ".ui-dialog-content",
                        ".ui-dialog",
                        "[role='dialog']",
                        "#PopupMask"
                      ];

                      for (const selector of selectors) {
                        const nodes = Array.from(document.querySelectorAll(selector));
                        if (nodes.some(isVisible)) {
                          return true;
                        }
                      }

                      return false;
                    }
                    """);
            return Boolean.TRUE.equals(value);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private Frame bestEffortPopupFrame(Page popupPage) {
        if (popupPage == null) {
            return null;
        }

        Frame mainFrame = popupPage.mainFrame();
        if (isUsefulPopupFrame(mainFrame)) {
            return mainFrame;
        }

        for (Frame frame : popupPage.frames()) {
            if (frame != null && frame != mainFrame && isUsefulPopupFrame(frame)) {
                return frame;
            }
        }

        return mainFrame;
    }

    private boolean isUsefulPopupFrame(Frame frame) {
        if (frame == null) {
            return false;
        }

        if (isPopupFrameUrl(frame.url())) {
            return true;
        }

        String text = frameBodyText(frame);
        return text != null && !text.isBlank();
    }

    private PopupCapture capturePopupContent(Frame frame) {
        if (frame == null) {
            return null;
        }

        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Map<String, String> attributes = scrapeDetailAttributes(frame);
                String text = frameBodyText(frame);
                if ((attributes != null && !attributes.isEmpty()) || (text != null && !text.isBlank())) {
                    return new PopupCapture(text, attributes == null ? new LinkedHashMap<>() : attributes);
                }
                sleep(100L * attempt);
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(100L * attempt);
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        return null;
    }

    private boolean isTimeoutError(PlaywrightException e) {
        String message = e == null ? null : e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("timeout");
    }

    private boolean isSectionAddressTarget(String displayText) {
        if (displayText == null || displayText.isBlank()) {
            return false;
        }

        String normalized = displayText.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return normalized.equals("shipping") || normalized.equals("receiving");
    }

    private void clickAddressFieldBelowLabel(Frame frame, String displayText) {
        try {
            Object clicked = frame.evaluate("""
                    async (displayText) => {
                      const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                      const normalizeKey = value => normalize(value).toLowerCase().replace(/[^a-z0-9]/g, "");
                      const isTextVisible = el => {
                        if (!el) return false;
                        const style = window.getComputedStyle(el);
                        if (!style || style.display === "none" || style.visibility === "hidden") {
                          return false;
                        }
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                      };
                      const isVisible = el => {
                        if (!el) return false;
                        const style = window.getComputedStyle(el);
                        if (!style || style.display === "none" || style.visibility === "hidden" || style.pointerEvents === "none") {
                          return false;
                        }
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                      };
                      const clickableSelector = "a, button, [role='button'], [role='link'], [onclick], [tabindex]:not([tabindex='-1']), input, select, textarea";
                      const clickableAncestor = element => {
                        let current = element;
                        while (current && current !== document.body) {
                          if (current.matches && current.matches(clickableSelector) && isVisible(current)) {
                            return current;
                          }
                          current = current.parentElement;
                        }
                        return null;
                      };
                      const isLocationPopupLink = anchor => {
                        if (!anchor) {
                          return false;
                        }
                        const href = normalize(anchor.getAttribute("href") || "");
                        const onclick = normalize(anchor.getAttribute("onclick") || "");
                        return /setPopupdiv\\(/i.test(href)
                          || /setPopupdiv\\(/i.test(onclick)
                          || /popup_[a-z_]+\\.cfm\\?/i.test(href)
                          || /popup_[a-z_]+\\.cfm\\?/i.test(onclick);
                      };
                      const findLocationPopupLink = root => {
                        if (!root) {
                          return null;
                        }

                        const links = [];
                        if (root.matches && root.matches(clickableSelector)) {
                          links.push(root);
                        }
                        if (root.querySelectorAll) {
                          links.push(...Array.from(root.querySelectorAll(clickableSelector)));
                        }

                        return links
                          .filter(isVisible)
                          .filter(isLocationPopupLink)
                          .sort((a, b) => {
                            const rectA = a.getBoundingClientRect();
                            const rectB = b.getBoundingClientRect();
                            return rectA.top - rectB.top || rectA.left - rectB.left;
                          })[0] || null;
                      };

                      const target = normalize(displayText).toLowerCase();
                      const labelCandidates = Array.from(document.querySelectorAll("body *"))
                        .filter(el => isTextVisible(el))
                        .filter(el => {
                          const text = normalize(el.innerText).toLowerCase();
                          const textKey = normalizeKey(text);
                          const targetKey = normalizeKey(target);
                          return text === target
                            || text.startsWith(target + ":")
                            || text.startsWith(target + " ")
                            || text.startsWith(target + "-")
                            || textKey === targetKey
                            || textKey.startsWith(targetKey);
                        })
                        .sort((a, b) => normalize(a.innerText).length - normalize(b.innerText).length);

                      const labelEl = labelCandidates[0];
                      if (!labelEl) {
                        return false;
                      }

                      labelEl.scrollIntoView({ block: "center", inline: "center" });
                      await new Promise(resolve => setTimeout(resolve, 100));

                      const rect = labelEl.getBoundingClientRect();
                      const containers = [
                        labelEl.closest("tr, [role='row'], .ant-descriptions-item, dl, .ant-card, section, article, .ant-space, .ant-row, .ant-col"),
                        labelEl.parentElement,
                        labelEl.parentElement?.parentElement
                      ].filter(Boolean);

                      for (const container of containers) {
                        const candidate = findLocationPopupLink(container);
                        if (candidate) {
                          candidate.scrollIntoView({ block: "center", inline: "center" });
                          try {
                            candidate.click();
                          } catch (error) {
                            candidate.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
                          }
                          return true;
                        }
                      }

                      const points = [
                        [rect.left + Math.min(rect.width / 2, 30), rect.bottom + 4],
                        [rect.left + Math.min(rect.width / 2, 30), rect.bottom + 14],
                        [rect.left + Math.min(rect.width / 2, 30), rect.bottom + 26],
                        [rect.right - 5, rect.bottom + 8],
                        [rect.left + 5, rect.bottom + 8]
                      ];

                      const pickClickable = element => {
                        const clickable = clickableAncestor(element);
                        if (clickable && clickable !== labelEl) {
                          return clickable;
                        }
                        return null;
                      };

                      for (const [x, y] of points) {
                        const hit = document.elementFromPoint(x, y);
                        const candidate = pickClickable(hit);
                        if (candidate) {
                          candidate.scrollIntoView({ block: "center", inline: "center" });
                          try {
                            candidate.click();
                          } catch (error) {
                            candidate.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
                          }
                          return true;
                        }
                      }

                      for (const container of containers) {
                        const descendants = Array.from(container.querySelectorAll(clickableSelector))
                          .filter(isVisible)
                          .filter(el => el !== labelEl);
                        descendants.sort((a, b) => {
                          const rectA = a.getBoundingClientRect();
                          const rectB = b.getBoundingClientRect();
                          return rectA.top - rectB.top || rectA.left - rectB.left;
                        });

                        const below = descendants.find(el => el.getBoundingClientRect().top >= rect.bottom - 2);
                        const candidate = below || descendants[0] || null;
                        if (candidate) {
                          candidate.scrollIntoView({ block: "center", inline: "center" });
                          try {
                            candidate.click();
                          } catch (error) {
                            candidate.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
                          }
                          return true;
                        }
                      }

                      return false;
                    }
                    """, displayText);

            if (!Boolean.TRUE.equals(clicked)) {
                log.debug("OMSR address field below label {} could not be located.", displayText);
            }
        } catch (PlaywrightException e) {
            if (!isTransientNavigationError(e)) {
                throw e;
            }
        }
    }

    private void clickSectionAddressField(Frame frame, String sectionText) {
        try {
            Object clicked = frame.evaluate("""
                    async (sectionText) => {
                      const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                      const normalizeKey = value => normalize(value).toLowerCase().replace(/[^a-z0-9]/g, "");
                      const isTextVisible = el => {
                        if (!el) return false;
                        const style = window.getComputedStyle(el);
                        if (!style || style.display === "none" || style.visibility === "hidden") {
                          return false;
                        }
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                      };
                      const isVisible = el => {
                        if (!el) return false;
                        const style = window.getComputedStyle(el);
                        if (!style || style.display === "none" || style.visibility === "hidden" || style.pointerEvents === "none") {
                          return false;
                        }
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                      };
                      const clickableSelector = "a, button, [role='button'], [role='link'], [onclick], [tabindex]:not([tabindex='-1']), input, select, textarea";
                      const isLocationPopupLink = anchor => {
                        if (!anchor) {
                          return false;
                        }
                        const href = normalize(anchor.getAttribute("href") || "");
                        const onclick = normalize(anchor.getAttribute("onclick") || "");
                        return /setPopupdiv\\(/i.test(href)
                          || /setPopupdiv\\(/i.test(onclick)
                          || /popup_[a-z_]+\\.cfm\\?/i.test(href)
                          || /popup_[a-z_]+\\.cfm\\?/i.test(onclick);
                      };
                      const findLocationPopupLink = root => {
                        if (!root) {
                          return null;
                        }

                        const links = [];
                        if (root.matches && root.matches(clickableSelector)) {
                          links.push(root);
                        }
                        if (root.querySelectorAll) {
                          links.push(...Array.from(root.querySelectorAll(clickableSelector)));
                        }

                        return links
                          .filter(isVisible)
                          .filter(isLocationPopupLink)
                          .sort((a, b) => {
                            const rectA = a.getBoundingClientRect();
                            const rectB = b.getBoundingClientRect();
                            return rectA.top - rectB.top || rectA.left - rectB.left;
                          })[0] || null;
                      };
                      const findClickableLink = root => {
                        if (!root) {
                          return null;
                        }

                        const links = [];
                        if (root.matches && root.matches(clickableSelector)) {
                          links.push(root);
                        }
                        if (root.querySelectorAll) {
                          links.push(...Array.from(root.querySelectorAll(clickableSelector)));
                        }

                        return links
                          .filter(isVisible)
                          .sort((a, b) => {
                            const rectA = a.getBoundingClientRect();
                            const rectB = b.getBoundingClientRect();
                            return rectA.top - rectB.top || rectA.left - rectB.left;
                          })[0] || null;
                      };
                      const findSectionPopupLink = (root, headingBottom) => {
                        if (!root) {
                          return null;
                        }

                        const rowSelectors = "table tbody tr, tbody tr, tr, [role='row'], .ant-table-row";
                        const cellSelectors = "th, td, [role='cell'], .ant-table-cell";
                        const rows = Array.from(root.querySelectorAll(rowSelectors))
                          .filter(isVisible)
                          .sort((a, b) => {
                            const rectA = a.getBoundingClientRect();
                            const rectB = b.getBoundingClientRect();
                            return rectA.top - rectB.top || rectA.left - rectB.left;
                          });
                        const scopedRows = rows.filter(row => row.getBoundingClientRect().top >= headingBottom - 2);
                        const rowCandidates = scopedRows.length > 0 ? scopedRows : rows;

                        for (const row of rowCandidates) {
                          const cells = Array.from(row.querySelectorAll(cellSelectors)).filter(isVisible);
                          const firstCell = cells[0] || Array.from(row.children).find(isVisible) || row;
                          const candidate = findLocationPopupLink(firstCell) || findClickableLink(firstCell);
                          if (candidate) {
                            return candidate;
                          }
                        }

                        return findLocationPopupLink(root) || findClickableLink(root);
                      };

                      const target = normalize(sectionText).toLowerCase();
                      const targetKey = normalizeKey(target);
                      const labelSelectors = "h1, h2, h3, h4, h5, h6, [role='heading'], .ant-card-head-title, .ant-typography";
                      const primaryCandidates = Array.from(document.querySelectorAll(labelSelectors))
                        .filter(el => isTextVisible(el))
                        .filter(el => {
                          const text = normalize(el.innerText).toLowerCase();
                          const textKey = normalizeKey(text);
                          return text === target
                            || text.startsWith(target + ":")
                            || text.startsWith(target + " ")
                            || text.startsWith(target + "-")
                            || textKey === targetKey
                            || textKey.startsWith(targetKey);
                        })
                        .sort((a, b) => normalize(a.innerText).length - normalize(b.innerText).length);
                      const labelCandidates = primaryCandidates.length > 0
                        ? primaryCandidates
                        : Array.from(document.querySelectorAll("body *"))
                            .filter(el => isTextVisible(el))
                            .filter(el => {
                              const text = normalize(el.innerText).toLowerCase();
                              const textKey = normalizeKey(text);
                              return text === target
                                || text.startsWith(target + ":")
                                || text.startsWith(target + " ")
                                || text.startsWith(target + "-")
                                || textKey === targetKey
                                || textKey.startsWith(targetKey);
                            })
                            .sort((a, b) => normalize(a.innerText).length - normalize(b.innerText).length);

                      const labelEl = labelCandidates[0];
                      if (!labelEl) {
                        return false;
                      }

                      labelEl.scrollIntoView({ block: "center", inline: "center" });
                      await new Promise(resolve => setTimeout(resolve, 100));

                      const headingBottom = labelEl.getBoundingClientRect().bottom;
                      const containers = [
                        labelEl.closest("section, article, .ant-card, .ant-card-body, .ant-collapse-item, .ant-collapse-content, [role='region'], .ant-tabs-tabpane, .ant-space, .ant-row, .ant-col"),
                        labelEl.parentElement,
                        labelEl.parentElement?.parentElement
                      ].filter(Boolean);

                      for (const container of containers) {
                        const candidate = findSectionPopupLink(container, headingBottom);
                        if (candidate) {
                          candidate.scrollIntoView({ block: "center", inline: "center" });
                          try {
                            candidate.click();
                          } catch (error) {
                            candidate.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window }));
                          }
                          return true;
                        }
                      }

                      return false;
                    }
                    """, sectionText);

            if (!Boolean.TRUE.equals(clicked)) {
                log.debug("OMSR section address field {} could not be located.", sectionText);
            }
        } catch (PlaywrightException e) {
            if (!isTransientNavigationError(e)) {
                throw e;
            }
        }
    }

    private void applyPopupCapture(CapturedOrdersPayload.Stop stop, PopupCapture capture, boolean pickup) {
        if (stop == null || capture == null) {
            return;
        }

        Map<String, String> attributes = capture.attributes == null ? new LinkedHashMap<>() : capture.attributes;

        String primaryAddress = pickup
                ? valueFor(attributes, "pickup address", "origin address", "ship from address")
                : valueFor(attributes, "delivery address", "destination address", "dropoff address", "receiver address", "ship to address");
        String fallbackAddress = valueFor(attributes, "address", "street address", "address 1", "address line 1", "location address", "street");
        String address = firstNonBlank(
                primaryAddress,
                fallbackAddress,
                buildAddressFromAttributes(attributes, pickup),
                extractAddressFromText(capture.text)
        );

        if (address != null) {
            applyAddress(stop, address, true);
        }

        String popupCity = pickup
                ? valueFor(attributes, "pickup city", "origin city", "city")
                : valueFor(attributes, "delivery city", "destination city", "city");
        String popupState = pickup
                ? valueFor(attributes, "pickup state", "origin state", "state")
                : valueFor(attributes, "delivery state", "destination state", "state");
        String popupZip = pickup
                ? valueFor(attributes, "pickup zip", "origin zip", "pickup zipcode", "zip", "zipcode", "postal code")
                : valueFor(attributes, "delivery zip", "destination zip", "delivery zipcode", "zip", "zipcode", "postal code");

        stop.city = firstNonBlank(stop.city, popupCity);
        stop.state = firstNonBlank(stop.state, popupState);
        stop.zip = firstNonBlank(stop.zip, popupZip);
    }

    private String buildAddressFromAttributes(Map<String, String> attributes, boolean pickup) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }

        String street = pickup
                ? firstNonBlank(
                        valueFor(attributes, "pickup street address", "origin street address", "ship from street address"),
                        valueFor(attributes, "street address", "address 1", "address line 1", "location address", "street"))
                : firstNonBlank(
                        valueFor(attributes, "delivery street address", "destination street address", "ship to street address"),
                        valueFor(attributes, "street address", "address 1", "address line 1", "location address", "street"));
        if (street == null || street.isBlank()) {
            return null;
        }

        String city = pickup
                ? firstNonBlank(valueFor(attributes, "pickup city", "origin city"), valueFor(attributes, "city"))
                : firstNonBlank(valueFor(attributes, "delivery city", "destination city"), valueFor(attributes, "city"));
        String state = pickup
                ? firstNonBlank(valueFor(attributes, "pickup state", "origin state"), valueFor(attributes, "state"))
                : firstNonBlank(valueFor(attributes, "delivery state", "destination state"), valueFor(attributes, "state"));
        String zip = pickup
                ? firstNonBlank(valueFor(attributes, "pickup zip", "origin zip", "pickup zipcode"), valueFor(attributes, "zip", "zipcode", "postal code"))
                : firstNonBlank(valueFor(attributes, "delivery zip", "destination zip", "delivery zipcode"), valueFor(attributes, "zip", "zipcode", "postal code"));

        StringBuilder builder = new StringBuilder(street.trim());
        if (city != null && !city.isBlank()) {
            builder.append(", ").append(city.trim());
        }
        if (state != null && !state.isBlank()) {
            builder.append(", ").append(state.trim());
        }
        if (zip != null && !zip.isBlank()) {
            builder.append(" ").append(zip.trim());
        }
        return builder.toString();
    }

    private void fillAddressFromBodyText(String bodyText, CapturedOrdersPayload.CapturedOrder order) {
        if (bodyText == null || bodyText.isBlank() || order == null) {
            return;
        }

        String normalizedBodyText = bodyText
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
        normalizedBodyText = normalizeAddressSeparators(normalizedBodyText);

        List<String> matches = findAddressMatches(normalizedBodyText);

        if (matches.isEmpty()) {
            return;
        }

        if (order.pickup == null) {
            order.pickup = new CapturedOrdersPayload.Stop();
        }
        if (order.dropoff == null) {
            order.dropoff = new CapturedOrdersPayload.Stop();
        }

        if (order.pickup.streetAddress == null || !looksLikeAddress(order.pickup.streetAddress)) {
            applyAddress(order.pickup, matches.get(0), true);
        }
        if (matches.size() > 1 && (order.dropoff.streetAddress == null || !looksLikeAddress(order.dropoff.streetAddress))) {
            applyAddress(order.dropoff, matches.get(1), true);
        }
    }

    private void applyAddress(CapturedOrdersPayload.Stop stop, String address) {
        applyAddress(stop, address, false);
    }

    private void applyAddress(CapturedOrdersPayload.Stop stop, String address, boolean overwrite) {
        if (stop == null || address == null || address.isBlank()) {
            return;
        }
        String trimmed = normalizeAddressSeparators(address.trim());
        if (overwrite || stop.location == null || stop.location.isBlank()) {
            stop.location = trimmed;
        }
        if (overwrite || stop.streetAddress == null || stop.streetAddress.isBlank()) {
            stop.streetAddress = trimmed;
        }
        AddressParts parts = parseAddress(trimmed);
        if (parts != null) {
            if (overwrite || stop.city == null || stop.city.isBlank()) {
                stop.city = parts.city;
            }
            if (overwrite || stop.state == null || stop.state.isBlank()) {
                stop.state = parts.state;
            }
            if (overwrite || stop.zip == null || stop.zip.isBlank()) {
                stop.zip = parts.zip;
            }
        }
    }

    private List<String> findAddressMatches(String text) {
        List<String> matches = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matches;
        }

        collectAddressMatches(matches, extractAddressCandidatesFromLabelBlocks(text));
        collectAddressMatches(matches, text, COMMA_ADDRESS_SEARCH_PATTERN);
        collectAddressMatches(matches, text, SPACE_ADDRESS_SEARCH_PATTERN);
        return matches;
    }

    private List<String> extractAddressCandidatesFromLabelBlocks(String text) {
        List<String> candidates = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return candidates;
        }

        List<String> lines = normalizedLines(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!startsAddressLabel(line)) {
                continue;
            }

            String candidate = joinLines(lines, i, i + 4);
            candidate = candidate.replaceFirst("(?i)^(?:address|location|street)\\s*:?", "");
            candidate = normalizeAddressSeparators(candidate);
            candidate = normalizeWhitespace(candidate);
            if (candidate != null && !candidate.isBlank() && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    private boolean startsAddressLabel(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String normalized = value.trim().toLowerCase(Locale.US);
        return normalized.startsWith("address")
                || normalized.startsWith("location")
                || normalized.startsWith("street");
    }

    private List<String> normalizedLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String cleaned = text
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ');
        return Arrays.stream(cleaned.split("\\R"))
                .map(line -> line.replaceAll("[\\p{Z}\\s]+", " ").trim())
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String joinLines(List<String> lines, int startInclusive, int endExclusive) {
        if (lines == null || lines.isEmpty() || startInclusive >= lines.size()) {
            return null;
        }

        int safeEnd = Math.min(endExclusive, lines.size());
        return String.join(" ", lines.subList(startInclusive, safeEnd));
    }

    private void collectAddressMatches(List<String> matches, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group();
            if (!matches.contains(match)) {
                matches.add(match);
            }
        }
    }

    private void collectAddressMatches(List<String> matches, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && !matches.contains(candidate)) {
                matches.add(candidate);
            }
        }
    }

    private String extractAddressFromText(String text) {
        List<String> matches = findAddressMatches(text);
        if (matches.isEmpty()) {
            String normalized = normalizeAddressSeparators(normalizeWhitespace(text));
            if (normalized != null && !normalized.equals(text)) {
                matches = findAddressMatches(normalized);
            }
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    private String normalizeAddressSeparators(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        return value.replaceAll("\\s*/\\s*", " ")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    private AddressParts parseAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = normalizeAddressSeparators(value);
        for (Pattern pattern : List.of(COMMA_SEPARATED_ADDRESS_PATTERN, SPACE_SEPARATED_ADDRESS_PATTERN)) {
            Matcher matcher = pattern.matcher(normalized);
            if (matcher.matches()) {
                return new AddressParts(
                        matcher.group("street").trim(),
                        matcher.group("city").trim(),
                        matcher.group("state").trim(),
                        matcher.group("zip") == null ? null : matcher.group("zip").trim()
                );
            }
        }

        String[] parts = normalized.split(",");
        if (parts.length >= 2) {
            String cityStateZip = parts[parts.length - 1].trim();
            Matcher cityStateZipMatcher = Pattern.compile("^(?<city>.+?)\\s+(?<state>[A-Z]{2})(?:\\s+(?<zip>\\d{5}(?:-\\d{4})?))?$").matcher(cityStateZip);
            if (cityStateZipMatcher.matches()) {
                StringBuilder street = new StringBuilder();
                for (int i = 0; i < parts.length - 2; i++) {
                    if (street.length() > 0) {
                        street.append(", ");
                    }
                    street.append(parts[i].trim());
                }
                if (street.length() == 0) {
                    street.append(parts[0].trim());
                }

                return new AddressParts(
                        street.toString(),
                        cityStateZipMatcher.group("city").trim(),
                        cityStateZipMatcher.group("state").trim(),
                        cityStateZipMatcher.group("zip") == null ? null : cityStateZipMatcher.group("zip").trim()
                );
            }
        }

        return new AddressParts(normalized, null, null, null);
    }

    private boolean looksLikeAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String normalized = normalizeAddressSeparators(value);
        return COMMA_SEPARATED_ADDRESS_PATTERN.matcher(normalized).matches()
                || SPACE_SEPARATED_ADDRESS_PATTERN.matcher(normalized).matches();
    }

    private Locator firstDataRow(Frame frame) {
        long deadline = System.nanoTime() + (long) (ROW_WAIT_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            try {
                Locator rows = frame.locator(browserConfig.getLoadRowSelector());
                Locator fallback = null;
                int rowCount = count(rows);
                for (int i = 0; i < rowCount; i++) {
                    Locator row = rows.nth(i);
                    if (count(row.locator(OMSR_LOAD_DETAILS_LINK_SELECTOR)) > 0) {
                        return row;
                    }
                    if (fallback == null && looksLikeOmsrLoadRow(row)) {
                        fallback = row;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
            }
            sleep(100);
        }
        return null;
    }

    private List<OmsrLoadSummary> skipAlreadyExtractedLoads(List<OmsrLoadSummary> loadSummaries) {
        if (loadSummaries == null || loadSummaries.isEmpty() || processedLoadStore.isEmpty()) {
            return loadSummaries == null ? List.of() : loadSummaries;
        }

        List<String> loadNumbers = loadSummaries.stream()
                .map(OmsrLoadSummary::load)
                .map(load -> load == null ? null : load.externalOrderId)
                .filter(loadNumber -> loadNumber != null && !loadNumber.isBlank())
                .toList();
        Set<String> alreadyExtracted = processedLoadStore.get().alreadyExtractedLoadNumbers(loadNumbers);
        if (alreadyExtracted.isEmpty()) {
            return loadSummaries;
        }

        List<OmsrLoadSummary> remaining = loadSummaries.stream()
                .filter(summary -> summary.load() == null
                        || summary.load().externalOrderId == null
                        || !alreadyExtracted.contains(summary.load().externalOrderId.trim()))
                .toList();
        log.info("Skipping {} already extracted OMSR load(s); {} new load(s) remain.",
                loadSummaries.size() - remaining.size(), remaining.size());
        return remaining;
    }

    private void markLoadExtracted(String runId, CapturedOrdersPayload.CapturedOrder load) {
        processedLoadStore.ifPresent(store -> store.markExtracted(runId, load));
    }

    private List<OmsrLoadSummary> collectOrderLoadSummaries(Frame initialFrame, int limit) {
        List<OmsrLoadSummary> summaries = new ArrayList<>();
        Set<String> seenLoadNumbers = new LinkedHashSet<>();
        Set<String> seenPageSignatures = new LinkedHashSet<>();
        Page page = initialFrame.page();
        Frame frame = initialFrame;
        int pageNumber = 1;

        while (true) {
            waitForOptionalRows(frame);
            String pageSignature = orderListSignature(frame);
            if (pageSignature != null && !seenPageSignatures.add(pageSignature)) {
                log.warn("Stopping OMSR pagination after seeing duplicate order-list page signature: {}", pageSignature);
                break;
            }

            int collectedBeforePage = summaries.size();
            Map<String, Integer> headerIndex = headerIndex(frame);
            collectCurrentOrderPageSummaries(frame, headerIndex, limit, seenLoadNumbers, summaries);
            log.debug("Collected {} OMSR load summaries from order-list page {}.",
                    summaries.size() - collectedBeforePage, pageNumber);

            if (isLoadLimitReached(limit, summaries.size())) {
                break;
            }

            Frame nextFrame = clickNextOrdersPage(frame);
            if (nextFrame == null) {
                break;
            }
            frame = resolveOrdersFrame(page);
            pageNumber++;
        }

        log.info("Collected {} OMSR load summaries across {} order-list page(s).", summaries.size(), pageNumber);
        return summaries;
    }

    private void collectCurrentOrderPageSummaries(
            Frame frame,
            Map<String, Integer> headerIndex,
            int limit,
            Set<String> seenLoadNumbers,
            List<OmsrLoadSummary> summaries
    ) {
        if (scrollOrderListToTop(frame)) {
            sleep(150);
        }
        String previousSignature = null;
        int stableSignatures = 0;

        for (int step = 0; step < ORDER_LIST_SCROLL_STEPS && !isLoadLimitReached(limit, summaries.size()); step++) {
            Map<String, Integer> currentHeaderIndex = headerIndex;
            if (currentHeaderIndex == null || currentHeaderIndex.isEmpty()) {
                currentHeaderIndex = headerIndex(frame);
            }

            collectRenderedOrderPageSummaries(frame, currentHeaderIndex, limit, seenLoadNumbers, summaries);

            String currentSignature = orderListSignature(frame);
            if (currentSignature != null && currentSignature.equals(previousSignature)) {
                stableSignatures++;
            } else {
                stableSignatures = 0;
                previousSignature = currentSignature;
            }

            if (isLoadLimitReached(limit, summaries.size()) || !scrollOrderListDown(frame)) {
                break;
            }

            sleep(150);
            if (stableSignatures >= ORDER_LIST_STABLE_SCROLL_SIGNATURES) {
                break;
            }
        }
    }

    private int collectRenderedOrderPageSummaries(
            Frame frame,
            Map<String, Integer> headerIndex,
            int limit,
            Set<String> seenLoadNumbers,
            List<OmsrLoadSummary> summaries
    ) {
        int collectedBefore = summaries.size();
        Locator rows = frame.locator(browserConfig.getLoadRowSelector());
        int rowCount = count(rows);
        for (int index = 0; index < rowCount && !isLoadLimitReached(limit, summaries.size()); index++) {
            Locator row = rows.nth(index);
            if (!isCandidateLoadRow(row)) {
                continue;
            }

            CapturedOrdersPayload.CapturedOrder load = extractSummary(row, headerIndex);
            if (load.externalOrderId == null || load.externalOrderId.isBlank()) {
                continue;
            }

            if (!seenLoadNumbers.add(load.externalOrderId)) {
                continue;
            }

            String detailUrl = loadDetailUrl(frame, row);
            if (detailUrl == null || detailUrl.isBlank()) {
                captureDebugSnapshot(frame.page(), "omsr-load-link-not-found");
                throw new IllegalStateException("Unable to resolve OMSR load detail URL for load number "
                        + load.externalOrderId + ".");
            }

            summaries.add(new OmsrLoadSummary(load, detailUrl));
        }
        return summaries.size() - collectedBefore;
    }

    private boolean scrollOrderListToTop(Frame frame) {
        return scrollOrderList(frame, false);
    }

    private boolean scrollOrderListDown(Frame frame) {
        return scrollOrderList(frame, true);
    }

    private boolean scrollOrderList(Frame frame, boolean down) {
        try {
            Object value = frame.evaluate("""
                    ([selector, down]) => {
                      const isVisible = element => {
                        const rect = element.getBoundingClientRect();
                        const style = window.getComputedStyle(element);
                        return rect.width > 0 && rect.height > 0 && style.visibility !== "hidden" && style.display !== "none";
                      };
                      const isScrollable = element => {
                        if (!element || !isVisible(element)) return false;
                        const style = window.getComputedStyle(element);
                        return /(auto|scroll)/.test(style.overflowY || "")
                          && element.scrollHeight > element.clientHeight + 5;
                      };
                      const targets = [];
                      const seen = new Set();
                      const add = element => {
                        if (!element || seen.has(element)) return;
                        seen.add(element);
                        targets.push(element);
                      };

                      Array.from(document.querySelectorAll(selector)).forEach(row => {
                        for (let element = row.parentElement; element; element = element.parentElement) {
                          if (isScrollable(element)) add(element);
                        }
                      });
                      Array.from(document.querySelectorAll("*")).forEach(element => {
                        if (isScrollable(element)) add(element);
                      });
                      add(document.scrollingElement || document.documentElement);
                      add(document.body);

                      let moved = false;
                      for (const target of targets) {
                        const maxTop = Math.max(0, target.scrollHeight - target.clientHeight);
                        if (maxTop <= 0) continue;

                        const before = target.scrollTop || 0;
                        const next = down
                          ? Math.min(maxTop, before + Math.max(200, target.clientHeight - 20))
                          : 0;
                        target.scrollTop = next;
                        if (Math.abs((target.scrollTop || 0) - before) > 1) {
                          moved = true;
                        }
                      }

                      if (down) {
                        const beforeY = window.scrollY || window.pageYOffset || 0;
                        window.scrollBy(0, Math.max(200, window.innerHeight - 20));
                        if (Math.abs((window.scrollY || window.pageYOffset || 0) - beforeY) > 1) moved = true;
                      } else {
                        const beforeY = window.scrollY || window.pageYOffset || 0;
                        window.scrollTo(0, 0);
                        if (Math.abs((window.scrollY || window.pageYOffset || 0) - beforeY) > 1) moved = true;
                      }

                      return moved;
                    }
                    """, List.of(browserConfig.getLoadRowSelector(), down));
            return Boolean.TRUE.equals(value);
        } catch (PlaywrightException e) {
            log.debug("Unable to scroll OMSR order list while collecting rows: {}", e.getMessage());
            return false;
        }
    }

    private boolean isLoadLimitReached(int limit, int collectedCount) {
        return limit > 0 && collectedCount >= limit;
    }

    private boolean isConfirmedLoadStatus(CapturedOrdersPayload.CapturedOrder load) {
        if (load == null || load.status == null || load.status.isBlank()) {
            return false;
        }
        String normalized = normalizeKey(load.status);
        return normalized.equals("confirmed") || normalized.startsWith("confirmed");
    }

    private Frame clickNextOrdersPage(Frame frame) {
        Locator nextLink = nextOrdersPageLink(frame);
        if (nextLink == null || !isPaginationLinkClickable(nextLink)) {
            // No enabled/actionable "Next" link means we have reached the last order-list page.
            return null;
        }

        String beforeSignature = orderListSignature(frame);
        Page page = frame.page();
        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                nextLink.click(new Locator.ClickOptions().setTimeout(PAGINATION_CLICK_TIMEOUT_MS));
                return waitForOrdersPageChange(page, beforeSignature);
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                frame = resolveOrdersFrame(page);
                nextLink = nextOrdersPageLink(frame);
                if (nextLink == null || !isPaginationLinkClickable(nextLink)) {
                    return null;
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private Locator nextOrdersPageLink(Frame frame) {
        Locator links = frame.locator("a");
        int linkCount = count(links);
        for (int index = 0; index < linkCount; index++) {
            Locator link = links.nth(index);
            String label = normalizeWhitespace(text(link));
            String href = attribute(link, "href");
            String onclick = attribute(link, "onclick");
            String title = attribute(link, "title");
            String ariaLabel = attribute(link, "aria-label");
            String className = attribute(link, "class");
            String combined = ((label == null ? "" : label) + " "
                    + (href == null ? "" : href) + " "
                    + (onclick == null ? "" : onclick) + " "
                    + (title == null ? "" : title) + " "
                    + (ariaLabel == null ? "" : ariaLabel) + " "
                    + (className == null ? "" : className)).toLowerCase(Locale.ROOT);
            if (isDisabledOrderPaginationLink(link, combined)) {
                continue;
            }

            String normalizedLabel = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
            boolean looksLikeNext = combined.contains("next")
                    || normalizedLabel.equals(">")
                    || normalizedLabel.equals(">>");
            boolean looksLikeOrderPagination = combined.contains("setprevnext")
                    || combined.contains("newstart")
                    || combined.contains("start")
                    || combined.contains("page");
            if (looksLikeNext && looksLikeOrderPagination) {
                return link;
            }
        }
        return null;
    }

    private boolean isDisabledOrderPaginationLink(Locator link, String combined) {
        String disabled = attribute(link, "disabled");
        String ariaDisabled = attribute(link, "aria-disabled");
        return disabled != null
                || "true".equalsIgnoreCase(ariaDisabled)
                || (combined != null && combined.contains("disabled"));
    }

    /**
     * Determines whether a matched pagination "Next" link is actually actionable.
     * OMSR marks the last-page Next control as disabled on the anchor <em>or an
     * ancestor</em> (class {@code disabled}, {@code aria-disabled}, or
     * {@code pointer-events:none}), which {@link #isDisabledOrderPaginationLink}
     * cannot see from the anchor's own attributes alone. Clicking such a link would
     * block until the Playwright timeout, so we detect it up front and treat it as
     * "no next page".
     */
    private boolean isPaginationLinkClickable(Locator link) {
        if (link == null) {
            return false;
        }
        try {
            Object actionable = link.evaluate("""
                    el => {
                      if (!el) return false;
                      const disabledMarker = node =>
                          node.hasAttribute('disabled')
                          || node.getAttribute('aria-disabled') === 'true'
                          || (typeof node.className === 'string'
                              && node.className.toLowerCase().includes('disabled'));
                      let node = el;
                      for (let i = 0; i < 5 && node; i++) {
                        if (disabledMarker(node)) return false;
                        node = node.parentElement;
                      }
                      const style = window.getComputedStyle(el);
                      if (style.pointerEvents === 'none'
                          || style.display === 'none'
                          || style.visibility === 'hidden') {
                        return false;
                      }
                      const rect = el.getBoundingClientRect();
                      return rect.width > 0 && rect.height > 0;
                    }
                    """);
            return Boolean.TRUE.equals(actionable);
        } catch (PlaywrightException e) {
            if (isTransientNavigationError(e)) {
                // Let the click/retry loop handle transient navigation churn.
                return true;
            }
            try {
                return link.isVisible() && link.isEnabled();
            } catch (PlaywrightException ignored) {
                return false;
            }
        }
    }

    private Frame waitForOrdersPageChange(Page page, String beforeSignature) {
        long deadline = System.nanoTime() + (long) (DEFAULT_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            Frame frame = bestEffortOrdersFrame(page);
            if (frame != null) {
                String afterSignature = orderListSignature(frame);
                if (afterSignature != null
                        && !afterSignature.isBlank()
                        && !afterSignature.equals(beforeSignature)
                        && firstDataRow(frame) != null) {
                    return frame;
                }
            }
            sleep(100);
        }

        captureDebugSnapshot(page, "omsr-orders-next-page-timeout");
        throw new IllegalStateException("Timed out waiting for OMSR order list to advance to the next page.");
    }

    private String orderListSignature(Frame frame) {
        try {
            Object value = frame.evaluate("""
                    (selector) => {
                      const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                      const text = normalize(document.body?.innerText || document.documentElement?.innerText || "");
                      const range = text.match(/\\b\\d+\\s*-\\s*\\d+\\s+of\\s+\\d+\\b/i)?.[0] || "";
                      const rows = Array.from(document.querySelectorAll(selector));
                      const ids = rows.map(row => {
                        const cells = Array.from(row.querySelectorAll("td, [role='cell']"));
                        const firstCell = cells.find(cell => normalize(cell.innerText || cell.textContent));
                        return normalize(firstCell?.innerText || firstCell?.textContent || "");
                      }).filter(Boolean).slice(0, 5).join("|");
                      return `${range}|${ids}`;
                    }
                    """, browserConfig.getLoadRowSelector());
            if (value == null) {
                return null;
            }
            String signature = value.toString();
            return signature.isBlank() ? null : signature;
        } catch (PlaywrightException e) {
            return null;
        }
    }

    private String loadDetailUrl(Frame frame, Locator row) {
        Locator link = firstLoadDetailLink(row);
        if (link == null) {
            return null;
        }

        String href = attribute(link, "href");
        if (href == null || href.isBlank() || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            return null;
        }
        return absoluteFrameUrl(frame, href);
    }

    private Locator firstLoadDetailLink(Locator row) {
        Locator loadDetailsLinks = row.locator(OMSR_LOAD_DETAILS_LINK_SELECTOR);
        if (count(loadDetailsLinks) > 0) {
            return loadDetailsLinks.first();
        }

        List<Locator> cells = dataCells(row);
        for (Locator cell : cells) {
            Locator exactLoadLink = cell.locator(OMSR_LOAD_DETAILS_LINK_SELECTOR);
            if (count(exactLoadLink) > 0) {
                return exactLoadLink.first();
            }
        }
        return null;
    }

    private String absoluteFrameUrl(Frame frame, String href) {
        try {
            URI baseUri = URI.create(frame.url());
            return baseUri.resolve(href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    private String absolutePageUrl(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        try {
            URI baseUri = URI.create(browserConfig.getOrdersUrl());
            return baseUri.resolve(href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    private Frame openLoadDetail(Page page, Frame frame, OmsrLoadSummary summary) {
        String beforeDetailUrl = frame.url();
        frame.evaluate("(url) => { window.location.href = url; }", summary.detailUrl());
        waitForLoadDetail(frame, beforeDetailUrl);
        return bestEffortOrdersFrame(page) == null ? frame : resolveOrdersFrame(page);
    }

    private String attribute(Locator locator, String name) {
        if (locator == null || name == null || name.isBlank()) {
            return null;
        }

        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                if (count(locator) == 0) {
                    return null;
                }
                String value = locator.getAttribute(name);
                return value == null ? null : value.trim();
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(100L * attempt);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private Locator findLoadRow(Frame frame, Map<String, Integer> headerIndex, String loadNumber) {
        if (frame == null || loadNumber == null || loadNumber.isBlank()) {
            return null;
        }
        String targetLoadNumber = loadNumber.trim();
        long deadline = System.nanoTime() + (long) (ROW_WAIT_TIMEOUT_MS * 1_000_000);
        PlaywrightException lastError = null;

        while (System.nanoTime() < deadline) {
            try {
                Map<String, Integer> currentHeaderIndex = headerIndex(frame);
                if (currentHeaderIndex.isEmpty() && headerIndex != null) {
                    currentHeaderIndex = headerIndex;
                }

                Locator rows = frame.locator(browserConfig.getLoadRowSelector());
                int rowCount = count(rows);
                for (int index = 0; index < rowCount; index++) {
                    Locator row = rows.nth(index);
                    if (rowMatchesLoadNumber(row, currentHeaderIndex, targetLoadNumber)) {
                        return row;
                    }
                }
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
            }

            sleep(100);
        }

        if (lastError != null) {
            throw lastError;
        }

        return null;
    }

    private boolean rowMatchesLoadNumber(Locator row, Map<String, Integer> headerIndex, String targetLoadNumber) {
        if (row == null || targetLoadNumber == null || targetLoadNumber.isBlank()) {
            return false;
        }

        CapturedOrdersPayload.CapturedOrder candidate = extractSummary(row, headerIndex);
        if (candidate != null && targetLoadNumber.equals(candidate.externalOrderId)) {
            return true;
        }

        String candidateLoadNumber = rowLoadNumber(row);
        if (targetLoadNumber.equals(candidateLoadNumber)) {
            return true;
        }

        return rowTextContainsLoadNumber(text(row), targetLoadNumber);
    }

    private boolean isCandidateLoadRow(Locator row) {
        return row != null
                && (count(row.locator(OMSR_LOAD_DETAILS_LINK_SELECTOR)) > 0
                || looksLikeOmsrLoadRow(row));
    }

    private String rowLoadNumber(Locator row) {
        List<Locator> cells = dataCells(row);
        if (cells.isEmpty()) {
            return null;
        }

        return cleanedCellValue(text(cells.get(0)), "load #", "load number", "load id");
    }

    private boolean rowTextContainsLoadNumber(String rowText, String loadNumber) {
        if (rowText == null || rowText.isBlank() || loadNumber == null || loadNumber.isBlank()) {
            return false;
        }

        String normalizedRowText = normalizeWhitespace(rowText);
        if (normalizedRowText == null || normalizedRowText.isBlank()) {
            return false;
        }

        Pattern pattern = Pattern.compile("(?<!\\d)" + Pattern.quote(loadNumber.trim()) + "(?!\\d)");
        return pattern.matcher(normalizedRowText).find();
    }

    private boolean looksLikeOmsrLoadRow(Locator row) {
        List<Locator> cells = dataCells(row);
        if (cells.size() < 8) {
            return false;
        }

        String firstCellText = cleanedCellValue(text(cells.get(0)));
        return firstCellText != null && OMSR_LOAD_NUMBER_PATTERN.matcher(firstCellText).matches();
    }

    private Map<String, Integer> headerIndex(Frame frame) {
        Locator headers = frame.locator("thead tr th, [role='columnheader']");
        Map<String, Integer> result = new LinkedHashMap<>();
        int headerCount = count(headers);
        for (int i = 0; i < headerCount; i++) {
            String text = cleanedCellValue(text(headers.nth(i)));
            if (text != null && !text.isBlank()) {
                result.putIfAbsent(text, i);
            }
        }
        return result.isEmpty() ? legacyHeaderIndex(frame) : result;
    }

    private Map<String, Integer> legacyHeaderIndex(Frame frame) {
        Locator rows = frame.locator("tr");
        int rowCount = Math.min(count(rows), 25);
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            List<Locator> cells = dataCells(rows.nth(rowIndex));
            if (cells.size() < 3) {
                continue;
            }

            Map<String, Integer> candidate = new LinkedHashMap<>();
            for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                String text = cleanedCellValue(text(cells.get(cellIndex)));
                if (text != null && isKnownOmsrHeader(text)) {
                    candidate.putIfAbsent(text, cellIndex);
                }
            }

            if (findHeaderIndex(candidate, "load number") != null
                    && findHeaderIndex(candidate, "status name") != null) {
                return candidate;
            }
        }
        return new LinkedHashMap<>();
    }

    private boolean isKnownOmsrHeader(String value) {
        String key = normalizeKey(value);
        return key.equals("loadnumber")
                || key.equals("buyer")
                || key.equals("statusname")
                || key.equals("shipdate")
                || key.equals("arrivaldate")
                || key.equals("altad")
                || key.equals("submitteddate")
                || key.equals("pr")
                || key.equals("rate")
                || key.equals("mode")
                || key.equals("pucount")
                || key.equals("transnumber")
                || key.equals("arrivalappointment");
    }

    private List<Locator> dataCells(Locator row) {
        Locator cells = row.locator("td, [role='cell']");
        List<Locator> result = new ArrayList<>();
        int cellCount = count(cells);
        for (int i = 0; i < cellCount; i++) {
            Locator cell = cells.nth(i);
            if (i == 0 && count(cell.locator("input[type='checkbox'], .ant-checkbox")) > 0) {
                continue;
            }
            result.add(cell);
        }
        return result;
    }

    private String rowValue(List<Locator> cells, Map<String, Integer> headerIndex, int fallbackIndex, String... labels) {
        String byHeader = valueForHeader(cells, headerIndex, labels);
        if (byHeader != null) {
            return byHeader;
        }
        if (fallbackIndex >= 0 && fallbackIndex < cells.size()) {
            return cleanedCellValue(text(cells.get(fallbackIndex)));
        }
        return null;
    }

    private String statusValue(List<Locator> cells, Map<String, Integer> headerIndex) {
        String byHeader = valueForHeader(cells, headerIndex, "status name", "status");
        if (looksLikeStatusValue(byHeader)) {
            return byHeader;
        }

        for (int fallbackIndex : new int[]{2, 10}) {
            if (fallbackIndex < cells.size()) {
                String candidate = cleanedCellValue(text(cells.get(fallbackIndex)));
                if (looksLikeStatusValue(candidate)) {
                    return candidate;
                }
            }
        }

        for (Locator cell : cells) {
            String candidate = cleanedCellValue(text(cell));
            if (looksLikeStatusValue(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean looksLikeStatusValue(String value) {
        String key = normalizeKey(value);
        return key.equals("open")
                || key.startsWith("open")
                || key.equals("confirmed")
                || key.equals("closed")
                || key.equals("cancelled")
                || key.equals("canceled")
                || key.equals("booked")
                || key.equals("covered")
                || key.equals("tendered");
    }

    private String valueForHeader(List<Locator> cells, Map<String, Integer> headerIndex, String... labels) {
        if (headerIndex == null || headerIndex.isEmpty()) {
            return null;
        }

        for (String label : labels) {
            Integer index = findHeaderIndex(headerIndex, label);
            if (index != null && index < cells.size()) {
                return cleanedCellValue(text(cells.get(index)), label);
            }
        }
        return null;
    }

    private Integer findHeaderIndex(Map<String, Integer> headerIndex, String label) {
        String normalizedLabel = normalizeKey(label);
        for (Map.Entry<String, Integer> entry : headerIndex.entrySet()) {
            String normalizedHeader = normalizeKey(entry.getKey());
            if (normalizedHeader.equals(normalizedLabel)
                    || normalizedHeader.contains(normalizedLabel)
                    || normalizedLabel.contains(normalizedHeader)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String cleanedCellValue(String value, String... labels) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        for (String label : labels) {
            String normalizedLabel = label == null ? "" : label.trim();
            if (!normalizedLabel.isBlank()) {
                cleaned = cleaned.replaceFirst("(?i)^" + Pattern.quote(normalizedLabel) + "\\s*[:#-]?\\s*", "");
            }
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String text(Locator locator) {
        if (locator == null) {
            return null;
        }

        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                if (count(locator) == 0) {
                    return null;
                }
                String value = locator.innerText();
                return value == null ? null : value.trim();
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(100L * attempt);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private String frameBodyText(Frame frame) {
        try {
            return (String) frame.evaluate("() => (document.body && document.body.innerText) || document.documentElement?.innerText || ''");
        } catch (PlaywrightException e) {
            return null;
        }
    }

    private void logExtractedLoad(Frame frame, CapturedOrdersPayload.CapturedOrder load) {
        try {
            log.debug("Extracted OMSR load from {}: {}", frame.url(), mapper.writeValueAsString(load));
        } catch (Exception e) {
            log.debug("Extracted OMSR load from {} but could not serialize debug payload", frame.url(), e);
        }
    }

    private void validateLoginConfiguration() {
        if (browserConfig.getUsername() == null || browserConfig.getUsername().isBlank()) {
            throw new IllegalStateException("Missing OMSR username. Set agent.omsr.username.");
        }
        if (browserConfig.getPassword() == null || browserConfig.getPassword().isBlank()) {
            throw new IllegalStateException("Missing OMSR password. Set agent.omsr.password.");
        }
    }

    private void waitForLoginResult(Page page) {
        try {
            page.waitForFunction(
                    """
                    () => {
                      const text = (document.body.innerText || "").toLowerCase();
                      const path = window.location.pathname.toLowerCase();
                      return !path.includes("/login")
                        || text.includes("incorrect")
                        || text.includes("invalid")
                        || text.includes("error")
                        || document.querySelector("[role='alert']") !== null;
                    }
                    """,
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(DEFAULT_TIMEOUT_MS)
            );
        } catch (PlaywrightException e) {
            captureDebugSnapshot(page, "omsr-login-timeout");
            throw e;
        }
    }

    private boolean waitForOrdersOrAuthUrl(Page page, double timeoutMs) {
        long deadline = System.nanoTime() + (long) (timeoutMs * 1_000_000);
        while (System.nanoTime() < deadline) {
            if (isOrdersPage(page) || isAuthPage(page)) {
                return true;
            }
            sleep(100);
        }
        return false;
    }

    private void waitForOptionalRows(Frame frame) {
        if (firstDataRow(frame) != null || hasNoDataPlaceholder(frame)) {
            return;
        }

        captureDebugSnapshot(frame.page(), "omsr-orders-rows-not-found");
    }

    private boolean hasNoDataPlaceholder(Frame frame) {
        try {
            Object value = frame.evaluate("""
                    (selector) => {
                      const text = ((document.body && document.body.innerText) || document.documentElement?.innerText || "")
                        .toLowerCase();
                      const rows = document.querySelectorAll(selector).length;
                      return rows === 0 && (
                        text.includes("no data")
                        || text.includes("no records")
                        || document.querySelector(".ant-empty-description") !== null
                        || document.querySelector(".ant-table-placeholder") !== null
                      );
                    }
                    """, browserConfig.getLoadRowSelector());

            return Boolean.TRUE.equals(value);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private void waitForLoadDetail(Frame frame, String beforeUrl) {
        waitForLoadDetail(frame, beforeUrl, null);
    }

    private void waitForLoadDetail(Frame frame, String beforeUrl, AtomicReference<String> orderDetailResponseJson) {
        long deadline = System.nanoTime() + (long) (DEFAULT_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            String currentUrl = frame.url();
            String text = frameBodyText(frame);
            if (text == null) {
                sleep(100);
                continue;
            }

            String normalizedText = text.toLowerCase(Locale.ROOT);
            boolean detailMarkersPresent = normalizedText.contains("load number")
                    || normalizedText.contains("origin")
                    || normalizedText.contains("delivery address")
                    || normalizedText.contains("appointment #")
                    || normalizedText.contains("ship from")
                    || normalizedText.contains("sw/h")
                    || normalizedText.contains("rw/h")
                    || normalizedText.contains("sw/r")
                    || normalizedText.contains("drop off")
                    || normalizedText.contains("destination");
            boolean poMarkersPresent = normalizedText.contains("po number")
                    || normalizedText.contains("po no")
                    || normalizedText.contains("purchase order");
            boolean detailReady = (currentUrl != null && !currentUrl.equals(beforeUrl) && text.length() > 100)
                    || detailMarkersPresent;

            if (hasOrderDetailResponse(orderDetailResponseJson)
                    || (detailReady && poMarkersPresent)
                    || normalizedText.contains("error")
                    || normalizedText.contains("not found")) {
                return;
            }

            sleep(100);
        }

        captureDebugSnapshot(frame.page(), "omsr-load-detail-timeout");
        throw new IllegalStateException("Timed out waiting for the OMSR load detail frame to update.");
    }

    private boolean hasOrderDetailResponse() {
        return hasOrderDetailResponse(null);
    }

    private boolean hasOrderDetailResponse(AtomicReference<String> orderDetailResponseJson) {
        String responseJson = orderDetailResponseJson == null
                ? lastOrderDetailResponseJson
                : orderDetailResponseJson.get();
        return responseJson != null && !responseJson.isBlank();
    }

    private boolean isLoginFailure(Page page) {
        return extractLoginFailureMessage(page) != null;
    }

    private String extractLoginFailureMessage(Page page) {
        Object value = page.evaluate("""
                () => {
                  const normalize = value => (value || "").replace(/\\s+/g, " ").trim();
                  const failurePhrases = [
                    "username or password",
                    "incorrect",
                    "invalid",
                    "wrong password",
                    "please try again",
                    "account locked",
                    "password expired"
                  ];
                  const hasFailurePhrase = text => {
                    const lower = text.toLowerCase();
                    return failurePhrases.some(phrase => lower.includes(phrase));
                  };

                  const selectors = [
                    "[role='alert']",
                    ".error-container",
                    "mat-error",
                    ".mat-mdc-form-field-error",
                    ".alert-danger"
                  ];
                  const message = selectors
                    .flatMap(selector => Array.from(document.querySelectorAll(selector)))
                    .map(element => normalize(element.innerText || element.textContent))
                    .find(text => text && hasFailurePhrase(text));
                  if (message) {
                    return message;
                  }

                  const bodyText = normalize(document.body?.innerText || "");
                  if (!hasFailurePhrase(bodyText)) {
                    return null;
                  }
                  return bodyText
                    .split(/(?<=[.!?])\\s+/)
                    .find(sentence => hasFailurePhrase(sentence))
                    || bodyText.substring(0, 240);
                }
                """);
        if (value == null) {
            return null;
        }

        String message = value.toString().trim();
        return message.isBlank() ? null : message;
    }

    private boolean isAuthPage(Page page) {
        return page.url().toLowerCase().contains("/login");
    }

    private boolean isOrdersPage(Page page) {
        return page.url().contains("/app/enterprise") || page.url().contains("LogisticsC_OrderStatus");
    }

    private boolean isTransientNavigationError(PlaywrightException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("net::ERR_ABORTED")
                || message.contains("Execution context was destroyed"));
    }

    private int count(Locator locator) {
        if (locator == null) {
            return 0;
        }

        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return locator.count();
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastError = e;
                sleep(100L * attempt);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return 0;
    }

    private void captureOmsrTokenFromResponse(Response response) {
        captureOmsrTokenFromResponse(response, null);
    }

    private void captureOmsrTokenFromResponse(Response response, AtomicReference<String> orderDetailResponseJson) {
        try {
            String headerToken = headerValue(response.headers(), "x-hh-token");
            if (headerToken != null) {
                setOmsrTokenIfPresent(headerToken);
            }

            String url = response.url();
            if (url != null && url.contains("/core/hwyhaul/services/orders/") && !url.endsWith("/notes")) {
                String responseText = response.text();
                captureOrderDetailResponse(response, responseText, orderDetailResponseJson);
                if (headerToken != null) {
                    return;
                }
                String contentType = headerValue(response.headers(), "content-type");
                if (contentType == null || !contentType.toLowerCase().contains("json")) {
                    return;
                }
                setOmsrTokenIfPresent(extractOmsrTokenFromJson(responseText));
                return;
            }

            String contentType = headerValue(response.headers(), "content-type");
            if (contentType == null || !contentType.toLowerCase().contains("json")) {
                return;
            }

            String responseText = response.text();
            if (headerToken != null) {
                return;
            }
            setOmsrTokenIfPresent(extractOmsrTokenFromJson(responseText));
        } catch (Exception ignored) {
            // Token extraction is best effort.
        }
    }

    private void captureOrderDetailResponse(Response response, String responseText) {
        captureOrderDetailResponse(response, responseText, null);
    }

    private void captureOrderDetailResponse(
            Response response,
            String responseText,
            AtomicReference<String> orderDetailResponseJson
    ) {
        if (response == null || responseText == null || responseText.isBlank()) {
            return;
        }

        String url = response.url();
        if (url == null || !url.contains("/core/hwyhaul/services/orders/") || url.endsWith("/notes")) {
            return;
        }

        if (orderDetailResponseJson == null) {
            lastOrderDetailResponseJson = responseText;
        } else {
            orderDetailResponseJson.set(responseText);
        }
    }

    private String extractOmsrTokenFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return findTokenValue(mapper.readTree(json));
        } catch (Exception e) {
            return null;
        }
    }

    private String findTokenValue(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                String normalizedName = field.getKey().replace("-", "").toLowerCase();
                com.fasterxml.jackson.databind.JsonNode value = field.getValue();

                if ((normalizedName.equals("xhhtoken")
                        || normalizedName.equals("hhtoken")
                        || normalizedName.equals("authtoken")
                        || normalizedName.equals("externaltoken")
                        || normalizedName.equals("token"))
                        && value.isTextual()
                        && !value.asText().isBlank()) {
                    return value.asText();
                }

                if (value.isTextual() && looksLikeJson(value.asText())) {
                    String nestedToken = extractOmsrTokenFromJson(value.asText());
                    if (nestedToken != null) {
                        return nestedToken;
                    }
                }

                String nestedToken = findTokenValue(value);
                if (nestedToken != null) {
                    return nestedToken;
                }
            }
        }

        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                String nestedToken = findTokenValue(item);
                if (nestedToken != null) {
                    return nestedToken;
                }
            }
        }

        return null;
    }

    private void enrichLoadFromOrderDetailResponse(CapturedOrdersPayload.CapturedOrder load, String responseJson) {
        if (load == null || responseJson == null || responseJson.isBlank()) {
            return;
        }

        try {
            JsonNode root = mapper.readTree(responseJson);
            if (root == null || root.isNull()) {
                return;
            }

            load.shipperId = firstNonBlank(
                    textByKey(root.path("company"), "shipperId"),
                    textByKey(root, "shipperId"),
                    load.shipperId);
            JsonNode waypoints = root.path("waypoints");
            load.routeStops = routeStopsFromOrderDetailResponse(waypoints);
            backfillPoNumbersFromRouteStops(load);
            if (waypoints.isArray()) {
                for (JsonNode waypoint : waypoints) {
                    String sequenceType = directTextByKey(waypoint, "sequenceType", "type", "stopType");
                    if (sequenceType == null) {
                        continue;
                    }
                    if (sequenceType.toUpperCase().contains("PICK")) {
                        applyWaypointResponse(load.pickup, waypoint, true);
                    } else if (sequenceType.toUpperCase().contains("DROP")) {
                        applyWaypointResponse(load.dropoff, waypoint, false);
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort fallback from the order API response.
        }
    }

    private List<CapturedOrdersPayload.RouteStop> routeStopsFromOrderDetailResponse(JsonNode waypoints) {
        if (waypoints == null || !waypoints.isArray() || waypoints.isEmpty()) {
            return List.of();
        }

        List<CapturedOrdersPayload.RouteStop> routeStops = new ArrayList<>();
        for (JsonNode waypoint : waypoints) {
            CapturedOrdersPayload.RouteStop routeStop = new CapturedOrdersPayload.RouteStop();
            JsonNode address = waypoint.path("addressDTO");

            routeStop.orderSequenceNumber = parseInteger(directTextByKey(waypoint, "orderSequenceNumber"));
            routeStop.sequenceType = normalizeSequenceType(directTextByKey(waypoint, "sequenceType", "type", "stopType"));
            routeStop.addressId = firstNonBlank(textByKey(address, "id"), textByKey(waypoint, "id"));
            routeStop.location = firstNonBlank(
                    textByKey(address, "singleLineAddress", "streetAddress", "addressLine2", "location", "name"),
                    textByKey(waypoint, "location", "name")
            );
            routeStop.streetAddress = firstNonBlank(
                    textByKey(address, "streetAddress", "addressLine2", "line2"),
                    routeStop.location
            );
            routeStop.city = firstNonBlank(
                    textByKey(address, "city", "name"),
                    textByKey(address, "addressCity", "name")
            );
            routeStop.state = firstNonBlank(
                    textByKey(address, "state"),
                    textByKey(address, "addressCity", "state")
            );
            routeStop.zip = firstNonBlank(
                    textByKey(address, "zip"),
                    textByKey(address, "addressCity", "zip")
            );
            routeStop.timezone = firstNonBlank(textByKey(address, "timezone"), textByKey(waypoint, "timezone"));
            routeStop.pickUpNumber = directTextByKey(waypoint, "pickUpNumber", "pickupNumber");
            routeStop.dropOffNumber = directTextByKey(waypoint, "dropOffNumber", "dropoffNumber");
            routeStop.poNumber = directTextByKey(waypoint, "poNumber", "poNo", "po_number", "purchaseOrderNumber", "purchaseOrderNo");
            routeStop.weight = directTextByKey(waypoint, "weight", "grossWeight", "netWeight");
            routeStop.palletCount = directTextByKey(waypoint, "palletCount", "pallets");
            routeStop.caseCount = directTextByKey(waypoint, "caseCount", "cases");
            if (routeStop.sequenceType == null) {
                if (routeStop.pickUpNumber != null && routeStop.dropOffNumber == null) {
                    routeStop.sequenceType = "PICK";
                } else if (routeStop.dropOffNumber != null && routeStop.pickUpNumber == null) {
                    routeStop.sequenceType = "DROP";
                }
            }
            routeStop.earliestPickupDateTime = directTextByKey(waypoint, "earliestPickupDateTime");
            routeStop.latestPickupDateTime = directTextByKey(waypoint, "latestPickupDateTime");
            routeStop.earliestDropoffDateTime = directTextByKey(waypoint, "earliestDropoffDateTime");
            routeStop.latestDropoffDateTime = directTextByKey(waypoint, "latestDropoffDateTime");
            if (routeStop.sequenceType != null && routeStop.sequenceType.contains("PICK")) {
                routeStop.dateTime = firstNonBlank(routeStop.earliestPickupDateTime, routeStop.latestPickupDateTime);
            } else if (routeStop.sequenceType != null && routeStop.sequenceType.contains("DROP")) {
                routeStop.dateTime = firstNonBlank(routeStop.earliestDropoffDateTime, routeStop.latestDropoffDateTime);
            } else {
                routeStop.dateTime = firstNonBlank(
                        routeStop.earliestPickupDateTime,
                        routeStop.latestPickupDateTime,
                        routeStop.earliestDropoffDateTime,
                        routeStop.latestDropoffDateTime
                );
            }

            routeStops.add(routeStop);
        }

        routeStops.sort(Comparator.comparing(
                (CapturedOrdersPayload.RouteStop routeStop) -> routeStop.orderSequenceNumber,
                Comparator.nullsLast(Integer::compareTo)
        ));
        return routeStops;
    }

    private void backfillPoNumbersFromRouteStops(CapturedOrdersPayload.CapturedOrder load) {
        if (load == null || load.routeStops == null || load.routeStops.isEmpty()) {
            return;
        }

        List<String> routePoNumbers = new ArrayList<>();
        for (CapturedOrdersPayload.RouteStop routeStop : load.routeStops) {
            if (routeStop == null || routeStop.poNumber == null || routeStop.poNumber.isBlank()) {
                continue;
            }
            String poNumber = extractPoNumberCandidate(routeStop.poNumber);
            if (poNumber == null || routePoNumbers.contains(poNumber)) {
                continue;
            }
            routePoNumbers.add(poNumber);
        }

        if (!routePoNumbers.isEmpty()) {
            load.poNumbers = routePoNumbers;
        }
    }

    private void applyWaypointResponse(CapturedOrdersPayload.Stop stop, JsonNode waypoint, boolean pickup) {
        if (waypoint == null || waypoint.isNull()) {
            return;
        }

        JsonNode address = waypoint.path("addressDTO");
        String street = firstNonBlank(
                textByKey(address, "streetAddress", "addressLine2", "line2"),
                textByKey(waypoint, "streetAddress", "addressLine2", "line2"));
        String city = firstNonBlank(
                textByKey(address, "city", "name"),
                textByKey(address, "addressCity", "name"),
                textByKey(waypoint, "city"));
        String state = firstNonBlank(
                textByKey(address, "state"),
                textByKey(address, "addressCity", "state"),
                textByKey(waypoint, "state"));
        String zip = firstNonBlank(
                textByKey(address, "zip"),
                textByKey(address, "addressCity", "zip"),
                textByKey(waypoint, "zip"));
        String location = firstNonBlank(
                textByKey(address, "singleLineAddress"),
                textByKey(address, "streetAddress", "addressLine2", "line2"),
                textByKey(waypoint, "location"),
                textByKey(address, "name"),
                textByKey(waypoint, "name"));

        if (stop == null) {
            return;
        }

        stop.addressId = firstNonBlank(stop.addressId, textByKey(address, "id"), textByKey(waypoint, "id"));
        String addressFallback = firstNonBlank(joinAddress(street, city, state, zip), joinCityStateZip(city, state, zip), street);

        stop.location = preferStopValue(stop.location, location);
        if ((stop.location == null || stop.location.isBlank() || isPlaceholderStopValue(stop.location)) && addressFallback != null) {
            stop.location = location != null && !location.isBlank() ? location : addressFallback;
        }

        stop.streetAddress = preferStopAddress(stop.streetAddress, addressFallback);
        if ((stop.streetAddress == null || stop.streetAddress.isBlank()) && addressFallback != null) {
            stop.streetAddress = addressFallback;
        }

        stop.city = preferStopValue(stop.city, city);
        stop.state = preferStopValue(stop.state, state);
        stop.zip = preferStopValue(stop.zip, zip);

        String dateTime = pickup
                ? firstNonBlank(textByKey(waypoint, "earliestPickupDateTime", "latestPickupDateTime"))
                : firstNonBlank(textByKey(waypoint, "earliestDropoffDateTime", "latestDropoffDateTime"));
        stop.dateTime = firstNonBlank(stop.dateTime, dateTime);
    }

    private String joinAddress(String street, String city, String state, String zip) {
        if (street == null || street.isBlank()) {
            return null;
        }

        StringBuilder builder = new StringBuilder(street.trim());
        if (city != null && !city.isBlank()) {
            builder.append(", ").append(city.trim());
        }
        if (state != null && !state.isBlank()) {
            builder.append(", ").append(state.trim());
        }
        if (zip != null && !zip.isBlank()) {
            builder.append(" ").append(zip.trim());
        }
        return builder.toString();
    }

    private String joinCityStateZip(String city, String state, String zip) {
        if ((city == null || city.isBlank()) && (state == null || state.isBlank()) && (zip == null || zip.isBlank())) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        if (city != null && !city.isBlank()) {
            builder.append(city.trim());
        }
        if (state != null && !state.isBlank()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(state.trim());
        }
        if (zip != null && !zip.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(zip.trim());
        }
        return builder.toString();
    }

    private String preferStopValue(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank() || isPlaceholderStopValue(current)) {
            return candidate;
        }
        return current;
    }

    private String preferStopAddress(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank() || isPlaceholderStopValue(current)) {
            return candidate;
        }
        if (!looksLikeAddress(current) && looksLikeAddress(candidate)) {
            return candidate;
        }
        return current;
    }

    private boolean isPlaceholderStopValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }

        String normalized = normalizeWhitespace(value).toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        return normalized.isBlank()
                || normalized.equals("swh")
                || normalized.equals("rwh")
                || normalized.equals("swr")
                || normalized.equals("pickup")
                || normalized.equals("dropoff")
                || normalized.equals("delivery")
                || normalized.equals("origin")
                || normalized.equals("destination")
                || normalized.equals("shipper")
                || normalized.equals("receiver")
                || normalized.equals("shipfrom");
    }

    private String textByKey(JsonNode node, String... keys) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && value.isValueNode()) {
                    String text = value.asText();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }

            var fields = node.fields();
            while (fields.hasNext()) {
                String nested = textByKey(fields.next().getValue(), keys);
                if (nested != null) {
                    return nested;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                String nested = textByKey(item, keys);
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String directTextByKey(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private boolean looksLikeJson(String value) {
        String trimmed = value.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name) && header.getValue() != null && !header.getValue().isBlank()) {
                return header.getValue();
            }
        }
        return null;
    }

    private void captureTokenFromStorage(Page page) {
        try {
            String storageJson = (String) page.evaluate("""
                    () => JSON.stringify({
                      sessionStorage: Object.fromEntries(Object.entries(sessionStorage)),
                      localStorage: Object.fromEntries(Object.entries(localStorage))
                    })
                    """);
            setOmsrTokenIfPresent(extractOmsrTokenFromJson(storageJson));
        } catch (Exception ignored) {
            // Storage inspection is best effort.
        }
    }

    private String collectAuthenticationState(BrowserContext context, Page page, String name) {
        try {
            String storageState = context.storageState();
            Path debugDir = Path.of(browserConfig.getDebugDirectory());
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve(name + ".json"), storageState);
            log.debug("OMSR authentication state collected from {}", page.url());
            return storageState;
        } catch (Exception e) {
            log.debug("Unable to collect OMSR authentication state from {}: {}", page.url(), e.getMessage());
            return "{}";
        }
    }

    private String collectSessionStorageState(Page page, String name) {
        try {
            String sessionStorageState = (String) page.evaluate("""
                    JSON.stringify(Object.keys(sessionStorage).reduce((items, key) => {
                      items[key] = sessionStorage.getItem(key);
                      return items;
                    }, {}))
                    """);

            Path debugDir = Path.of(browserConfig.getDebugDirectory());
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve(name + ".json"), sessionStorageState);
            log.debug("OMSR session storage collected from {}: entries={}", page.url(), mapper.readTree(sessionStorageState).size());
            return sessionStorageState;
        } catch (Exception e) {
            log.debug("Unable to collect OMSR session storage from {}: {}", page.url(), e.getMessage());
            return "{}";
        }
    }

    private void captureDebugSnapshot(Page page, String name) {
        try {
            Path debugDir = Path.of(browserConfig.getDebugDirectory());
            Files.createDirectories(debugDir);
            page.screenshot(new Page.ScreenshotOptions().setPath(debugDir.resolve(name + ".png")).setFullPage(true));
            Files.writeString(debugDir.resolve(name + ".html"), page.content());
            Frame frame = bestEffortOrdersFrame(page);
            if (frame != null) {
                try {
                    Files.writeString(debugDir.resolve(name + "-frame.html"), frame.content());
                    Files.writeString(debugDir.resolve(name + "-frame.txt"),
                            "url=" + frame.url() + System.lineSeparator()
                                    + "selector=" + browserConfig.getLoadRowSelector() + System.lineSeparator());
                } catch (Exception ignored) {
                    // Frame capture is best-effort; keep the page-level snapshot.
                }
            }
            Files.writeString(debugDir.resolve(name + ".txt"),
                    "url=" + page.url() + System.lineSeparator()
                            + "title=" + page.title() + System.lineSeparator()
                            + "selector=" + browserConfig.getLoadRowSelector() + System.lineSeparator()
                            + "iframes=" + iframeSummary(page) + System.lineSeparator()
                            + "frames=" + frameUrlSummary(page) + System.lineSeparator());
        } catch (Exception ignored) {
            // Debug artifacts are best-effort.
        }
    }

    private Frame resolveOrdersFrame(Page page) {
        long deadline = System.nanoTime() + (long) (DEFAULT_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            Frame frame = bestEffortOrdersFrame(page);
            if (isOrdersFrameReady(frame)) {
                return frame;
            }
            sleep(100);
        }

        captureDebugSnapshot(page, "omsr-orders-frame-not-found");
        throw new IllegalStateException("Unable to locate the OMSR order status iframe.");
    }

    private Frame bestEffortOrdersFrame(Page page) {
        // OMSR renders the table in a legacy child iframe, not the top-level document.
        for (Frame frame : page.frames()) {
            if (isOrdersFrame(frame) || hasOrdersFrameContent(frame)) {
                return frame;
            }
        }

        Locator candidateIframes = page.locator(OMSR_ORDER_IFRAME_SELECTOR);
        int iframeCount = count(candidateIframes);
        for (int i = 0; i < iframeCount; i++) {
            Locator iframe = candidateIframes.nth(i);
            try {
                ElementHandle handle = iframe.elementHandle(new Locator.ElementHandleOptions().setTimeout(0));
                if (handle == null) {
                    continue;
                }

                Frame frame = handle.contentFrame();
                if (isOrdersFrame(frame) || hasOrdersFrameContent(frame)) {
                    return frame;
                }
            } catch (PlaywrightException ignored) {
                // The iframe may be mid-navigation; keep polling until it settles or the deadline expires.
            }
        }
        return null;
    }

    private Frame bestEffortDetailFrame(Page page) {
        Frame fallbackFrame = page.mainFrame();
        for (Frame frame : page.frames()) {
            String url = frame.url();
            if (url == null) {
                continue;
            }

            String normalizedUrl = url.toLowerCase(Locale.ROOT);
            if (normalizedUrl.contains("load_details")) {
                return frame;
            }
            if (normalizedUrl.contains("logistics_t")) {
                fallbackFrame = frame;
            }
        }
        return fallbackFrame;
    }

    private boolean isOrdersFrameReady(Frame frame) {
        return hasOrdersFrameContent(frame);
    }

    private boolean hasOrdersFrameContent(Frame frame) {
        if (frame == null) {
            return false;
        }

        try {
            Object value = frame.evaluate("""
                    () => {
                      const normalize = value => String(value || "")
                        .replace(/\\u00a0/g, " ")
                        .replace(/\\s+/g, " ")
                        .trim()
                        .toLowerCase();
                      const text = normalize((document.body && document.body.innerText)
                        || document.documentElement?.innerText
                        || "");
                      const controls = Array.from(document.querySelectorAll("input, button, a"));
                      const hasFilterControl = controls.some(element => {
                        const label = normalize(element.value
                          || element.innerText
                          || element.textContent
                          || element.getAttribute("aria-label")
                          || element.title
                          || element.name);
                        const onclick = normalize(element.getAttribute("onclick"));
                        return label === "filter"
                          || (label.includes("filter") && onclick.includes("filter"));
                      });

                      return hasFilterControl
                        || document.querySelector("a[href*='load_details']") !== null
                        || text.includes("incoming load")
                        || text.includes("load number")
                        || text.includes("status name")
                        || text.includes("arrival appointment")
                        || text.includes("no data")
                        || text.includes("no records");
                    }
                    """);
            return Boolean.TRUE.equals(value);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private boolean isOrdersFrame(Frame frame) {
        if (frame == null) {
            return false;
        }

        String url = frame.url();
        if (url == null) {
            return false;
        }

        String normalizedUrl = url.toLowerCase(Locale.ROOT);
        return frame.parentFrame() != null
                && (normalizedUrl.contains(OMSR_ORDER_FRAME_URL_MARKER)
                || normalizedUrl.contains("logistics_t"));
    }

    private String frameUrlSummary(Page page) {
        StringBuilder summary = new StringBuilder();
        for (Frame frame : page.frames()) {
            if (summary.length() > 0) {
                summary.append(System.lineSeparator());
            }
            summary.append(frame.url());
        }
        return summary.toString();
    }

    private String iframeSummary(Page page) {
        try {
            Object value = page.evaluate("""
                    () => Array.from(document.querySelectorAll("iframe")).map((iframe, index) => {
                      const id = iframe.id || "";
                      const name = iframe.name || "";
                      const src = iframe.getAttribute("src") || "";
                      return `iframe[${index}] id=${id} name=${name} src=${src}`.trim();
                    }).join("\\n")
                    """);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private synchronized void setOmsrTokenIfPresent(String token) {
        if ((omsrToken == null || omsrToken.isBlank()) && token != null && !token.isBlank()) {
            omsrToken = token;
        }
    }

    private boolean looksLikeDateValue(String value) {
        if (value == null) {
            return false;
        }

        String trimmed = value.trim();
        return trimmed.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?")
                || trimmed.matches("\\d{1,2}/\\d{1,2}/\\d{4}([ T].*)?")
                || trimmed.matches("\\d{4}/\\d{1,2}/\\d{1,2}([ T].*)?")
                || trimmed.matches("[A-Za-z]{3,9}\\s+\\d{1,2},\\s+\\d{4}.*")
                || trimmed.matches("(?i)^(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:,\\s*\\d{4})?(?:\\s+at\\s+.*)?$")
                || trimmed.matches("(?i)^\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?(?:\\s+[A-Z]{2,4})?$");
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String valueFor(Map<String, String> attributes, String... labels) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }

        for (String label : labels) {
            String normalizedLabel = normalizeKey(label);
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String normalizedEntry = normalizeKey(entry.getKey());
                if (normalizedEntry.equals(normalizedLabel)
                        || normalizedEntry.contains(normalizedLabel)
                        || normalizedLabel.contains(normalizedEntry)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    private List<String> poNumbersFromAttributes(Map<String, String> attributes) {
        List<String> result = new ArrayList<>();
        if (attributes == null || attributes.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!isPoLabel(normalizeKey(entry.getKey()))) {
                continue;
            }

            List<String> candidates = splitMultipleValues(entry.getValue());
            if (candidates.isEmpty()) {
                candidates = List.of(entry.getValue());
            }

            candidates.forEach(candidate -> addPoNumberCandidate(result, candidate));
        }

        return result;
    }

    private List<String> poNumbersFromText(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = normalizeWhitespace(lines[i]);
            if (line == null || line.isBlank()) {
                continue;
            }

            Matcher inlineMatcher = PO_NUMBER_INLINE_PATTERN.matcher(line);
            if (inlineMatcher.matches()) {
                addPoNumberCandidate(result, inlineMatcher.group(1));
                continue;
            }

            if (!PO_NUMBER_LABEL_PATTERN.matcher(line).matches()) {
                continue;
            }

            for (int j = i + 1; j < lines.length; j++) {
                String candidate = normalizeWhitespace(lines[j]);
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                addPoNumberCandidate(result, candidate);
                break;
            }
        }

        return result;
    }

    private void addPoNumberCandidate(List<String> values, String candidate) {
        String normalized = extractPoNumberCandidate(candidate);
        if (normalized == null || normalized.isBlank() || values.contains(normalized)) {
            return;
        }
        values.add(normalized);
    }

    private String extractPoNumberCandidate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalizedText = normalizeWhitespace(text);
        if (normalizedText == null || normalizedText.isBlank()) {
            return null;
        }

        String withoutDates = removeDateLikeFragments(normalizedText);
        if (withoutDates.isBlank()) {
            return null;
        }

        Matcher matcher = PO_NUMBER_VALUE_PATTERN.matcher(withoutDates);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String removeDateLikeFragments(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b", " ")
                .replaceAll("\\b\\d{4}/\\d{1,2}/\\d{1,2}\\b", " ")
                .replaceAll("\\b\\d{4}-\\d{2}-\\d{2}(?:[T ][^\\s,;]*)?\\b", " ")
                .replaceAll("(?i)\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:,\\s*\\d{4})?\\b", " ");
    }

    private boolean isPoLabel(String normalizedKey) {
        return normalizedKey.contains("ponumber")
                || normalizedKey.contains("pono")
                || normalizedKey.contains("purchaseordernumber")
                || normalizedKey.contains("purchaseorderno")
                || normalizedKey.contains("purchaseorder");
    }

    private void addUniqueValues(List<String> target, List<String> values) {
        if (target == null || values == null || values.isEmpty()) {
            return;
        }

        for (String value : values) {
            String normalized = normalizeWhitespace(value);
            if (normalized == null || normalized.isBlank() || target.contains(normalized)) {
                continue;
            }
            target.add(normalized);
        }
    }

    private void addUniquePoNumbers(List<String> target, List<String> values) {
        if (target == null || values == null || values.isEmpty()) {
            return;
        }
        values.forEach(value -> addPoNumberCandidate(target, value));
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.replaceAll("[^0-9\\-]", "");
        if (normalized.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeSequenceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.US);
        if (normalized.contains("PICK")) {
            return "PICK";
        }
        if (normalized.contains("DROP")) {
            return "DROP";
        }
        return normalized;
    }

    private List<String> splitMultipleValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("(?:\\R|[,;])+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<String> splitPoNumberValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<String> poNumbers = new ArrayList<>();
        splitMultipleValues(value).forEach(candidate -> addPoNumberCandidate(poNumbers, candidate));
        return List.copyOf(poNumbers);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for browser navigation.", e);
        }
    }

    private void close(BrowserContext context, Browser browser, Playwright playwright) {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private static final class AddressParts {
        private final String street;
        private final String city;
        private final String state;
        private final String zip;

        private AddressParts(String street, String city, String state, String zip) {
            this.street = street;
            this.city = city;
            this.state = state;
            this.zip = zip;
        }
    }

    private static final class PopupCapture {
        private final String text;
        private final Map<String, String> attributes;

        private PopupCapture(String text, Map<String, String> attributes) {
            this.text = text;
            this.attributes = attributes;
        }
    }

    private static final class PopupTarget {
        private final String type;
        private final String id;
        private final String url;

        private PopupTarget(String type, String id, String url) {
            this.type = type;
            this.id = id;
            this.url = url;
        }
    }

    private static final class PopupPattern {
        private final String type;
        private final Pattern pattern;

        private PopupPattern(String type, Pattern pattern) {
            this.type = type;
            this.pattern = pattern;
        }
    }
}
