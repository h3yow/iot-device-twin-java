package com.github.h3yow.iotdevicetwinjava.store;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令记录与离线补发队列（本地内存）。
 *
 * - 命令以 commandId 全局幂等：重复保存不会产生第二条记录；
 * - 离线期间产生的命令 delivered=false 进入待发队列；
 * - 重连后按创建顺序取出 pending 并逐条标记 delivered，每条命令只送达一次。
 */
@Component
public class CommandStore {

    private final ConcurrentHashMap<String, Command> commands = new ConcurrentHashMap<>();

    /** 幂等保存：已存在同 commandId 时保留原记录，返回 false。 */
    public boolean save(Command command) {
        return commands.putIfAbsent(command.commandId(), command) == null;
    }

    public Command get(String commandId) {
        return commands.get(commandId);
    }

    public boolean exists(String commandId) {
        return commands.containsKey(commandId);
    }

    /** 某设备的全部命令（含已送达与待补发），按创建时间升序。 */
    public List<Command> forDevice(String deviceId) {
        return commands.values().stream()
                .filter(c -> c.deviceId().equals(deviceId))
                .sorted(Comparator.comparingLong(Command::createdAt))
                .toList();
    }

    /** 某设备尚未送达的命令，按创建时间升序（补发顺序确定）。 */
    public List<Command> pending(String deviceId) {
        return commands.values().stream()
                .filter(c -> c.deviceId().equals(deviceId))
                .filter(c -> !c.delivered())
                .sorted(Comparator.comparingLong(Command::createdAt))
                .toList();
    }

    /** 将命令标记为已送达；不存在或已送达时返回 false。 */
    public boolean markDelivered(String commandId) {
        Command existing = commands.get(commandId);
        if (existing == null || existing.delivered()) {
            return false;
        }
        Command delivered = new Command(existing.commandId(), existing.deviceId(),
                existing.ruleId(), existing.name(), existing.payload(),
                existing.createdAt(), true);
        return commands.replace(commandId, existing, delivered);
    }
}
