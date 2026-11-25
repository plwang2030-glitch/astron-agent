package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The notification title must say which rule sold, not just "sold". */
class OrderNotificationTest {

    private static PaperOrder sell(String reason) {
        PaperOrder o = new PaperOrder();
        o.setSide(PaperOrder.SELL);
        o.setSource("STRATEGY");
        o.setReason(reason);
        return o;
    }

    @Test
    void sellLabelComesFromTheRuleThatFired() {
        assertEquals("止损", PaperTradingService.fillLabel(sell("策略 v4 盘中止损：现价 96.28，成本 107.207")));
        assertEquals("止盈", PaperTradingService.fillLabel(sell("策略 v4 盘中止盈：现价 130.0，成本 107.0")));
        assertEquals("移动止损", PaperTradingService.fillLabel(sell("策略 v4 盘中移动止损：现价 120，最高 135")));
        assertEquals("分批止盈", PaperTradingService.fillLabel(sell("策略 v4 盘中分批止盈：现价 118")));
        assertEquals("到期", PaperTradingService.fillLabel(sell("策略 v4 尾盘离场：到期")));
        assertEquals("均线死叉", PaperTradingService.fillLabel(sell("策略 v4 尾盘均线死叉：收盘确认")));
        assertEquals("自定义离场", PaperTradingService.fillLabel(sell("策略 v4 尾盘离场：自定义离场")));
    }

    @Test
    void fallsBackWhenTheReasonSaysNothingUseful() {
        assertEquals("卖出成交", PaperTradingService.fillLabel(sell("用户要求卖出")));
        assertEquals("卖出成交", PaperTradingService.fillLabel(sell(null)));
    }

    @Test
    void aSellBodyShowsSizePriceFeesAndRealizedPnl() {
        PaperOrder o = sell("策略 v4 盘中止损：现价 96.28，成本 107.207");
        o.setCode("603083");
        o.setName("剑桥科技");
        o.setStatus(PaperOrder.FILLED);
        o.setQty(200);
        o.setFillPrice(new java.math.BigDecimal("96.180"));
        o.setFillAmount(new java.math.BigDecimal("19236.00"));
        o.setFees(new java.math.BigDecimal("14.81"));
        o.setRealizedPnl(new java.math.BigDecimal("-2220.21"));
        assertEquals("A股模拟盘：止损 剑桥科技 603083", PaperTradingService.orderTitle(o));
        String body = PaperTradingService.orderBody(o);
        assertTrue(body.startsWith("200 股 @96.180 元，金额 19236.00 元，手续费 14.81 元，本笔亏损 2220.21 元。依据："), body);
        assertTrue(body.contains("盘中止损"), body);
        // A gain is worded as a gain, and the sign is not printed twice.
        o.setRealizedPnl(new java.math.BigDecimal("301.50"));
        assertTrue(PaperTradingService.orderBody(o).contains("本笔盈利 301.50 元"));
    }

    @Test
    void aBuyBodyHasNoRealizedPnl() {
        PaperOrder o = new PaperOrder();
        o.setCode("000725");
        o.setName("京东方Ａ");
        o.setSide(PaperOrder.BUY);
        o.setSource("STRATEGY");
        o.setStatus(PaperOrder.FILLED);
        o.setQty(100);
        o.setFillPrice(new java.math.BigDecimal("4.480"));
        o.setFillAmount(new java.math.BigDecimal("448.00"));
        o.setFees(new java.math.BigDecimal("5.00"));
        o.setRealizedPnl(new java.math.BigDecimal("999"));
        o.setReason("策略 v4 尾盘入场（MA_CROSS）");
        assertEquals("A股模拟盘：策略买入 京东方Ａ 000725", PaperTradingService.orderTitle(o));
        String body = PaperTradingService.orderBody(o);
        assertFalse(body.contains("本笔"), body);
        assertTrue(body.contains("100 股 @4.480 元"), body);
    }

    @Test
    void aRejectionSaysWhy() {
        PaperOrder o = sell("策略 v4 尾盘离场：到期");
        o.setCode("002281");
        o.setName("光迅科技");
        o.setStatus(PaperOrder.REJECTED);
        o.setQty(200);
        o.setRejectReason("可卖 0 股不足（T+1：当日买入的不能卖）");
        assertEquals("A股模拟盘：卖出未成交 光迅科技 002281", PaperTradingService.orderTitle(o));
        assertTrue(PaperTradingService.orderBody(o).contains("200 股未成交：可卖 0 股不足"));
        // A rejection with no recorded reason still reads sensibly.
        o.setRejectReason(null);
        assertTrue(PaperTradingService.orderBody(o).contains("原因未记录"));
    }

    @Test
    void buysSayWhoPlacedThem() {
        PaperOrder strategy = new PaperOrder();
        strategy.setSide(PaperOrder.BUY);
        strategy.setSource("STRATEGY");
        strategy.setReason("策略 v4 尾盘入场（MA_CROSS）");
        assertEquals("策略买入", PaperTradingService.fillLabel(strategy));
        PaperOrder agent = new PaperOrder();
        agent.setSide(PaperOrder.BUY);
        agent.setSource("AGENT");
        assertEquals("买入成交", PaperTradingService.fillLabel(agent));
    }
}
