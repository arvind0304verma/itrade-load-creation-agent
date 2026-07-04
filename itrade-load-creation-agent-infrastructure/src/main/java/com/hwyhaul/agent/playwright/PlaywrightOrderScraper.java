package com.hwyhaul.agent.playwright;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hwyhaul.agent.config.AgentBrowserConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class PlaywrightOrderScraper {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightOrderScraper.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final double DEFAULT_TIMEOUT_MS = 60_000;
    private static final double ROW_WAIT_TIMEOUT_MS = 10_000;
    private static final int MAX_LOADS_FROM_FIRST_SCREEN = 10;

    // Login form selectors. The email field is matched against a few common
    // variants (not just input[type='email']) so a minor markup change on the
    // auth page does not silently time out; the first DOM match is used.
    private static final String EMAIL_SELECTOR =
            "input[type='email'], input[name='email'], input[id='email'], input[name='username'], input[name='userName']";
    private static final String PASSWORD_SELECTOR =
            "input[type='password'], input[name='password'], input[id='password']";
    private static final String SUBMIT_SELECTOR = "button[type='submit']";
    private final AgentBrowserConfig browserConfig;
    private String xHhToken;

    public PlaywrightOrderScraper(AgentBrowserConfig browserConfig) {
        this.browserConfig = browserConfig;
    }

    public String loginAndGetToken() {
        xHhToken = null;

        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true)
        );
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("en-US")
                .setExtraHTTPHeaders(Map.of("accept-language", "en-US,en;q=0.9")));
        Page page = context.newPage();
        page.onResponse(this::captureXHhTokenFromResponse);
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);

        try {
            authenticate(page, context);
            if (xHhToken == null || xHhToken.isBlank()) {
                throw new IllegalStateException("HwyHaul login completed without capturing an x-hh-token.");
            }
            return xHhToken;
        } finally {
            close(context, browser, playwright);
        }
    }

    public List<OrderRow> scrapeOrders() {
        xHhToken = null;

        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true)
        );
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("en-US")
                .setExtraHTTPHeaders(Map.of("accept-language", "en-US,en;q=0.9")));
        Page page = context.newPage();
        page.onResponse(this::captureXHhTokenFromResponse);
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);

        try {
            authenticate(page, context);

            goToOrdersScreen(page);

            List<OrderRow> list = new ArrayList<>();
            Locator rows = orderRows(page);
            int rowCount = rows.count();
            if (rowCount == 0) {
                if (hasNoDataPlaceholder(page)) {
                    captureDebugSnapshot(page, "orders-no-data");
                    log.debug("Orders page returned no data; returning an empty order list.");
                    return list;
                }
                captureDebugSnapshot(page, "orders-rows-not-found");
                throw new IllegalStateException("No order rows found on orders page.");
            }

            int limit = Math.min(rowCount, MAX_LOADS_FROM_FIRST_SCREEN);
            for (int index = 0; index < limit; index++) {
                Locator row = rows.nth(index);
                OrderRow order = extractOrder(row);
                if (order.orderNo == null || order.orderNo.isBlank()) {
                    continue;
                }
                list.add(order);
            }

            logExtractedOrders(page, list);

            return list;
        } finally {
            close(context, browser, playwright);
        }
    }

    public String getXHhToken() {
        return xHhToken;
    }

    private void authenticate(Page page, BrowserContext context) {
        page.navigate(browserConfig.getAuthUrl());
        try {
            page.locator(EMAIL_SELECTOR).first().fill(browserConfig.getUsername());
            page.locator(PASSWORD_SELECTOR).first().fill(browserConfig.getPassword());
            page.locator(SUBMIT_SELECTOR).first().click();
        } catch (PlaywrightException e) {
            captureDebugSnapshot(page, "auth-login-form-not-found");
            log.error("HwyHaul login form interaction failed. authUrl={}, landedUrl={}, title='{}'. "
                            + "The login field was not found within the timeout. Inspect "
                            + "target/playwright-debug/auth-login-form-not-found.(png|html|txt) to see the actual page "
                            + "(possible redirect/SSO, iframe, or changed selectors).",
                    browserConfig.getAuthUrl(), safeUrl(page), safeTitle(page), e);
            throw e;
        }
        waitForAuthenticatedPage(page);

        String authenticationState = collectAuthenticationState(context, page, "spring-auth-state");
        setXHhTokenIfPresent(extractXHhTokenFromJson(authenticationState));

        String sessionStorageState = collectSessionStorageState(page, "spring-session-storage");
        setXHhTokenIfPresent(extractXHhTokenFromJson(sessionStorageState));

        log.debug("Login completed and authentication state collected. Current URL: {}, {}, sessionStorageEntries={}, xHhTokenPresent={}",
                page.url(), authenticationStateSummary(authenticationState), sessionStorageEntryCount(sessionStorageState),
                xHhToken != null && !xHhToken.isBlank());
    }

    private OrderRow extractOrder(Locator row) {
        List<Locator> cells = dataCells(row);
        OrderRow o = new OrderRow();

        if (cells.size() >= 10) {
            List<String> orderLines = textLines(cells.get(0));
            o.orderNo = valueAt(orderLines, 0);
            o.customerName = valueAt(orderLines, 1);
            o.shipDate = text(cells.get(1));
            o.deliveryDate = text(cells.get(2));

            List<String> pickupLines = textLines(cells.get(3));
            o.pickupLocation = valueAt(pickupLines, 0);
            o.pickupDateTime = valueAt(pickupLines, 1);

            List<String> dropoffLines = textLines(cells.get(4));
            o.dropoffLocation = valueAt(dropoffLines, 0);
            o.dropoffDateTime = valueAt(dropoffLines, 1);

            o.commodity = text(cells.get(5));
            o.palletCount = text(cells.get(6));
            o.weight = text(cells.get(7));
            o.poNumbers = splitCommaSeparated(text(cells.get(8)));
            o.status = text(cells.get(9));
        } else {
            o.orderNo = text(row.locator("[data-testid='order-no'], .order-no-cell, .order-id-cell").first());
            o.customerName = text(row.locator("[data-testid='customer-name'], .customer-name-cell").first());
            o.shipDate = text(row.locator("[data-testid='ship-date'], .ship-date-cell").first());
            o.deliveryDate = text(row.locator("[data-testid='delivery-date'], .delivery-date-cell").first());
            o.pickupLocation = text(row.locator("[data-testid='pickup-location'], .pickup-location-cell, .pickup-cell").first());
            o.pickupDateTime = text(row.locator("[data-testid='pickup-datetime'], .pickup-datetime-cell, .pickup-date-cell").first());
            o.dropoffLocation = text(row.locator("[data-testid='dropoff-location'], .dropoff-location-cell, .dropoff-cell").first());
            o.dropoffDateTime = text(row.locator("[data-testid='dropoff-datetime'], .dropoff-datetime-cell, .dropoff-date-cell").first());
            o.commodity = text(row.locator("[data-testid='commodity'], .commodity-cell").first());
            o.palletCount = text(row.locator("[data-testid='pallet-count'], .pallet-count-cell").first());
            o.weight = text(row.locator("[data-testid='weight'], .weight-cell").first());
            o.poNumbers = splitCommaSeparated(text(row.locator("[data-testid='po-no'], .po-no-cell").first()));
            o.status = text(row.locator("[data-testid='status'], .status-cell").first());
        }

        return o;
    }

    private void selectRow(Locator row) {
        Locator checkbox = row.locator("input[type='checkbox']").first();
        if (checkbox.count() > 0) {
            checkbox.check();
            return;
        }

        Locator checkboxWrapper = row.locator(".ant-checkbox, .ant-checkbox-wrapper").first();
        if (checkboxWrapper.count() > 0) {
            checkboxWrapper.click();
        }
    }

    private Locator orderRows(Page page) {
        Locator primaryRows = page.locator(browserConfig.getOrderRowSelector());
        if (primaryRows.count() > 0) {
            return primaryRows;
        }
        return page.locator("[role='row']").filter(new Locator.FilterOptions().setHas(page.locator("td, [role='cell']")));
    }

    private List<Locator> dataCells(Locator row) {
        Locator cells = row.locator("td, [role='cell']");
        List<Locator> result = new ArrayList<>();

        for (int i = 0; i < cells.count(); i++) {
            Locator cell = cells.nth(i);
            if (i == 0 && cell.locator("input[type='checkbox'], .ant-checkbox").count() > 0) {
                continue;
            }
            result.add(cell);
        }

        return result;
    }

    private void logExtractedOrders(Page page, List<OrderRow> orders) {
        try {
            log.debug("Extracted {} orders from {}: {}", orders.size(), page.url(), mapper.writeValueAsString(orders));
        } catch (Exception e) {
            log.debug("Extracted {} orders from {} but could not serialize order debug payload", orders.size(), page.url(), e);
        }
    }

    private String text(Locator locator) {
        if (locator.count() == 0) {
            return null;
        }
        return locator.innerText().trim().replaceAll("\\s+", " ");
    }

    private List<String> textLines(Locator locator) {
        if (locator.count() == 0) {
            return List.of();
        }
        return Arrays.stream(locator.innerText().split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String valueAt(List<String> values, int index) {
        return values.size() > index ? values.get(index) : null;
    }

    private List<String> splitCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private void goToOrdersScreen(Page page) {
        navigateToOrdersUrl(page);
        log.debug("Navigated to orders page. Current URL: {}", page.url());
        if (isAuthPage(page)) {
            captureDebugSnapshot(page, "orders-redirected-to-auth");
            throw new IllegalStateException("Orders page redirected to auth. Login did not establish an authenticated session.");
        }
        waitForOptionalRows(page);
    }

    private void navigateToOrdersUrl(Page page) {
        PlaywrightException lastNavigationError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.navigate(
                        browserConfig.getOrdersUrl(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
                );
                waitForOrdersOrAuthUrl(page, DEFAULT_TIMEOUT_MS);
                return;
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }

                lastNavigationError = e;
                log.debug("Orders navigation hit a transient browser error on attempt {}. Current URL: {}",
                        attempt, page.url());
                if (waitForOrdersOrAuthUrl(page, 10_000)) {
                    return;
                }
            }
        }

        captureDebugSnapshot(page, "orders-navigation-failed");
        if (lastNavigationError != null) {
            throw lastNavigationError;
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

    private void waitForAuthenticatedPage(Page page) {
        page.waitForFunction(
                "() => !window.location.pathname.includes('/auth')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(DEFAULT_TIMEOUT_MS)
        );
    }

    private String collectAuthenticationState(BrowserContext context, Page page, String name) {
        try {
            page.waitForLoadState();
            String storageState = context.storageState();

            Path debugDir = Path.of("target", "playwright-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve(name + ".json"), storageState);

            log.debug("Authentication state collected from {}: {}", page.url(), authenticationStateSummary(storageState));
            return storageState;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to collect authentication state after login.", e);
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

            Path debugDir = Path.of("target", "playwright-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve(name + ".json"), sessionStorageState);

            log.debug("Session storage collected from {}: entries={}", page.url(), mapper.readTree(sessionStorageState).size());
            return sessionStorageState;
        } catch (Exception e) {
            log.debug("Unable to collect session storage after login from {}: {}", page.url(), e.getMessage());
            return "{}";
        }
    }

    private void captureXHhTokenFromResponse(Response response) {
        try {
            String headerToken = headerValue(response.headers(), "x-hh-token");
            if (headerToken != null) {
                setXHhTokenIfPresent(headerToken);
                return;
            }

            String contentType = headerValue(response.headers(), "content-type");
            if (contentType == null || !contentType.toLowerCase().contains("json")) {
                return;
            }

            String responseText = response.text();
            setXHhTokenIfPresent(extractXHhTokenFromJson(responseText));
        } catch (Exception ignored) {
            // Some browser responses do not expose a readable body; token extraction is best-effort per response.
        }
    }

    private String extractXHhTokenFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return findTokenValue(mapper.readTree(json));
        } catch (Exception e) {
            return null;
        }
    }

    private String findTokenValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedName = field.getKey().replace("-", "").toLowerCase();
                JsonNode value = field.getValue();

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
                    String nestedToken = extractXHhTokenFromJson(value.asText());
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
            for (JsonNode item : node) {
                String nestedToken = findTokenValue(item);
                if (nestedToken != null) {
                    return nestedToken;
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

    private void setXHhTokenIfPresent(String token) {
        if ((xHhToken == null || xHhToken.isBlank()) && token != null && !token.isBlank()) {
            xHhToken = token;
        }
    }

    private String authenticationStateSummary(String storageState) {
        try {
            var root = mapper.readTree(storageState);
            return "cookies=" + root.path("cookies").size() + ", origins=" + root.path("origins").size();
        } catch (Exception e) {
            return "authenticationStateSummary=unavailable";
        }
    }

    private int sessionStorageEntryCount(String sessionStorageState) {
        try {
            return mapper.readTree(sessionStorageState).size();
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isAuthPage(Page page) {
        return page.url().contains("/auth");
    }

    private boolean isOrdersPage(Page page) {
        return page.url().contains("/orders");
    }

    private boolean isTransientNavigationError(PlaywrightException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("net::ERR_ABORTED")
                || message.contains("Execution context was destroyed"));
    }

    private boolean hasNoDataPlaceholder(Page page) {
        Object value = page.evaluate("""
                (selector) => {
                  const text = document.body.innerText || "";
                  const rows = document.querySelectorAll(selector).length;
                  return rows === 0 && (
                    text.includes("No data")
                    || document.querySelector(".ant-empty-description")?.innerText?.trim() === "No data"
                    || document.querySelector(".ant-table-placeholder") !== null
                  );
                }
                """, browserConfig.getOrderRowSelector());

        return Boolean.TRUE.equals(value);
    }

    private void waitForOptionalRows(Page page) {
        try {
            page.waitForFunction(
                    """
                    (selector) => {
                      const rows = document.querySelectorAll(selector).length;
                      const loading = document.querySelector(".ant-spin-spinning, .ant-spin-blur") !== null;
                      const noData = !loading && (document.body.innerText.includes("No data") || document.querySelector(".ant-empty-description"));
                      return rows > 0 || noData;
                    }
                    """,
                    browserConfig.getOrderRowSelector(),
                    new Page.WaitForFunctionOptions().setTimeout(ROW_WAIT_TIMEOUT_MS)
            );
        } catch (PlaywrightException e) {
            captureDebugSnapshot(page, "orders-rows-not-found");
        }
    }

    private void captureDebugSnapshot(Page page, String name) {
        try {
            Path debugDir = Path.of("target", "playwright-debug");
            Files.createDirectories(debugDir);
            page.screenshot(new Page.ScreenshotOptions().setPath(debugDir.resolve(name + ".png")).setFullPage(true));
            Files.writeString(debugDir.resolve(name + ".html"), page.content());
            Files.writeString(debugDir.resolve(name + ".txt"),
                    "url=" + page.url() + System.lineSeparator()
                            + "title=" + page.title() + System.lineSeparator()
                            + "missingSelector=" + browserConfig.getOrderRowSelector() + System.lineSeparator());
        } catch (Exception ignored) {
            // Debug artifacts are best-effort; scraping should not fail because diagnostics failed.
        }
    }

    private String safeUrl(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    private String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return "<unavailable>";
        }
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
}
