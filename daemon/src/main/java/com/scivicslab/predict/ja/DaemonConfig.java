package com.scivicslab.predict.ja;

/**
 * Configuration for the predict daemon.
 */
public record DaemonConfig(
        int port,
        String dataDir,
        String mozcUserDictPath,
        String vllmUrl,
        String vllmModel,
        int curateIntervalMinutes,
        int maxDictEntries,
        String gatewayUrl,
        int gatewayPollSeconds,
        boolean imeLearning
) {

    public static DaemonConfig fromArgsOrDefaults(String[] args) {
        String home = System.getProperty("user.home");

        int port = intArg(args, "--port", 8190);
        String dataDir = stringArg(args, "--data-dir",
                home + "/.local/share/fcitx5-predict-ja");
        String mozcUserDictPath = stringArg(args, "--mozc-dict",
                home + "/.config/mozc/user_dictionary.db");
        String vllmUrl = stringArg(args, "--vllm-url",
                "http://192.168.5.16:8000");
        String vllmModel = stringArg(args, "--vllm-model", "llm-jp-3-3.7b-instruct");
        int curateInterval = intArg(args, "--curate-interval", 5);
        int maxEntries = intArg(args, "--max-dict-entries", 3000);
        String gatewayUrl = stringArg(args, "--gateway-url", "");
        int gatewayPoll = intArg(args, "--gateway-poll", 300);
        boolean imeLearning = boolArg(args, "--ime-learning", true);

        return new DaemonConfig(port, dataDir, mozcUserDictPath, vllmUrl,
                vllmModel, curateInterval, maxEntries,
                gatewayUrl, gatewayPoll, imeLearning);
    }

    private static String stringArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return defaultValue;
    }

    private static int intArg(String[] args, String name, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                try { return Integer.parseInt(args[i + 1]); }
                catch (NumberFormatException e) { return defaultValue; }
            }
        }
        return defaultValue;
    }

    private static boolean boolArg(String[] args, String name, boolean defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Boolean.parseBoolean(args[i + 1]);
            }
        }
        return defaultValue;
    }
}
