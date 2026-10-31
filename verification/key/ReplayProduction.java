import de.uka.ilkd.key.control.KeYEnvironment;
import java.nio.file.Path;

/** Replays each saved proof in a fresh process; never starts proof search. */
public final class ReplayProduction {
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]).toAbsolutePath();
        for (int i = 0; i < Integer.parseInt(args[1]); i++) {
            try (var env = KeYEnvironment.load(root.resolve("proofs/contract-" + i + ".proof"))) {
                var proof = env.getLoadedProof();
                var replay = env.getReplayResult();
                boolean errors = replay == null || replay.hasErrors();
                System.out.println("REPLAY " + i + " CLOSED=" + proof.closed() + " OPEN_GOALS="
                        + proof.openGoals().size() + " ERRORS=" + errors);
                if (errors) System.out.println(replay == null ? "missing replay result" : replay.getErrorList());
                if (errors || !proof.closed() || proof.openGoals().size() != 0) System.exit(3);
            }
        }
    }
}
