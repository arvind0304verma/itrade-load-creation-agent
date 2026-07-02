package com.hwyhaul.agent.playwright;

import com.hwyhaul.agent.config.OmsrBrowserConfig;
import com.hwyhaul.agent.model.CapturedOrdersPayload;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmsrPlaywrightLoadScraperTest {

    @Test
    void findLoadRowMatchesUsingTheLoadNumberColumnNotJustTheFirstCell() throws Exception {
        OmsrBrowserConfig config = new OmsrBrowserConfig();
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(config);

        Frame frame = mock(Frame.class);
        Locator rows = mock(Locator.class);
        Locator row = mock(Locator.class);
        Locator cells = mock(Locator.class);
        Locator firstCell = mock(Locator.class);
        Locator secondCell = mock(Locator.class);
        Locator firstCellCheckbox = mock(Locator.class);

        when(frame.locator(config.getLoadRowSelector())).thenReturn(rows);
        when(rows.count()).thenReturn(1);
        when(rows.nth(0)).thenReturn(row);

        when(row.locator("td, [role='cell']")).thenReturn(cells);
        when(cells.count()).thenReturn(2);
        when(cells.nth(0)).thenReturn(firstCell);
        when(cells.nth(1)).thenReturn(secondCell);

        when(firstCell.locator("input[type='checkbox'], .ant-checkbox")).thenReturn(firstCellCheckbox);
        when(firstCellCheckbox.count()).thenReturn(0);
        when(firstCell.count()).thenReturn(1);
        when(firstCell.innerText()).thenReturn("TRACKING-ONLY");

        when(secondCell.count()).thenReturn(1);
        when(secondCell.innerText()).thenReturn("34851996");

        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        headerIndex.put("Load Number", 1);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "findLoadRow",
                Frame.class,
                Map.class,
                String.class
        );
        method.setAccessible(true);

        Locator matchedRow = (Locator) method.invoke(scraper, frame, headerIndex, "34851996");

        assertSame(row, matchedRow);
    }

    @Test
    void findLoadRowRetriesUntilTheTargetRowAppears() throws Exception {
        OmsrBrowserConfig config = new OmsrBrowserConfig();
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(config);

        Frame frame = mock(Frame.class);
        Locator headers = mock(Locator.class);
        Locator rows = mock(Locator.class);
        Locator row = mock(Locator.class);
        Locator cells = mock(Locator.class);
        Locator firstCell = mock(Locator.class);
        Locator secondCell = mock(Locator.class);
        Locator firstCellCheckbox = mock(Locator.class);

        when(frame.locator("thead tr th, [role='columnheader']")).thenReturn(headers);
        when(headers.count()).thenReturn(0);
        when(frame.locator(config.getLoadRowSelector())).thenReturn(rows);
        when(rows.count()).thenReturn(0, 0, 1);
        when(rows.nth(0)).thenReturn(row);

        when(row.locator("td, [role='cell']")).thenReturn(cells);
        when(cells.count()).thenReturn(2);
        when(cells.nth(0)).thenReturn(firstCell);
        when(cells.nth(1)).thenReturn(secondCell);

        when(firstCell.locator("input[type='checkbox'], .ant-checkbox")).thenReturn(firstCellCheckbox);
        when(firstCellCheckbox.count()).thenReturn(0);
        when(firstCell.count()).thenReturn(1);
        when(firstCell.innerText()).thenReturn("TRACKING-ONLY");

        when(secondCell.count()).thenReturn(1);
        when(secondCell.innerText()).thenReturn("34851996");

        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        headerIndex.put("Load Number", 1);

        Locator matchedRow = invokeFindLoadRow(scraper, frame, headerIndex, "34851996");

        assertSame(row, matchedRow);
    }

    @Test
    void findLoadRowMatchesUsingTheRenderedRowTextWhenTheLoadNumberIsNotInTheFirstCell() throws Exception {
        OmsrBrowserConfig config = new OmsrBrowserConfig();
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(config);

        Frame frame = mock(Frame.class);
        Locator headers = mock(Locator.class);
        Locator rows = mock(Locator.class);
        Locator row = mock(Locator.class);
        Locator cells = mock(Locator.class);
        Locator firstCell = mock(Locator.class);
        Locator secondCell = mock(Locator.class);
        Locator firstCellCheckbox = mock(Locator.class);

        when(frame.locator("thead tr th, [role='columnheader']")).thenReturn(headers);
        when(headers.count()).thenReturn(0);
        when(frame.locator(config.getLoadRowSelector())).thenReturn(rows);
        when(rows.count()).thenReturn(1);
        when(rows.nth(0)).thenReturn(row);

        when(row.locator("td, [role='cell']")).thenReturn(cells);
        when(cells.count()).thenReturn(2);
        when(cells.nth(0)).thenReturn(firstCell);
        when(cells.nth(1)).thenReturn(secondCell);
        when(row.count()).thenReturn(1);
        when(row.innerText()).thenReturn("TRACKING-ONLY\n28886167");

        when(firstCell.locator("input[type='checkbox'], .ant-checkbox")).thenReturn(firstCellCheckbox);
        when(firstCellCheckbox.count()).thenReturn(0);
        when(firstCell.count()).thenReturn(1);
        when(firstCell.innerText()).thenReturn("TRACKING-ONLY");

        when(secondCell.count()).thenReturn(1);
        when(secondCell.innerText()).thenReturn("28886167");

        Locator matchedRow = invokeFindLoadRow(scraper, frame, new LinkedHashMap<>(), "28886167");

        assertSame(row, matchedRow);
    }

    @Test
    void extractLoginFailureMessageReturnsTheSiteErrorMessage() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Page page = mock(Page.class);
        when(page.evaluate(anyString()))
                .thenReturn("The username or password entered is incorrect. Please try again.");

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod("extractLoginFailureMessage", Page.class);
        method.setAccessible(true);

        String message = (String) method.invoke(scraper, page);

        assertEquals("The username or password entered is incorrect. Please try again.", message);
    }

    @Test
    void splitPoNumberValuesDropsDateValues() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod("splitPoNumberValues", String.class);
        method.setAccessible(true);

        assertEquals(List.of("10845348"), method.invoke(scraper, "06/09/2026\n10845348"));
        assertEquals(List.of("10845348"), method.invoke(scraper, "06/09/2026 10845348"));
    }

    @Test
    void applyShippingTotalsReadsInterleavedShippingSectionText() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder load =
                new com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                Total Quantity
                98 Pallets
                2.00 Cubes
                102.8794 Weight
                2940.000
                Receiving
                RW/H
                """;

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "applyShippingTotals",
                com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder.class,
                Map.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(scraper, load, new LinkedHashMap<String, String>(), bodyText);

        assertEquals("98", load.totalQuantity);
        assertEquals("98", load.caseCount);
        assertEquals("2.00", load.palletCount);
        assertEquals("102.8794", load.cubeCount);
        assertEquals("2940.000", load.weight);
    }

    @Test
    void applyShippingTotalsReadsSingleLineShippingSectionText() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                Total Quantity 98 Pallets 2.00 Cubes 102.8794 Weight 2940.000
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertEquals("98", load.totalQuantity);
        assertEquals("98", load.caseCount);
        assertEquals("2.00", load.palletCount);
        assertEquals("102.8794", load.cubeCount);
        assertEquals("2940.000", load.weight);
    }

    @Test
    void applyShippingTotalsReadsExplicitCasesAndPalletLabels() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                # of Cases 650 Total # of Pallets 9.75 Gross Weight 2940.000
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertNull(load.totalQuantity);
        assertEquals("650", load.caseCount);
        assertEquals("9.75", load.palletCount);
        assertEquals("2940.000", load.weight);
    }

    @Test
    void applyShippingTotalsReadsNumericWeightWithUnits() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                Weight
                2,940 lbs
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertEquals("2940", load.weight);
    }

    @Test
    void applyShippingTotalsDropsNonNumericSummaryFreightValues() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();
        load.weight = "Ground";
        load.palletCount = " ";
        String bodyText = """
                Load Details
                Shipping
                SW/H
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertNull(load.weight);
        assertNull(load.palletCount);
    }

    @Test
    void applyShippingTotalsIgnoresSequenceMarkerBeforeTotalQuantity() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder load =
                new com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                1 Total Quantity
                650 Pallets
                9.75 Cubes
                442.9014 Weight
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertEquals("650", load.totalQuantity);
        assertEquals("650", load.caseCount);
        assertEquals("9.75", load.palletCount);
        assertEquals("442.9014", load.cubeCount);
        assertNull(load.weight);
    }

    @Test
    void applyShippingTotalsIgnoresSequenceMarkerAndReadsTrailingWeight() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        CapturedOrdersPayload.CapturedOrder load = new CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                1 Total Quantity
                650 Pallets
                9.75 Cubes
                442.9014 Weight
                2940.000
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertEquals("650", load.totalQuantity);
        assertEquals("650", load.caseCount);
        assertEquals("9.75", load.palletCount);
        assertEquals("442.9014", load.cubeCount);
        assertEquals("2940.000", load.weight);
    }

    @Test
    void applyShippingTotalsRealignsMissingTotalQuantityLabel() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder load =
                new com.hwyhaul.agent.model.CapturedOrdersPayload.CapturedOrder();
        String bodyText = """
                Load Details
                Shipping
                SW/H
                650 Pallets
                9.75 Cubes
                442.9014 Weight
                Receiving
                RW/H
                """;

        invokeApplyShippingTotals(scraper, load, bodyText);

        assertEquals("650", load.totalQuantity);
        assertEquals("650", load.caseCount);
        assertEquals("9.75", load.palletCount);
        assertEquals("442.9014", load.cubeCount);
        assertNull(load.weight);
    }

    @Test
    void isConfirmedLoadStatusRequiresExactConfirmedStatus() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "isConfirmedLoadStatus",
                CapturedOrdersPayload.CapturedOrder.class
        );
        method.setAccessible(true);

        CapturedOrdersPayload.CapturedOrder confirmed = new CapturedOrdersPayload.CapturedOrder();
        confirmed.status = " Confirmed ";
        CapturedOrdersPayload.CapturedOrder bracketedConfirmed = new CapturedOrdersPayload.CapturedOrder();
        bracketedConfirmed.status = "[Confirmed -- S]";
        CapturedOrdersPayload.CapturedOrder closed = new CapturedOrdersPayload.CapturedOrder();
        closed.status = "Closed";

        assertTrue((Boolean) method.invoke(scraper, confirmed));
        assertTrue((Boolean) method.invoke(scraper, bracketedConfirmed));
        assertFalse((Boolean) method.invoke(scraper, closed));
    }

    @Test
    void extractSummaryUsesLegacyStatusNameColumnFallback() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Locator row = mockLegacyDataRow(
                "35886978",
                "KWFR",
                "[Open -- S]",
                "06/06/2026",
                "06/09/2026",
                "",
                "06/03/2026",
                "",
                "",
                "Ground",
                "1",
                "",
                ""
        );

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod("extractSummary", Locator.class, Map.class);
        method.setAccessible(true);

        CapturedOrdersPayload.CapturedOrder load =
                (CapturedOrdersPayload.CapturedOrder) method.invoke(scraper, row, new LinkedHashMap<>());

        assertEquals("35886978", load.externalOrderId);
        assertEquals("[Open -- S]", load.status);
        assertEquals("06/06/2026", load.shipDate);
        assertEquals("06/09/2026", load.deliveryDate);
    }

    @Test
    void extractSummaryUsesLegacyHeaderMapWithoutReadingModeAsWeight() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Locator row = mockLegacyDataRow(
                "22853714",
                "KWFR",
                "Open",
                "06/06/2026",
                "06/09/2026",
                "",
                "06/02/2026",
                "",
                "",
                "Ground",
                "2",
                "",
                ""
        );
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        headerIndex.put("Load Number", 0);
        headerIndex.put("Buyer", 1);
        headerIndex.put("Status Name", 2);
        headerIndex.put("Ship Date", 3);
        headerIndex.put("Arrival Date", 4);
        headerIndex.put("Alt A/D", 5);
        headerIndex.put("Submitted Date", 6);
        headerIndex.put("PR", 7);
        headerIndex.put("Rate", 8);
        headerIndex.put("Mode", 9);
        headerIndex.put("P/U Count", 10);
        headerIndex.put("Trans. Number", 11);
        headerIndex.put("Arrival Appointment", 12);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod("extractSummary", Locator.class, Map.class);
        method.setAccessible(true);

        CapturedOrdersPayload.CapturedOrder load =
                (CapturedOrdersPayload.CapturedOrder) method.invoke(scraper, row, headerIndex);

        assertEquals("22853714", load.externalOrderId);
        assertEquals("KWFR", load.customerName);
        assertEquals("Open", load.status);
        assertEquals("06/06/2026", load.shipDate);
        assertEquals("06/09/2026", load.deliveryDate);
        assertNull(load.pickupNumber);
        assertNull(load.palletCount);
        assertNull(load.weight);
    }

    @Test
    void extractSummaryFallsBackWhenDetectedStatusHeaderPointsToWrongCell() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Locator row = mockLegacyDataRow(
                "23845051",
                "KWFR",
                "Open",
                "06/09/2026",
                "06/13/2026",
                "",
                "06/08/2026",
                "",
                "",
                "Ground",
                "1",
                "",
                ""
        );
        Map<String, Integer> badHeaderIndex = new LinkedHashMap<>();
        badHeaderIndex.put("Status", 1);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod("extractSummary", Locator.class, Map.class);
        method.setAccessible(true);

        CapturedOrdersPayload.CapturedOrder load =
                (CapturedOrdersPayload.CapturedOrder) method.invoke(scraper, row, badHeaderIndex);

        assertEquals("23845051", load.externalOrderId);
        assertEquals("Open", load.status);
    }

    @Test
    void collectRenderedOrderPageSummariesDoesNotDeduplicateRowsByBadLoadNumberHeader() throws Exception {
        OmsrBrowserConfig config = new OmsrBrowserConfig();
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(config);

        Frame frame = mock(Frame.class);
        Locator rows = mock(Locator.class);
        Locator firstOpenRow = mockLegacyDataRowWithDetailLink("load_details_trans.cfm?theID=11",
                "23845051", "KWFR", "Open", "06/09/2026", "06/13/2026", "", "06/08/2026", "", "", "Ground", "1", "", "");
        Locator secondOpenRow = mockLegacyDataRowWithDetailLink("load_details_trans.cfm?theID=12",
                "23845052", "KWFR", "Open", "06/09/2026", "06/13/2026", "", "06/08/2026", "", "", "Ground", "1", "", "");

        when(frame.locator(config.getLoadRowSelector())).thenReturn(rows);
        when(frame.url()).thenReturn("https://oms.itradenetwork.com/2/logistics_t/trans_order_status.cfm");
        when(rows.count()).thenReturn(2);
        when(rows.nth(0)).thenReturn(firstOpenRow);
        when(rows.nth(1)).thenReturn(secondOpenRow);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "collectRenderedOrderPageSummaries",
                Frame.class,
                Map.class,
                int.class,
                Set.class,
                List.class
        );
        method.setAccessible(true);

        Map<String, Integer> badHeaderIndex = new LinkedHashMap<>();
        badHeaderIndex.put("Load Number", 1);
        badHeaderIndex.put("Status", 2);
        Set<String> seenLoadNumbers = new LinkedHashSet<>();
        List<Object> summaries = new ArrayList<>();

        int collected = (Integer) method.invoke(
                scraper,
                frame,
                badHeaderIndex,
                0,
                seenLoadNumbers,
                summaries
        );

        assertEquals(2, collected);
        assertEquals(new LinkedHashSet<>(List.of("23845051", "23845052")), seenLoadNumbers);
    }

    @Test
    void omsrOpenStatusFilterButtonUsesLegacySubmit4Button() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Frame frame = mock(Frame.class);
        Locator buttons = mock(Locator.class);
        Locator button = mock(Locator.class);

        when(frame.locator(anyString())).thenReturn(buttons);
        when(buttons.count()).thenReturn(1);
        when(buttons.first()).thenReturn(button);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "omsrOpenStatusFilterButton",
                Frame.class
        );
        method.setAccessible(true);

        Locator matchedButton = (Locator) method.invoke(scraper, frame);

        assertSame(button, matchedButton);
    }

    @Test
    void omsrOpenStatusFilterButtonUsesVisibleActionStripFilterButton() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Frame frame = mock(Frame.class);
        Locator exactButtons = mock(Locator.class);
        Locator controls = mock(Locator.class);
        Locator filterButton = mockActionControl("Filter");
        Locator cdfButton = mockActionControl("CDF");
        Locator clearButton = mockActionControl("Clear");
        Locator submitButton = mockActionControl("Submit");

        when(frame.locator(anyString())).thenReturn(exactButtons);
        when(frame.locator("input[type='button'], input[type='submit'], button")).thenReturn(controls);
        when(exactButtons.count()).thenReturn(0);
        when(controls.count()).thenReturn(4);
        when(controls.nth(0)).thenReturn(filterButton);
        when(controls.nth(1)).thenReturn(cdfButton);
        when(controls.nth(2)).thenReturn(clearButton);
        when(controls.nth(3)).thenReturn(submitButton);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "omsrOpenStatusFilterButton",
                Frame.class
        );
        method.setAccessible(true);

        Locator matchedButton = (Locator) method.invoke(scraper, frame);

        assertSame(filterButton, matchedButton);
    }

    @Test
    void bestEffortOrdersFrameIgnoresBlankIframeContentFrame() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Page page = mock(Page.class);
        Frame parentFrame = mock(Frame.class);
        Frame blankFrame = mock(Frame.class);
        Locator iframes = mock(Locator.class);
        Locator iframe = mock(Locator.class);
        ElementHandle iframeHandle = mock(ElementHandle.class);

        when(page.frames()).thenReturn(List.of(blankFrame));
        when(page.locator(anyString())).thenReturn(iframes);
        when(blankFrame.url()).thenReturn("about:blank");
        when(blankFrame.parentFrame()).thenReturn(parentFrame);
        when(blankFrame.evaluate(anyString())).thenReturn(false);
        when(iframes.count()).thenReturn(1);
        when(iframes.nth(0)).thenReturn(iframe);
        when(iframe.elementHandle(any(Locator.ElementHandleOptions.class))).thenReturn(iframeHandle);
        when(iframeHandle.contentFrame()).thenReturn(blankFrame);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "bestEffortOrdersFrame",
                Page.class
        );
        method.setAccessible(true);

        Frame matchedFrame = (Frame) method.invoke(scraper, page);

        assertNull(matchedFrame);
    }

    @Test
    void selectConfirmedOrderStatusOptionRetriesWhenPopupNavigates() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Page filterPage = mock(Page.class);

        when(filterPage.isClosed()).thenReturn(false);
        when(filterPage.evaluate(anyString()))
                .thenThrow(new PlaywrightException("Execution context was destroyed, most likely because of a navigation"))
                .thenReturn("orderStatus=Confirmed");

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "selectConfirmedOrderStatusOption",
                Page.class
        );
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(scraper, filterPage));
    }

    @Test
    void submitOmsrFilterWindowTreatsPopupCloseDuringNavigationAsSubmitted() throws Exception {
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(new OmsrBrowserConfig());
        Page filterPage = mock(Page.class);

        when(filterPage.isClosed()).thenReturn(false, true);
        when(filterPage.evaluate(anyString()))
                .thenThrow(new PlaywrightException("Execution context was destroyed, most likely because of a navigation"));

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "submitOmsrFilterWindow",
                Page.class
        );
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(scraper, filterPage));
    }

    @Test
    void applyConfirmedOrderStatusFilterContinuesWhenPopupStaysOpenAfterSubmit() throws Exception {
        OmsrBrowserConfig config = new OmsrBrowserConfig();
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(config);
        Page page = mock(Page.class);
        Frame parentFrame = mock(Frame.class);
        Frame ordersFrame = mock(Frame.class);
        Frame filteredFrame = mock(Frame.class);
        Page filterPage = mock(Page.class);
        Locator filterButtons = mock(Locator.class);
        Locator filterButton = mock(Locator.class);
        Locator rows = mock(Locator.class);
        Locator row = mock(Locator.class);
        Locator detailLinks = mock(Locator.class);

        when(ordersFrame.evaluate(anyString(), any())).thenReturn("before");
        when(ordersFrame.locator(anyString())).thenReturn(filterButtons);
        when(filterButtons.count()).thenReturn(1);
        when(filterButtons.first()).thenReturn(filterButton);

        when(page.waitForPopup(any(Page.WaitForPopupOptions.class), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable callback = invocation.getArgument(1);
            callback.run();
            return filterPage;
        });
        when(filterPage.isClosed()).thenReturn(false);
        when(filterPage.evaluate(anyString())).thenReturn("orderStatus=Confirmed", "submit");

        when(page.frames()).thenReturn(List.of(filteredFrame));
        when(filteredFrame.parentFrame()).thenReturn(parentFrame);
        when(filteredFrame.url()).thenReturn("https://oms.itradenetwork.com/2/logistics_t/trans_order_status.cfm");
        when(filteredFrame.evaluate(anyString())).thenReturn(true);
        when(filteredFrame.evaluate(anyString(), any())).thenReturn("after");
        when(filteredFrame.locator(config.getLoadRowSelector())).thenReturn(rows);
        when(rows.count()).thenReturn(1);
        when(rows.nth(0)).thenReturn(row);
        when(row.locator("a[href*='load_details']")).thenReturn(detailLinks);
        when(detailLinks.count()).thenReturn(1);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "applyConfirmedOrderStatusFilter",
                Page.class,
                Frame.class
        );
        method.setAccessible(true);

        Frame result = (Frame) method.invoke(scraper, page, ordersFrame);

        assertSame(filteredFrame, result);
        verify(filterPage).close();
    }

    @Test
    void collectRenderedOrderPageSummariesCollectsEveryFilteredLegacyRow() throws Exception {
        OmsrBrowserConfig config = new OmsrBrowserConfig();
        OmsrPlaywrightLoadScraper scraper = new OmsrPlaywrightLoadScraper(config);

        Frame frame = mock(Frame.class);
        Locator rows = mock(Locator.class);
        Locator openRow = mockLegacyDataRowWithDetailLink("load_details_trans.cfm?theID=1",
                "22853714", "KWFR", "Open", "06/06/2026", "06/09/2026", "", "06/02/2026", "", "", "Ground", "1", "", "");
        Locator confirmedRow = mockLegacyDataRowWithDetailLink("load_details_trans.cfm?theID=2",
                "22853715", "KWFR", "Confirmed", "06/06/2026", "06/09/2026", "", "06/02/2026", "", "", "Ground", "1", "", "");
        Locator bracketedOpenRow = mockLegacyDataRowWithDetailLink("load_details_trans.cfm?theID=3",
                "22853716", "KWFR", "|Open|", "06/06/2026", "06/09/2026", "", "06/02/2026", "", "", "Ground", "1", "", "");
        Locator openWithSuffixRow = mockLegacyDataRowWithDetailLink("load_details_trans.cfm?theID=4",
                "22853717", "KWFR", "Open -- S", "06/06/2026", "06/09/2026", "", "06/02/2026", "", "", "Ground", "1", "", "");

        when(frame.locator(config.getLoadRowSelector())).thenReturn(rows);
        when(frame.url()).thenReturn("https://oms.itradenetwork.com/2/logistics_t/trans_order_status.cfm");
        when(rows.count()).thenReturn(4);
        when(rows.nth(0)).thenReturn(openRow);
        when(rows.nth(1)).thenReturn(confirmedRow);
        when(rows.nth(2)).thenReturn(bracketedOpenRow);
        when(rows.nth(3)).thenReturn(openWithSuffixRow);

        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "collectRenderedOrderPageSummaries",
                Frame.class,
                Map.class,
                int.class,
                Set.class,
                List.class
        );
        method.setAccessible(true);

        Set<String> seenLoadNumbers = new LinkedHashSet<>();
        List<Object> summaries = new ArrayList<>();
        int collected = (Integer) method.invoke(
                scraper,
                frame,
                legacyHeaderIndex(),
                0,
                seenLoadNumbers,
                summaries
        );

        assertEquals(4, collected);
        assertEquals(4, summaries.size());
        assertEquals(new LinkedHashSet<>(List.of("22853714", "22853715", "22853716", "22853717")), seenLoadNumbers);
    }

    private Locator invokeFindLoadRow(
            OmsrPlaywrightLoadScraper scraper,
            Frame frame,
            Map<String, Integer> headerIndex,
            String loadNumber
    ) throws Exception {
        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "findLoadRow",
                Frame.class,
                Map.class,
                String.class
        );
        method.setAccessible(true);
        return (Locator) method.invoke(scraper, frame, headerIndex, loadNumber);
    }

    private void invokeApplyShippingTotals(
            OmsrPlaywrightLoadScraper scraper,
            CapturedOrdersPayload.CapturedOrder load,
            String bodyText
    ) throws Exception {
        Method method = OmsrPlaywrightLoadScraper.class.getDeclaredMethod(
                "applyShippingTotals",
                CapturedOrdersPayload.CapturedOrder.class,
                Map.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(scraper, load, new LinkedHashMap<String, String>(), bodyText);
    }

    private Locator mockActionControl(String value) {
        Locator control = mock(Locator.class);
        when(control.count()).thenReturn(1);
        when(control.getAttribute("value")).thenReturn(value);
        return control;
    }

    private Locator mockLegacyDataRow(String... cellTexts) {
        Locator row = mock(Locator.class);
        Locator cells = mock(Locator.class);
        when(row.locator("td, [role='cell']")).thenReturn(cells);
        when(cells.count()).thenReturn(cellTexts.length);

        for (int index = 0; index < cellTexts.length; index++) {
            Locator cell = mock(Locator.class);
            when(cells.nth(index)).thenReturn(cell);
            when(cell.count()).thenReturn(1);
            when(cell.innerText()).thenReturn(cellTexts[index]);
            if (index == 0) {
                Locator checkbox = mock(Locator.class);
                when(cell.locator("input[type='checkbox'], .ant-checkbox")).thenReturn(checkbox);
                when(checkbox.count()).thenReturn(0);
            }
        }
        return row;
    }

    private Locator mockLegacyDataRowWithDetailLink(String href, String... cellTexts) {
        Locator row = mockLegacyDataRow(cellTexts);
        Locator links = mock(Locator.class);
        Locator link = mock(Locator.class);
        when(row.locator("a[href*='load_details']")).thenReturn(links);
        when(links.count()).thenReturn(1);
        when(links.first()).thenReturn(link);
        when(link.count()).thenReturn(1);
        when(link.getAttribute("href")).thenReturn(href);
        return row;
    }

    private Map<String, Integer> legacyHeaderIndex() {
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        headerIndex.put("Load Number", 0);
        headerIndex.put("Buyer", 1);
        headerIndex.put("Status Name", 2);
        headerIndex.put("Ship Date", 3);
        headerIndex.put("Arrival Date", 4);
        headerIndex.put("Alt A/D", 5);
        headerIndex.put("Submitted Date", 6);
        headerIndex.put("PR", 7);
        headerIndex.put("Rate", 8);
        headerIndex.put("Mode", 9);
        headerIndex.put("P/U Count", 10);
        headerIndex.put("Trans. Number", 11);
        headerIndex.put("Arrival Appointment", 12);
        return headerIndex;
    }
}
