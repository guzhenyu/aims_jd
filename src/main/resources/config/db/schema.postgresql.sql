CREATE SCHEMA IF NOT EXISTS aims_jd;

CREATE TABLE IF NOT EXISTS aims_jd.device_infos (
    id INTEGER PRIMARY KEY,
    dept_id INTEGER NOT NULL,
    device_sn VARCHAR(255) NOT NULL,
    device_type VARCHAR(255) NOT NULL,
    device_name VARCHAR(255) NOT NULL,
    device_ip VARCHAR(255),
    device_port VARCHAR(255),
    device_driver_code VARCHAR(255),
    source_mode INTEGER NOT NULL,
    source_topology INTEGER NOT NULL,
    upstream_device_id INTEGER NOT NULL DEFAULT 0,
    pds_ip_seq INTEGER DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by INTEGER,
    deleted_at TIMESTAMP,
    modified_by INTEGER,
    modified_at TIMESTAMP
);

COMMENT ON TABLE aims_jd.device_infos IS 'AIMS JD 设备信息快照表';
COMMENT ON COLUMN aims_jd.device_infos.id IS 'AIMS device_infos.id';
COMMENT ON COLUMN aims_jd.device_infos.dept_id IS '部门id';
COMMENT ON COLUMN aims_jd.device_infos.device_sn IS '设备序列号';
COMMENT ON COLUMN aims_jd.device_infos.device_type IS '设备类型';
COMMENT ON COLUMN aims_jd.device_infos.device_name IS '设备名称';
COMMENT ON COLUMN aims_jd.device_infos.device_ip IS '设备IP地址';
COMMENT ON COLUMN aims_jd.device_infos.device_port IS '设备端口';
COMMENT ON COLUMN aims_jd.device_infos.device_driver_code IS '驱动编码';
COMMENT ON COLUMN aims_jd.device_infos.source_mode IS '数据来源模式，1-INBOUND_SERVER，2-OUTBOUND_CLIENT，3-NON_DIRECT_CONNECT';
COMMENT ON COLUMN aims_jd.device_infos.source_topology IS '数据来源拓扑，1-SINGLE_DEVICE，2-CENTRAL_STATION_FANOUT';
COMMENT ON COLUMN aims_jd.device_infos.upstream_device_id IS '上游设备id，0表示无上游';
COMMENT ON COLUMN aims_jd.device_infos.pds_ip_seq IS 'PDS target ipSeq';
COMMENT ON COLUMN aims_jd.device_infos.is_deleted IS '是否已删除';

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_infos_ip ON aims_jd.device_infos(device_ip) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_aims_jd_device_infos_driver ON aims_jd.device_infos(device_driver_code) WHERE is_deleted = false;

CREATE TABLE IF NOT EXISTS aims_jd.device_data (
    id BIGSERIAL PRIMARY KEY,
    dept_id INTEGER NOT NULL,
    device_id INTEGER,
    param_code VARCHAR(255),
    recorded_at TIMESTAMP,
    recorded_str VARCHAR(255)
);

COMMENT ON TABLE aims_jd.device_data IS 'AIMS JD 设备数据表，只保存最近90天数据';
COMMENT ON COLUMN aims_jd.device_data.id IS '自增id';
COMMENT ON COLUMN aims_jd.device_data.dept_id IS '部门id';
COMMENT ON COLUMN aims_jd.device_data.device_id IS '设备id，关联 aims_jd.device_infos.id';
COMMENT ON COLUMN aims_jd.device_data.param_code IS '观测参数code';
COMMENT ON COLUMN aims_jd.device_data.recorded_at IS '数据记录时间，服务器UTC时间';
COMMENT ON COLUMN aims_jd.device_data.recorded_str IS '设备数据值字符串';

CREATE INDEX IF NOT EXISTS idx_aims_jd_device_data_device_id ON aims_jd.device_data(device_id);
CREATE INDEX IF NOT EXISTS idx_aims_jd_device_data_recorded_at ON aims_jd.device_data(recorded_at);
CREATE INDEX IF NOT EXISTS idx_aims_jd_device_data_minute_dedupe
    ON aims_jd.device_data(device_id, param_code, date_trunc('minute', recorded_at));
