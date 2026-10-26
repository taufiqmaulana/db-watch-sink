package io.datasync.cli;

import io.datasync.core.JobRunner;
import io.datasync.core.RunKind;
import io.datasync.core.RunResult;
import io.datasync.core.StateStore;
import io.datasync.core.config.ConfigLoader;
import io.datasync.core.config.DataSyncConfig;
import io.datasync.core.config.DataSyncConfig.JobDef;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code datasync run <config.yaml> [--job NAME] [--state FILE] [--reconcile] [--allow-deletes]}
 * <p>
 * Runs every job (or one) once. {@code --reconcile} makes WATERMARK and HYBRID jobs do a full snapshot diff,
 * which also applies deletes. Exit code: 0 all succeeded, 1 a run failed, 2 a run needs review (delete guard
 * tripped; re-run with {@code --allow-deletes}, and {@code --reconcile} for those jobs, once the deletes are confirmed).
 */
public final class Main {

    private static final String USAGE = "usage: datasync run <config.yaml> [--job NAME] [--state FILE] [--reconcile] [--allow-deletes]";

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("run")) return usage();
        Path configFile = Path.of(args[1]);
        String job = null;
        Path stateFile = Path.of("data", "datasync.db");
        boolean allowDeletes = false;
        RunKind kind = RunKind.POLL;
        for (int i = 2; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--allow-deletes") || option.equals("--reconcile")) {
                if (option.equals("--allow-deletes")) allowDeletes = true;
                else kind = RunKind.RECONCILE;
                continue;
            }
            if (i + 1 == args.length) return usage();
            switch (option) {
                case "--job" -> job = args[++i];
                case "--state" -> stateFile = Path.of(args[++i]);
                default -> { return usage(); }
            }
        }

        DataSyncConfig config;
        try (Reader r = Files.newBufferedReader(configFile)) {
            config = ConfigLoader.load(r, System::getenv);
        }
        List<String> jobs = job != null ? List.of(job) : config.jobs().stream().map(JobDef::name).toList();

        int exit = 0;
        try (StateStore state = new StateStore(stateFile)) {
            JobRunner runner = new JobRunner(config, state);
            for (String name : jobs) {
                RunResult result = runner.run(name, kind, allowDeletes);
                exit = Math.max(exit, switch (result.state()) {
                    case SUCCEEDED -> 0;
                    case NEEDS_REVIEW -> 2;
                    default -> 1;
                });
            }
        }
        return exit;
    }

    private static int usage() {
        System.err.println(USAGE);
        return 64;
    }
}
