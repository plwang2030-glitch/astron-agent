package com.iflytek.astron.console.hub.service.ashare;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AShareRulesTest {

    @Test
    void normalizesCodesAndRejectsUnsupportedMarkets() {
        assertEquals("600519", AShareRules.normalizeCode("sh600519"));
        assertEquals("000333", AShareRules.normalizeCode("000333.SZ"));
        assertEquals("688981", AShareRules.normalizeCode(" 688981 "));
        assertThrows(IllegalArgumentException.class, () -> AShareRules.normalizeCode("830799"));
        assertThrows(IllegalArgumentException.class, () -> AShareRules.normalizeCode("920001"));
        assertThrows(IllegalArgumentException.class, () -> AShareRules.normalizeCode("510300"));
        assertThrows(IllegalArgumentException.class, () -> AShareRules.normalizeCode("茅台"));
    }

    @Test
    void mapsBoardsAndSecurityIds() {
        assertEquals(AShareRules.Board.SH_MAIN, AShareRules.board("600036"));
        assertEquals(AShareRules.Board.SZ_MAIN, AShareRules.board("002415"));
        assertEquals(AShareRules.Board.CHINEXT, AShareRules.board("300750"));
        assertEquals(AShareRules.Board.STAR, AShareRules.board("688981"));
        assertEquals("1.600036", AShareRules.secid("600036"));
        assertEquals("1.688981", AShareRules.secid("688981"));
        assertEquals("0.300750", AShareRules.secid("300750"));
        assertEquals("0.000333", AShareRules.secid("000333"));
    }

    @Test
    void priceLimitsDependOnBoardAndSt() {
        assertEquals(new BigDecimal("0.10"), AShareRules.limitPct("600036", "招商银行"));
        assertEquals(new BigDecimal("0.05"), AShareRules.limitPct("600123", "*ST 某某"));
        assertEquals(new BigDecimal("0.20"), AShareRules.limitPct("300750", "宁德时代"));
        // ST stocks on ChiNext / STAR keep the 20% limit.
        assertEquals(new BigDecimal("0.20"), AShareRules.limitPct("300001", "ST 某某"));
        assertEquals(new BigDecimal("11.00"), AShareRules.limitUp(new BigDecimal("10.00"), "600036", "招商银行"));
        assertEquals(new BigDecimal("9.00"), AShareRules.limitDown(new BigDecimal("10.00"), "600036", "招商银行"));
        assertEquals(new BigDecimal("12.00"), AShareRules.limitUp(new BigDecimal("10.00"), "300750", "宁德时代"));
    }

    @Test
    void lotRules() {
        assertNull(AShareRules.checkQty("600036", true, 200, 0));
        assertNotNull(AShareRules.checkQty("600036", true, 150, 0));
        assertNotNull(AShareRules.checkQty("600036", true, 0, 0));
        assertNotNull(AShareRules.checkQty("688981", true, 150, 0));
        assertNull(AShareRules.checkQty("688981", true, 201, 0));
        // Odd lots can only be sold all at once.
        assertNull(AShareRules.checkQty("600036", false, 150, 150));
        assertNotNull(AShareRules.checkQty("600036", false, 150, 300));
        assertNull(AShareRules.checkQty("600036", false, 100, 300));
    }

    @Test
    void maxBuyQtyRespectsLots() {
        assertEquals(500, AShareRules.maxBuyQty("600036", 19.9, 10_000));
        assertEquals(0, AShareRules.maxBuyQty("600036", 200, 10_000));
        assertEquals(233, AShareRules.maxBuyQty("688981", 42.9, 10_000));
        assertEquals(0, AShareRules.maxBuyQty("688981", 60, 10_000));
    }

    @Test
    void feesUseMinimumCommissionAndStampDutyOnSells() {
        AShareRules.Fees buy = AShareRules.fees(true, new BigDecimal("10000"));
        assertEquals(new BigDecimal("5.00"), buy.commission());
        assertEquals(new BigDecimal("0.10"), buy.transfer());
        assertEquals(0, buy.stamp().signum());
        assertEquals(new BigDecimal("5.10"), buy.total());
        AShareRules.Fees sell = AShareRules.fees(false, new BigDecimal("100000"));
        assertEquals(new BigDecimal("25.00"), sell.commission());
        assertEquals(new BigDecimal("1.00"), sell.transfer());
        assertEquals(new BigDecimal("50.00"), sell.stamp());
        assertEquals(new BigDecimal("76.00"), sell.total());
    }
}
