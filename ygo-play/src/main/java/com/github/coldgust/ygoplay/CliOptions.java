package com.github.coldgust.ygoplay;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数：只表达"用户显式给了什么"，未给的留空，由 {@link RuntimeConfig} 合并环境/.env/默认值。
 */
public record CliOptions(
        String host, Integer port, String name, String ydkPath, String move,
        String tracePath, String duelLogPath,
        String archetype, String envPath) {

    public static CliOptions parse(String[] args) {
        String host = null;
        Integer port = null;
        String name = null;
        String ydk = null;
        String move = null;
        String tracePath = null;
        String duelLogPath = null;
        String archetype = null;
        String envPath = null;

        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--trace" -> tracePath = args[++i];
                case "--duel-log" -> duelLogPath = args[++i];
                case "--archetype" -> archetype = args[++i];
                case "--env" -> envPath = args[++i];
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() > 0) host = positional.get(0);
        if (positional.size() > 1) port = Integer.parseInt(positional.get(1));
        if (positional.size() > 2) name = positional.get(2);
        if (positional.size() > 3) ydk = positional.get(3);
        if (positional.size() > 4) move = positional.get(4);
        return new CliOptions(host, port, name, ydk, move, tracePath, duelLogPath,
                archetype, envPath);
    }
}
