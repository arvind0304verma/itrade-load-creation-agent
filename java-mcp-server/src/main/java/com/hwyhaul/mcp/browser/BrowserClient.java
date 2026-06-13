package com.hwyhaul.mcp.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class BrowserClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final double DEFAULT_TIMEOUT_MS = 60_000;
    private static final double ROW_WAIT_TIMEOUT_MS = 30_000;
    private static final int MAX_LOADS_FROM_FIRST_SCREEN = 10;
    private static final String DEFAULT_AUTH_URL = "https://qa.ops.hwyhaul.com/auth";
    private static final String DEFAULT_ORDERS_URL =
            "https://qa.ops.hwyhaul.com/orders?activeTab=OPEN_ORDERS&status=ORDER_CREATED&sort=shipDate,asc&type=SALES";
    private static final String DEFAULT_ORDER_ROW_SELECTOR =
            "[data-testid='orders-table-row'], .ant-table-tbody > tr.ant-table-row:not([aria-hidden='true']), table tbody tr[data-row-key]";

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private String xHhToken;
    private final List<JsonNode> capturedJsonResponses = new CopyOnWriteArrayList<>();
    private final List<String> capturedJsonResponseDebug = new CopyOnWriteArrayList<>();
    private final String authUrl = configValue("MCP_AUTH_URL", DEFAULT_AUTH_URL);
    private final String ordersUrl = configValue("MCP_ORDERS_URL", DEFAULT_ORDERS_URL);
    private final String username = configValue("MCP_USERNAME", "superadmin.user1@hwyhaul.com");
    private final String password = configValue("MCP_PASSWORD", "password");
    private final String orderRowSelector = configValue("MCP_ORDER_ROW_SELECTOR", DEFAULT_ORDER_ROW_SELECTOR);

    public void init() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true)
        );
        context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("en-US")
                .setExtraHTTPHeaders(Map.of("accept-language", "en-US,en;q=0.9")));
        page = context.newPage();
        page.onResponse(this::captureXHhTokenFromResponse);
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
    }

    public void login() {
        try {
            page.navigate(authUrl);
            page.locator("input[type='email']").fill(username);
            page.locator("input[type='password']").fill(password);
            page.locator("button[type='submit']").click();
            waitForAuthenticatedPage();
            String authenticationState = collectAuthenticationState("mcp-auth-state");
            String sessionStorageState = collectSessionStorageState("mcp-session-storage");
            System.err.println("DEBUG Login completed and authentication state collected. Current URL: "
                    + page.url() + ", " + authenticationStateSummary(authenticationState)
                    + ", sessionStorageEntries=" + sessionStorageEntryCount(sessionStorageState)
                    + ", xHhTokenPresent=" + (xHhToken != null && !xHhToken.isBlank()));
        } catch (PlaywrightException e) {
            captureDebugSnapshot("login-failed", "authenticated page after login");
            throw e;
        }
    }

    public String getXHhToken() {
        return xHhToken;
    }

    public void goToOrdersScreen() {
        navigateToOrdersUrl();
        System.err.println("DEBUG Navigated to orders page. Current URL: " + page.url());
        if (isAuthPage()) {
            captureDebugSnapshot("orders-redirected-to-auth", ordersUrl);
            throw new IllegalStateException("Orders page redirected to auth. Login did not establish an authenticated session.");
        }
        waitForOptionalRows();
    }

    public List<OrderRow> extractOrders() {
        List<OrderRow> list = new ArrayList<>();
        Locator rows = page.locator(orderRowSelector);
        int rowCount = rows.count();
        if (rowCount == 0) {
            if (hasNoDataPlaceholder()) {
                captureDebugSnapshot("orders-no-data", "orders table data rows");
                System.err.println("DEBUG Orders page returned no data; creating an empty load payload.");
                return list;
            }
            captureDebugSnapshot("orders-rows-not-found", "first order row");
            throw new IllegalStateException("No order rows found on orders page.");
        }

        int limit = Math.min(rowCount, MAX_LOADS_FROM_FIRST_SCREEN);
        for (int index = 0; index < limit; index++) {
            Locator row = rows.nth(index);
            List<String> rowCellTexts = rowCellTexts(row);
            if (rowCellTexts.size() < 10) {
                continue;
            }

            OrderRow order = extractOrder(rowCellTexts);
            if (order.orderNo == null || order.orderNo.isBlank()) {
                continue;
            }

            openOrderDetailPage(row, order.orderNo);
            enrichOrderFromDetailPage(order);
            list.add(order);
            navigateToOrdersUrl();
            waitForOptionalRows();
            rows = page.locator(orderRowSelector);
        }

        logExtractedOrders(list);

        return list;
    }

    private OrderRow extractOrder(List<String> cells) {
        OrderRow o = new OrderRow();

        if (cells.size() >= 10) {
            List<String> orderLines = textLines(cells.get(0));
            o.orderNo = valueAt(orderLines, 0);
            o.customerName = valueAt(orderLines, 1);
            o.shipDate = normalize(cells.get(1));
            o.deliveryDate = normalize(cells.get(2));

            List<String> pickupLines = textLines(cells.get(3));
            o.pickupLocation = valueAt(pickupLines, 0);
            o.pickupDateTime = valueAt(pickupLines, 1);

            List<String> dropoffLines = textLines(cells.get(4));
            o.dropoffLocation = valueAt(dropoffLines, 0);
            o.dropoffDateTime = valueAt(dropoffLines, 1);

            o.commodity = normalize(cells.get(5));
            o.palletCount = normalize(cells.get(6));
            o.weight = normalize(cells.get(7));
            o.poNumbers = splitCommaSeparated(normalize(cells.get(8)));
            o.status = normalize(cells.get(9));
        } else {
            throw new IllegalStateException("First order row does not contain the expected 10 data columns. Columns found: " + cells.size());
        }

        return o;
    }

    private void logExtractedOrders(List<OrderRow> orders) {
        try {
            System.err.println("DEBUG Extracted " + orders.size() + " orders from " + page.url() + ": "
                    + mapper.writeValueAsString(orders));
        } catch (Exception e) {
            System.err.println("DEBUG Extracted " + orders.size() + " orders from " + page.url()
                    + " but could not serialize order debug payload: " + e.getMessage());
        }
    }

    private void openOrderDetailPage(Locator row, String orderNo) {
        String beforeUrl = page.url();
        capturedJsonResponses.clear();
        capturedJsonResponseDebug.clear();

        Locator detailLink = row.locator("a[href*='/orders/']").first();
        if (detailLink.count() == 0) {
            captureDebugSnapshot("order-detail-click-failed", orderRowSelector);
            throw new IllegalStateException("Unable to open order detail page for order " + orderNo + ". Detail link was not found.");
        }

        clickDetailLink(detailLink);
        if (!waitForDetailPage(beforeUrl)) {
            captureDebugSnapshot("order-detail-not-loaded", "order detail page");
            writeCapturedJsonResponses("order-detail-responses");
            throw new IllegalStateException("Order detail page did not load detail content. Current URL: " + page.url());
        }
        if (isUnavailablePage()) {
            captureDebugSnapshot("order-detail-unavailable", "order detail page");
            throw new IllegalStateException("Order detail page opened to an unavailable-page error. Current URL: " + page.url());
        }
        captureDebugSnapshot("order-detail-page", "order detail page");
        writeCapturedJsonResponses("order-detail-responses");
    }

    private void clickDetailLink(Locator detailLink) {
        PlaywrightException lastNavigationError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                detailLink.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                return;
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }
                lastNavigationError = e;
                sleep(500);
            }
        }
        if (lastNavigationError != null) {
            throw lastNavigationError;
        }
    }

    private boolean waitForDetailPage(String beforeUrl) {
        long deadline = System.nanoTime() + (long) (DEFAULT_TIMEOUT_MS * 1_000_000);
        while (System.nanoTime() < deadline) {
            String currentUrl = page.url();
            if (hasCapturedOrderDetailResponse()
                    || (!currentUrl.equals(beforeUrl) && isOrderDetailPage() && isDetailContentLoaded())) {
                return true;
            }

            sleep(250);
        }
        return false;
    }

    private boolean isDetailContentLoaded() {
        try {
            Object result = evaluateWithNavigationRetry("""
                    () => {
                      const text = document.body.innerText || "";
                      const unavailable = text.includes("Sorry, this page isn't available")
                        || text.includes("page may have been removed");
                      const listPage = document.querySelectorAll("tbody tr.ant-table-row, tbody tr[data-row-key]").length > 1
                        || /\\b\\d+\\s*-\\s*\\d+\\s+of\\s+\\d+\\b/.test(text);
                      const poMarkers = /PO Number|PO No\\.?|Purchase Order/i.test(text);
                      return !unavailable && !listPage && poMarkers;
                    }
                    """, null);
            return Boolean.TRUE.equals(result);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichOrderFromDetailPage(OrderRow order) {
        Map<String, String> attributes = scrapeDetailAttributes();
        order.detailAttributes = attributes;

        order.customerName = firstNonBlank(valueFor(attributes, "customer", "customer name"), order.customerName);
        order.shipDate = firstNonBlank(valueFor(attributes, "ship date", "shipping date"), order.shipDate);
        order.deliveryDate = firstNonBlank(valueFor(attributes, "delivery date", "deliver date"), order.deliveryDate);
        order.commodity = firstNonBlank(valueFor(attributes, "commodity"), order.commodity);
        order.palletCount = firstNonBlank(valueFor(attributes, "pallet count", "pallets"), order.palletCount);
        order.weight = firstNonBlank(valueFor(attributes, "weight"), order.weight);

        List<String> poNumbers = poNumbersFromAttributes(attributes);
        if (!poNumbers.isEmpty()) {
            order.poNumbers = poNumbers;
        } else {
            String poNumber = valueFor(attributes, "po number", "po no", "purchase order number", "purchase order no");
            if (poNumber != null) {
                order.poNumbers = splitCommaSeparated(poNumber);
            }
        }

        order.pickupLocation = firstNonBlank(valueFor(attributes, "pickup location", "pickup", "origin"), order.pickupLocation);
        order.pickupDateTime = firstNonBlank(valueFor(attributes, "pickup date time", "pickup date", "pickup appointment"), order.pickupDateTime);
        order.pickupStreetAddress = firstNonBlank(valueFor(attributes,
                "pickup full address", "pickup address", "pickup street address", "origin full address", "origin address", "shipper address"),
                order.pickupStreetAddress);
        order.pickupCity = firstNonBlank(valueFor(attributes, "pickup city", "origin city"), order.pickupCity);
        order.pickupState = firstNonBlank(valueFor(attributes, "pickup state", "origin state"), order.pickupState);
        order.pickupZip = firstNonBlank(valueFor(attributes, "pickup zip", "pickup zipcode", "origin zip"), order.pickupZip);

        order.dropoffLocation = firstNonBlank(valueFor(attributes, "dropoff location", "drop off location", "delivery location", "destination"), order.dropoffLocation);
        order.dropoffDateTime = firstNonBlank(valueFor(attributes, "dropoff date time", "dropoff date", "delivery date time", "delivery appointment"), order.dropoffDateTime);
        order.dropoffStreetAddress = firstNonBlank(valueFor(attributes,
                "dropoff full address", "drop off full address", "delivery full address", "destination full address",
                "dropoff address", "drop off address", "delivery address", "destination address", "receiver address"),
                order.dropoffStreetAddress);
        order.dropoffCity = firstNonBlank(valueFor(attributes, "dropoff city", "delivery city", "destination city"), order.dropoffCity);
        order.dropoffState = firstNonBlank(valueFor(attributes, "dropoff state", "delivery state", "destination state"), order.dropoffState);
        order.dropoffZip = firstNonBlank(valueFor(attributes, "dropoff zip", "delivery zip", "destination zip"), order.dropoffZip);

        enrichOrderFromCapturedJson(order);
        enrichFullAddressesFromPageText(order);
        splitLocationIntoCityState(order);
        backfillStreetAddressFromCityStateZip(order);

        System.err.println("DEBUG Detail attributes scraped for order " + order.orderNo + ": " + attributes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> scrapeDetailAttributes() {
        Object value = evaluateWithNavigationRetry("""
                () => {
                  const normalize = value => (value || "")
                    .replace(/\\s*\\/\\s*/g, " ")
                    .replace(/\\s+/g, " ")
                    .trim();
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
                    const dts = Array.from(dl.querySelectorAll("dt"));
                    dts.forEach(dt => put(dt.innerText, dt.nextElementSibling?.innerText));
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
                    put(label.includes("Pickup") || label.includes("Origin") ? "pickup full address" : "dropoff full address", el.innerText || el.value);
                  });

                  return attrs;
                }
                """, null);

        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, mapValue) -> {
                if (key != null && mapValue != null) {
                    result.put(String.valueOf(key), String.valueOf(mapValue));
                }
            });
        }
        return result;
    }

    private void navigateToOrdersUrl() {
        PlaywrightException lastNavigationError = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.navigate(
                        ordersUrl,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
                );
                waitForOrdersOrAuthUrl(DEFAULT_TIMEOUT_MS);
                return;
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }

                lastNavigationError = e;
                System.err.println("DEBUG Orders navigation hit a transient browser error on attempt "
                        + attempt + ". Current URL: " + page.url());
                if (waitForOrdersOrAuthUrl(10_000)) {
                    return;
                }
            }
        }

        captureDebugSnapshot("orders-navigation-failed", ordersUrl);
        if (lastNavigationError != null) {
            throw lastNavigationError;
        }
    }

    private boolean waitForOrdersOrAuthUrl(double timeoutMs) {
        long deadline = System.nanoTime() + (long) (timeoutMs * 1_000_000);
        while (System.nanoTime() < deadline) {
            if (isOrdersPage() || isAuthPage()) {
                return true;
            }
            sleep(100);
        }
        return false;
    }

    private List<String> rowCellTexts(Locator row) {
        Locator cells = row.locator("td, [role='cell']");
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < cells.count(); index++) {
            Locator cell = cells.nth(index);
            if (index == 0 && cell.locator("input[type='checkbox'], .ant-checkbox").count() > 0) {
                continue;
            }
            String text = normalize(cell.innerText());
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private Object evaluateWithNavigationRetry(String expression, Object arg) {
        PlaywrightException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return page.evaluate(expression, arg);
            } catch (PlaywrightException e) {
                if (!isTransientNavigationError(e)) {
                    throw e;
                }

                lastError = e;
                System.err.println("DEBUG Browser context changed during page evaluation on attempt "
                        + attempt + ". Current URL: " + page.url());
                waitForOrdersOrAuthUrl(5_000);
            }
        }
        throw lastError;
    }

    private List<String> textLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String valueAt(List<String> values, int index) {
        return values.size() > index ? values.get(index) : null;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ");
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

    private void waitForAuthenticatedPage() {
        page.waitForFunction(
                "() => !window.location.pathname.includes('/auth')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(DEFAULT_TIMEOUT_MS)
        );
    }

    private String collectAuthenticationState(String name) {
        try {
            page.waitForLoadState();
            String storageState = context.storageState();

            Path debugDir = Path.of("target", "playwright-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve(name + ".json"), storageState);
            setXHhTokenIfPresent(extractXHhTokenFromJson(storageState));

            System.err.println("DEBUG Authentication state collected from " + page.url() + ": "
                    + authenticationStateSummary(storageState));
            return storageState;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to collect authentication state after login.", e);
        }
    }

    private String collectSessionStorageState(String name) {
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
            setXHhTokenIfPresent(extractXHhTokenFromJson(sessionStorageState));

            System.err.println("DEBUG Session storage collected from " + page.url() + ": entries="
                    + mapper.readTree(sessionStorageState).size());
            return sessionStorageState;
        } catch (Exception e) {
            System.err.println("DEBUG Unable to collect session storage after login from " + page.url()
                    + ": " + e.getMessage());
            return "{}";
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
            captureJsonResponse(response.url(), responseText);
        } catch (Exception ignored) {
            // Some browser responses do not expose a readable body; token extraction is best-effort per response.
        }
    }

    private void captureJsonResponse(String responseUrl, String responseText) {
        try {
            JsonNode root = mapper.readTree(responseText);
            capturedJsonResponses.add(root);
            capturedJsonResponseDebug.add(mapper.writeValueAsString(Map.of(
                    "url", responseUrl == null ? "" : responseUrl,
                    "body", root
            )));
            if (capturedJsonResponses.size() > 50) {
                capturedJsonResponses.remove(0);
            }
            if (capturedJsonResponseDebug.size() > 50) {
                capturedJsonResponseDebug.remove(0);
            }
        } catch (Exception ignored) {
            // Not all JSON-looking responses are useful for detail extraction.
        }
    }

    private void writeCapturedJsonResponses(String name) {
        try {
            Path debugDir = Path.of("target", "playwright-debug");
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve(name + ".json"),
                    "[" + String.join("," + System.lineSeparator(), capturedJsonResponseDebug) + "]");
        } catch (Exception ignored) {
            // Debug artifacts are best-effort.
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
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
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

    private void backfillPoNumbersFromRouteStops(OrderRow order) {
        if (order == null || order.routeStops == null || order.routeStops.isEmpty()) {
            return;
        }

        List<String> routePoNumbers = new ArrayList<>();
        for (OrderRow.RouteStop routeStop : order.routeStops) {
            if (routeStop == null || routeStop.poNumber == null || routeStop.poNumber.isBlank()) {
                continue;
            }

            String poNumber = routeStop.poNumber.trim();
            if (looksLikeDateValue(poNumber) || routePoNumbers.contains(poNumber)) {
                continue;
            }

            routePoNumbers.add(poNumber);
        }

        if (!routePoNumbers.isEmpty()) {
            order.poNumbers = routePoNumbers;
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

    private void enrichOrderFromCapturedJson(OrderRow order) {
        Optional<JsonNode> selectedOrderNode = capturedJsonResponses.stream()
                .filter(this::containsWaypoints)
                .findFirst();

        if (selectedOrderNode.isEmpty()) {
            selectedOrderNode = capturedJsonResponses.stream()
                    .filter(node -> containsTextValue(node, order.orderNo))
                    .findFirst();
        }

        if (selectedOrderNode.isEmpty()) {
            return;
        }

        JsonNode node = selectedOrderNode.get();
        order.shipperId = firstNonBlank(
                textByKey(node.path("company"), "shipperId"),
                textByKey(node, "shipperId"),
                order.shipperId);
        order.customerName = firstNonBlank(textByKey(node, "customerName", "customer"), order.customerName);
        order.commodity = firstNonBlank(textByKey(node, "commodityName", "commodity"), order.commodity);
        order.weight = firstNonBlank(textByKey(node, "weight", "totalWeight"), order.weight);
        order.palletCount = firstNonBlank(textByKey(node, "palletCount", "pallets"), order.palletCount);
        order.pickupAddressId = firstNonBlank(addressIdByStopType(node, "PICK"), order.pickupAddressId);
        order.dropoffAddressId = firstNonBlank(addressIdByStopType(node, "DROP"), order.dropoffAddressId);
        order.pickupLocation = preferStopValue(order.pickupLocation, addressFieldByStopType(node, "PICK", "singleLineAddress", "streetAddress", "addressLine2", "location", "name"));
        order.dropoffLocation = preferStopValue(order.dropoffLocation, addressFieldByStopType(node, "DROP", "singleLineAddress", "streetAddress", "addressLine2", "location", "name"));
        order.pickupStreetAddress = preferStopAddress(order.pickupStreetAddress, addressFieldByStopType(node, "PICK", "streetAddress", "addressLine2", "singleLineAddress"));
        order.dropoffStreetAddress = preferStopAddress(order.dropoffStreetAddress, addressFieldByStopType(node, "DROP", "streetAddress", "addressLine2", "singleLineAddress"));
        order.pickupCity = preferStopValue(order.pickupCity, addressFieldByStopType(node, "PICK", "city"));
        order.dropoffCity = preferStopValue(order.dropoffCity, addressFieldByStopType(node, "DROP", "city"));
        order.pickupState = preferStopValue(order.pickupState, addressFieldByStopType(node, "PICK", "state"));
        order.dropoffState = preferStopValue(order.dropoffState, addressFieldByStopType(node, "DROP", "state"));
        order.pickupZip = preferStopValue(order.pickupZip, addressFieldByStopType(node, "PICK", "zip"));
        order.dropoffZip = preferStopValue(order.dropoffZip, addressFieldByStopType(node, "DROP", "zip"));
        order.routeStops = routeStopsFromCapturedJson(node);
        backfillPoNumbersFromRouteStops(order);
    }

    private List<OrderRow.RouteStop> routeStopsFromCapturedJson(JsonNode node) {
        JsonNode waypoints = node == null ? null : node.path("waypoints");
        if (waypoints == null || !waypoints.isArray() || waypoints.isEmpty()) {
            return List.of();
        }

        List<OrderRow.RouteStop> routeStops = new ArrayList<>();
        for (JsonNode waypoint : waypoints) {
            OrderRow.RouteStop routeStop = new OrderRow.RouteStop();
            JsonNode address = waypoint.path("addressDTO");

            routeStop.orderSequenceNumber = parseInteger(textByKey(waypoint, "orderSequenceNumber"));
            routeStop.sequenceType = normalizeSequenceType(textByKey(waypoint, "sequenceType", "type", "stopType"));
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
            routeStop.pickUpNumber = textByKey(waypoint, "pickUpNumber", "pickupNumber");
            routeStop.dropOffNumber = textByKey(waypoint, "dropOffNumber", "dropoffNumber");
            routeStop.poNumber = textByKey(waypoint, "poNumber", "poNo", "po_number", "purchaseOrderNumber", "purchaseOrderNo");
            routeStop.weight = textByKey(waypoint, "weight", "grossWeight", "netWeight");
            routeStop.palletCount = textByKey(waypoint, "palletCount", "pallets");
            routeStop.caseCount = textByKey(waypoint, "caseCount", "cases");
            if (routeStop.sequenceType == null) {
                if (routeStop.pickUpNumber != null && routeStop.dropOffNumber == null) {
                    routeStop.sequenceType = "PICK";
                } else if (routeStop.dropOffNumber != null && routeStop.pickUpNumber == null) {
                    routeStop.sequenceType = "DROP";
                }
            }
            routeStop.earliestPickupDateTime = textByKey(waypoint, "earliestPickupDateTime");
            routeStop.latestPickupDateTime = textByKey(waypoint, "latestPickupDateTime");
            routeStop.earliestDropoffDateTime = textByKey(waypoint, "earliestDropoffDateTime");
            routeStop.latestDropoffDateTime = textByKey(waypoint, "latestDropoffDateTime");
            if (routeStop.sequenceType != null && routeStop.sequenceType.contains("PICK")) {
                routeStop.dateTime = firstNonBlank(routeStop.earliestPickupDateTime, routeStop.latestPickupDateTime);
            } else if (routeStop.sequenceType != null && routeStop.sequenceType.contains("DROP")) {
                routeStop.dateTime = firstNonBlank(routeStop.earliestDropoffDateTime, routeStop.latestDropoffDateTime);
            } else {
                routeStop.dateTime = firstNonBlank(routeStop.earliestPickupDateTime, routeStop.latestPickupDateTime);
                routeStop.dateTime = firstNonBlank(routeStop.dateTime, routeStop.earliestDropoffDateTime);
                routeStop.dateTime = firstNonBlank(routeStop.dateTime, routeStop.latestDropoffDateTime);
            }

            routeStops.add(routeStop);
        }

        routeStops.sort(Comparator.comparing(
                (OrderRow.RouteStop routeStop) -> routeStop.orderSequenceNumber,
                Comparator.nullsLast(Integer::compareTo)
        ));
        return routeStops;
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

    private void enrichFullAddressesFromPageText(OrderRow order) {
        Object value = evaluateWithNavigationRetry("""
                () => {
                  const normalize = value => (value || "")
                    .replace(/\\s*\\/\\s*/g, " ")
                    .replace(/\\s+/g, " ")
                    .trim();
                  const rawText = document.body.innerText || "";
                  const lines = rawText
                    .replace(/\u00A0/g, " ")
                    .replace(/\u202F/g, " ")
                    .replace(/\u2007/g, " ")
                    .split(/\r?\n/)
                    .map(normalize)
                    .filter(Boolean);

                  const addressPattern = /\\b\\d{1,8}\\s+[A-Za-z0-9][A-Za-z0-9 .#&'\\-]+(?:,\\s*|\\s+)[A-Za-z .'-]+(?:,\\s*|\\s+)[A-Z]{2}(?:\\s+\\d{5}(?:-\\d{4})?)?(?:\\s*,?\\s*(?:United States|USA|US))?\\b/i;
                  const result = {};
                  const genericAddressBlocks = [];

                  for (let index = 0; index < lines.length; index++) {
                    const line = lines[index];
                    const match = line.match(addressPattern);
                    if (match) {
                      if (/Pickup|Origin|Shipper/i.test(line) && !result.pickup) result.pickup = match[0];
                      if (/Dropoff|Drop Off|Destination|Receiver|Delivery/i.test(line) && !result.dropoff) result.dropoff = match[0];
                    }
                    if (/^(Address|Location|Street)\\b/i.test(line)) {
                      const block = lines.slice(index, index + 4).join(" ").replace(/^(Address|Location|Street)\\s*:?\\s*/i, "");
                      const blockMatch = block.match(addressPattern);
                      if (blockMatch && !genericAddressBlocks.includes(blockMatch[0])) {
                        genericAddressBlocks.push(blockMatch[0]);
                      }
                    }
                  }

                  if (!result.pickup && genericAddressBlocks[0]) {
                    result.pickup = genericAddressBlocks[0];
                  }
                  if (!result.dropoff && genericAddressBlocks[1]) {
                    result.dropoff = genericAddressBlocks[1];
                  }

                  if (!result.pickup || !result.dropoff) {
                    const all = Array.from(rawText.matchAll(new RegExp(addressPattern.source, "g"))).map(match => match[0]);
                    if (!result.pickup && all[0]) result.pickup = all[0];
                    if (!result.dropoff && all[1]) result.dropoff = all[1];
                  }

                  return result;
                }
                """, null);

        if (value instanceof Map<?, ?> addresses) {
            Object pickup = addresses.get("pickup");
            Object dropoff = addresses.get("dropoff");
            if (pickup != null && (order.pickupStreetAddress == null || order.pickupStreetAddress.isBlank() || !looksLikeAddress(order.pickupStreetAddress))) {
                order.pickupStreetAddress = String.valueOf(pickup);
            }
            if (dropoff != null && (order.dropoffStreetAddress == null || order.dropoffStreetAddress.isBlank() || !looksLikeAddress(order.dropoffStreetAddress))) {
                order.dropoffStreetAddress = String.valueOf(dropoff);
            }
        }
    }

    private boolean containsTextValue(JsonNode node, String expected) {
        if (node == null || expected == null || expected.isBlank()) {
            return false;
        }
        if (node.isTextual()) {
            return node.asText().contains(expected);
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                if (containsTextValue(fields.next().getValue(), expected)) {
                    return true;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsTextValue(item, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsWaypoints(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }

        if (node.isObject()) {
            JsonNode waypoints = node.get("waypoints");
            if (waypoints != null && waypoints.isArray() && waypoints.size() > 0) {
                return true;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                if (containsWaypoints(fields.next().getValue())) {
                    return true;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsWaypoints(item)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasCapturedOrderDetailResponse() {
        return capturedJsonResponses.stream().anyMatch(this::containsWaypoints);
    }

    private String textByKey(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                for (String key : keys) {
                    if (field.getKey().equalsIgnoreCase(key) && field.getValue().isValueNode()) {
                        return field.getValue().asText();
                    }
                }
                String nested = textByKey(field.getValue(), keys);
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

    private String addressIdByStopType(JsonNode node, String stopType) {
        JsonNode stop = stopNode(node, stopType);
        return stop == null ? null : textByKey(stop.path("addressDTO"), "id");
    }

    private String addressFieldByStopType(JsonNode node, String stopType, String... keys) {
        JsonNode stop = stopNode(node, stopType);
        if (stop == null) {
            return null;
        }
        String direct = textByKey(stop.path("addressDTO"), keys);
        return direct == null ? textByKey(stop.path("address"), keys) : direct;
    }

    private JsonNode stopNode(JsonNode node, String stopType) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            String sequenceType = directTextByKey(node, "sequenceType", "type", "stopType");
            if (sequenceType != null && sequenceType.toUpperCase().contains(stopType)) {
                return node;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                JsonNode nested = stopNode(fields.next().getValue(), stopType);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode nested = stopNode(item, stopType);
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
                return value.asText();
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String firstNonBlank(String first, String second, String third) {
        return first == null || first.isBlank() ? firstNonBlank(second, third) : first;
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

    private boolean looksLikeAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String normalized = normalizeWhitespace(value).replaceAll("\\s*/\\s*", " ");
        return normalized.matches("(?i)^\\d{1,8}\\s+.+\\b[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?(?:\\s+(?:United States|USA|US))?$")
                || normalized.matches("(?i)^\\d{1,8}\\s+.+,\\s+.+,\\s+[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?(?:\\s+(?:United States|USA|US))?$");
    }

    private String valueFor(Map<String, String> attributes, String... labels) {
        for (String label : labels) {
            String normalizedLabel = normalizeKey(label);
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (normalizeKey(entry.getKey()).contains(normalizedLabel)) {
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

            List<String> candidates = splitCommaSeparated(entry.getValue());
            if (candidates.isEmpty()) {
                candidates = List.of(entry.getValue());
            }

            for (String candidate : candidates) {
                String cleaned = normalizeWhitespace(candidate);
                if (cleaned == null || cleaned.isBlank() || looksLikeDateValue(cleaned) || result.contains(cleaned)) {
                    continue;
                }
                result.add(cleaned);
            }
        }

        return result;
    }

    private boolean isPoLabel(String normalizedKey) {
        return normalizedKey.contains("ponumber")
                || normalizedKey.contains("pono")
                || normalizedKey.contains("purchaseordernumber")
                || normalizedKey.contains("purchaseorderno")
                || normalizedKey.contains("purchaseorder");
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void splitLocationIntoCityState(OrderRow order) {
        if ((order.pickupCity == null || order.pickupState == null) && order.pickupLocation != null) {
            String[] parts = order.pickupLocation.split(",");
            if (parts.length >= 2) {
                order.pickupCity = firstNonBlank(order.pickupCity, parts[0].trim());
                order.pickupState = firstNonBlank(order.pickupState, parts[1].trim().split("\\s+")[0]);
            }
        }
        if ((order.dropoffCity == null || order.dropoffState == null) && order.dropoffLocation != null) {
            String[] parts = order.dropoffLocation.split(",");
            if (parts.length >= 2) {
                order.dropoffCity = firstNonBlank(order.dropoffCity, parts[0].trim());
                order.dropoffState = firstNonBlank(order.dropoffState, parts[1].trim().split("\\s+")[0]);
            }
        }
    }

    private void backfillStreetAddressFromCityStateZip(OrderRow order) {
        if (order == null) {
            return;
        }

        String pickupCityStateZip = joinCityStateZip(order.pickupCity, order.pickupState, order.pickupZip);
        if ((order.pickupStreetAddress == null || order.pickupStreetAddress.isBlank() || isPlaceholderStopValue(order.pickupStreetAddress))
                && pickupCityStateZip != null) {
            order.pickupStreetAddress = pickupCityStateZip;
        }

        String dropoffCityStateZip = joinCityStateZip(order.dropoffCity, order.dropoffState, order.dropoffZip);
        if ((order.dropoffStreetAddress == null || order.dropoffStreetAddress.isBlank() || isPlaceholderStopValue(order.dropoffStreetAddress))
                && dropoffCityStateZip != null) {
            order.dropoffStreetAddress = dropoffCityStateZip;
        }
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

    private boolean isAuthPage() {
        return page.url().contains("/auth");
    }

    private boolean isOrdersPage() {
        return page.url().contains("/orders");
    }

    private boolean isOrderDetailPage() {
        return page.url().matches(".*/orders/[^/?#]+.*");
    }

    private boolean isUnavailablePage() {
        try {
            Object result = evaluateWithNavigationRetry("""
                    () => {
                      const text = document.body.innerText || "";
                      return text.includes("Sorry, this page isn't available")
                        || text.includes("page may have been removed");
                    }
                    """, null);
            return Boolean.TRUE.equals(result);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private boolean isTransientNavigationError(PlaywrightException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("net::ERR_ABORTED")
                || message.contains("Execution context was destroyed"));
    }

    private boolean hasNoDataPlaceholder() {
        Object value = evaluateWithNavigationRetry("""
                (selector) => {
                  const text = document.body.innerText || "";
                  const rows = document.querySelectorAll(selector).length;
                  return rows === 0 && (
                    text.includes("No data")
                    || document.querySelector(".ant-empty-description")?.innerText?.trim() === "No data"
                    || document.querySelector(".ant-table-placeholder") !== null
                  );
                }
                """, orderRowSelector);

        return Boolean.TRUE.equals(value);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for browser navigation.", e);
        }
    }

    private void waitForOptionalRows() {
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
                    orderRowSelector,
                    new Page.WaitForFunctionOptions().setTimeout(ROW_WAIT_TIMEOUT_MS)
            );
        } catch (PlaywrightException e) {
            captureDebugSnapshot("orders-rows-not-found", orderRowSelector);
        }
    }

    private static String configValue(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void captureDebugSnapshot(String name, String waitingForSelector) {
        try {
            Path debugDir = Path.of("target", "playwright-debug");
            Files.createDirectories(debugDir);
            page.screenshot(new Page.ScreenshotOptions().setPath(debugDir.resolve(name + ".png")).setFullPage(true));
            Files.writeString(debugDir.resolve(name + ".html"), page.content());
            Files.writeString(debugDir.resolve(name + ".txt"),
                    "url=" + page.url() + System.lineSeparator()
                            + "title=" + page.title() + System.lineSeparator()
                            + "waitingForSelector=" + waitingForSelector + System.lineSeparator());
        } catch (Exception ignored) {
            // Debug artifacts are best-effort; scraping should not fail because diagnostics failed.
        }
    }

    public void close() {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }
}
