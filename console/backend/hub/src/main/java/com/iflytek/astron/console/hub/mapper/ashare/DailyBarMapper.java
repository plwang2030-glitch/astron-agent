package com.iflytek.astron.console.hub.mapper.ashare;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Daily bars stored for backtests ({@code ashare_daily_bar}). */
@Mapper
public interface DailyBarMapper {

    @Select("SELECT trade_date AS tradeDate, name, open, close, high, low, volume, amount, pct_chg AS pctChg, "
            + "turnover_pct AS turnoverPct FROM ashare_daily_bar WHERE secid = #{secid} AND adjusted = #{adjusted} "
            + "ORDER BY trade_date DESC LIMIT #{limit}")
    List<Map<String, Object>> latest(@Param("secid") String secid, @Param("adjusted") int adjusted,
            @Param("limit") int limit);

    @Select("SELECT MAX(trade_date) FROM ashare_daily_bar WHERE secid = #{secid} AND adjusted = #{adjusted}")
    LocalDate lastDate(@Param("secid") String secid, @Param("adjusted") int adjusted);

    @Delete("DELETE FROM ashare_daily_bar WHERE secid = #{secid} AND adjusted = #{adjusted}")
    int deleteAll(@Param("secid") String secid, @Param("adjusted") int adjusted);

    @Insert("<script>REPLACE INTO ashare_daily_bar (secid, adjusted, trade_date, name, open, close, high, low, volume, "
            + "amount, pct_chg, turnover_pct) VALUES "
            + "<foreach collection='rows' item='r' separator=','>(#{secid}, #{adjusted}, #{r.date}, #{name}, #{r.open}, "
            + "#{r.close}, #{r.high}, #{r.low}, #{r.volume}, #{r.amount}, #{r.pctChg}, #{r.turnoverPct})</foreach>"
            + "</script>")
    int upsert(@Param("secid") String secid, @Param("adjusted") int adjusted, @Param("name") String name,
            @Param("rows") List<com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar> rows);
}
