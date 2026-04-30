-- AIMS JD 直接读写 AIMS 引擎表，不再创建独立 schema 或本地快照表。
-- 当前默认配置：
--   aims.engine.schema=public  -> 只读 public.device_infos
--   aims.jd.schema=public      -> 写入 public.device_data
--
-- 本文件仅保留直接读写模式下的可选索引。执行前请确认 public.device_infos
-- 和 public.device_data 已由 jingyi_aims_engine 的建表脚本创建。

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_infos_ip_active
    ON public.device_infos(device_ip)
    WHERE is_deleted = false;

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_infos_driver_active
    ON public.device_infos(device_driver_code)
    WHERE is_deleted = false;

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_data_device_id
    ON public.device_data(device_id);

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_data_recorded_at
    ON public.device_data(recorded_at);

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_data_minute_dedupe
    ON public.device_data(device_id, param_code, date_trunc('minute', recorded_at));
