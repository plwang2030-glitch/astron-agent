-- Doubao Speech (Volcengine) voice ids such as "zh_female_shuangkuaisisi_moon_bigtts" (36 chars)
-- exceed the original varchar(32), which made bot creation fail with
-- "Data too long for column 'vcn_cn'". Widening is backward compatible with iFLYTEK voice codes.
ALTER TABLE chat_bot_base
    MODIFY COLUMN `vcn_cn` VARCHAR(128) DEFAULT NULL COMMENT 'Chinese voice actor',
    MODIFY COLUMN `vcn_en` VARCHAR(128) DEFAULT NULL COMMENT 'English voice actor';
