package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.config.AppConfig;
import com.github.h3yow.iotdevicetwinjava.web.DeviceController;
import com.github.h3yow.iotdevicetwinjava.web.RuleAdminController;
import com.github.h3yow.iotdevicetwinjava.web.ShadowController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 不经网络端口的端到端集成测试：Controller -> Service -> Store 全链路，
 * 演示并验证乱序上报、规则触发、离线重连的完整 HTTP 流程。
 */
class HttpEndToEndTest {

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc newClient() {
        AppConfig config = new AppConfig();
        var store = config.deviceShadowStore();
        var ruleEngine = config.ruleEngine();
        var telemetry = config.telemetryService(store, ruleEngine);
        var shadow = config.shadowService(store, ruleEngine);
        var batchShadow = config.batchShadowService(store, shadow);
        var lifecycle = config.deviceLifecycleService(store);
        return standaloneSetup(
                new DeviceController(lifecycle, telemetry, store),
                new ShadowController(shadow, batchShadow),
                new RuleAdminController(store)).build();
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        MockMvc mvc = newClient();
        String d = "dev-http";

        // 注册 + 上线
        mvc.perform(post("/api/devices/" + d + "/register")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        mvc.perform(post("/api/devices/" + d + "/online")
                .contentType(MediaType.APPLICATION_JSON).content("{\"timestamp\":1}")).andReturn();

        // 注册阈值规则
        mvc.perform(post("/api/rules").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ruleId":"r1","deviceId":"%s","field":"temperature",
                         "operator":"GTE","threshold":30.0,"commandType":"COOL_ON",
                         "commandPayload":{"fan":"high"}}
                        """.formatted(d))).andReturn();

        // 乱序上报：先 31（触发），再 25（过期被拒）
        mvc.perform(post("/api/devices/" + d + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deviceId":"%s","timestamp":100,"reportId":"p100",
                         "fields":{"temperature":31.0}}
                        """.formatted(d))).andReturn();
        MvcResult stale = mvc.perform(post("/api/devices/" + d + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deviceId":"%s","timestamp":90,"reportId":"p90",
                         "fields":{"temperature":25.0}}
                        """.formatted(d))).andReturn();
        JsonNode staleNode = json.readTree(stale.getResponse().getContentAsString());
        assertFalse(staleNode.path("applied").asBoolean());
        assertEquals("STALE_TIMESTAMP",
                staleNode.path("fields").get(0).path("reason").asText());

        // 离线后再过热（积压），重连补发
        mvc.perform(post("/api/devices/" + d + "/offline")
                .contentType(MediaType.APPLICATION_JSON).content("{\"timestamp\":101}")).andReturn();
        mvc.perform(post("/api/devices/" + d + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deviceId":"%s","timestamp":120,"reportId":"p120",
                         "fields":{"temperature":20.0}}
                        """.formatted(d))).andReturn();
        mvc.perform(post("/api/devices/" + d + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deviceId":"%s","timestamp":130,"reportId":"p130",
                         "fields":{"temperature":33.0}}
                        """.formatted(d))).andReturn();

        MvcResult reconnect = mvc.perform(post("/api/devices/" + d + "/reconnect")
                .contentType(MediaType.APPLICATION_JSON).content("{\"timestamp\":200}")).andReturn();
        JsonNode recNode = json.readTree(reconnect.getResponse().getContentAsString());
        assertEquals(1, recNode.path("redeliveredCommands").size());

        // 影子状态确认：版本与温度均未被补发动作改动
        MvcResult shadowView = mvc.perform(get("/api/devices/" + d + "/shadow")).andReturn();
        JsonNode shadowNode = json.readTree(shadowView.getResponse().getContentAsString());
        assertEquals(33.0, shadowNode.path("reported").path("temperature").path("value").asDouble());
        assertEquals(3, shadowNode.path("version").asInt());
        assertEquals("ONLINE", shadowNode.path("status").asText());

        // 影子更新：非法字段必须以 UNKNOWN_FIELD 拒绝
        MvcResult badUpdate = mvc.perform(post("/api/shadows/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deviceId":"%s","baseVersion":3,"updateId":"u-bad","timestamp":140,
                         "desired":{"ghost":1}}
                        """.formatted(d))).andReturn();
        JsonNode bad = json.readTree(badUpdate.getResponse().getContentAsString());
        assertFalse(bad.path("committed").asBoolean());
        assertEquals("UNKNOWN_FIELD", bad.path("fields").get(0).path("reason").asText());
    }

    @Test
    void batchUpdateOverHttp() throws Exception {
        MockMvc mvc = newClient();
        for (String d : new String[]{"dev-b1", "dev-b2"}) {
            mvc.perform(post("/api/devices/" + d + "/register")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        }

        // 批量下发：一台未注册，逐台结论可区分
        MvcResult first = mvc.perform(post("/api/shadows/batch-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"batchId":"b-http","baseVersion":0,"timestamp":100,
                         "desired":{"mode":"cool"},
                         "deviceIds":["dev-b1","dev-b2","dev-ghost"]}
                        """)).andReturn();
        JsonNode firstNode = json.readTree(first.getResponse().getContentAsString());
        assertFalse(firstNode.path("batchRejected").asBoolean());
        assertEquals(3, firstNode.path("deviceResults").size());
        assertEquals("DEVICE_NOT_FOUND", firstNode.path("deviceResults").get(2)
                .path("fields").get(0).path("reason").asText());

        // 同 batchId 同内容重发：幂等重放，逐台结论一致
        MvcResult replay = mvc.perform(post("/api/shadows/batch-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"batchId":"b-http","baseVersion":0,"timestamp":100,
                         "desired":{"mode":"cool"},
                         "deviceIds":["dev-b1","dev-b2","dev-ghost"]}
                        """)).andReturn();
        JsonNode replayNode = json.readTree(replay.getResponse().getContentAsString());
        assertTrue(replayNode.path("idempotentReplay").asBoolean());
        assertEquals(firstNode.path("deviceResults"), replayNode.path("deviceResults"));

        // 同 batchId 换内容：BATCH_ID_CONFLICT
        MvcResult conflict = mvc.perform(post("/api/shadows/batch-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"batchId":"b-http","baseVersion":0,"timestamp":101,
                         "desired":{"mode":"heat"},
                         "deviceIds":["dev-b1","dev-b2","dev-ghost"]}
                        """)).andReturn();
        JsonNode conflictNode = json.readTree(conflict.getResponse().getContentAsString());
        assertTrue(conflictNode.path("batchRejected").asBoolean());
        assertEquals("BATCH_ID_CONFLICT", conflictNode.path("rejectReason").asText());
    }
}
