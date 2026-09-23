package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;
import com.github.h3yow.iotdevicetwinjava.model.RuleDefinition;
import com.github.h3yow.iotdevicetwinjava.store.CommandStore;
import com.github.h3yow.iotdevicetwinjava.store.RuleDefinitionStore;
import com.github.h3yow.iotdevicetwinjava.store.RuleStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阈值规则评估与命令下发。
 *
 * 必须在调用方持有设备锁({@code DeviceStore.lockFor})时调用：
 * “条件判定 + 武装状态翻转 + 命令落库”作为一个临界区，
 * 保证并发更新下同一持续为真的条件只下发一次。
 *
 * 在线：命令即时送达(delivered=true)；离线：命令入待发队列(delivered=false)，
 * 由 {@link DeviceConnectionService#reconnect} 按序补发，补发不改影子、不重复执行。
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleDefinitionStore ruleDefinitionStore;
    private final RuleStateStore ruleStateStore;
    private final CommandStore commandStore;

    public RuleEngine(RuleDefinitionStore ruleDefinitionStore,
                      RuleStateStore ruleStateStore,
                      CommandStore commandStore) {
        this.ruleDefinitionStore = ruleDefinitionStore;
        this.ruleStateStore = ruleStateStore;
        this.commandStore = commandStore;
    }

    /** 评估全部已注册规则，返回本次评估实际触发的命令（可能尚未送达）。 */
    public List<Command> evaluate(DeviceShadow shadow) {
        String deviceId = shadow.getDeviceId();
        List<Command> fired = new ArrayList<>();

        for (RuleDefinition rule : ruleDefinitionStore.all()) {
            Object raw = shadow.getReportedValue(rule.metric());
            boolean condition = raw instanceof Number n && compare(n.doubleValue(), rule);

            if (condition) {
                if (!ruleStateStore.isArmed(deviceId, rule.ruleId())) {
                    log.info("[rule] device={} rule={} 条件持续为真(metric={} value={} op={} threshold={})，已去重，不重复下发",
                            deviceId, rule.ruleId(), rule.metric(), raw, rule.operator(), rule.threshold());
                    continue;
                }
                ruleStateStore.setArmed(deviceId, rule.ruleId(), false);

                // 以触发时该指标的生效时间戳标识本次边沿 episode，确定且天然幂等
                long metricTs = shadow.getReportedTimestamp(rule.metric());
                String commandId = rule.ruleId() + "|" + deviceId + "|" + metricTs;
                boolean online = shadow.getStatus() == DeviceStatus.ONLINE;
                Command command = new Command(commandId, deviceId, rule.ruleId(),
                        rule.commandName(),
                        Map.copyOf(rule.commandPayload()),
                        metricTs, online);
                boolean saved = commandStore.save(command);
                if (online && saved) {
                    commandStore.markDelivered(commandId);
                    command = commandStore.get(commandId);
                }
                if (saved) {
                    fired.add(command);
                }
                log.info("[rule] device={} rule={} 判定依据: value={} {} {} (metricTs={}, armed->false) => 触发命令 {} commandId={} delivered={}",
                        deviceId, rule.ruleId(), raw, rule.operator(), rule.threshold(),
                        metricTs, rule.commandName(), commandId, online);
            } else {
                boolean wasArmed = ruleStateStore.isArmed(deviceId, rule.ruleId());
                ruleStateStore.setArmed(deviceId, rule.ruleId(), true);
                if (!wasArmed) {
                    log.info("[rule] device={} rule={} 条件恢复为假/数据缺失(metric={} value={})，规则重新武装",
                            deviceId, rule.ruleId(), rule.metric(), raw);
                }
            }
        }
        return fired;
    }

    private boolean compare(double value, RuleDefinition rule) {
        return switch (rule.operator()) {
            case GT -> value > rule.threshold();
            case GE -> value >= rule.threshold();
            case LT -> value < rule.threshold();
            case LE -> value <= rule.threshold();
        };
    }
}
