import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.io.ProofSaver;
import de.uka.ilkd.key.settings.ProofSettings;
import de.uka.ilkd.key.strategy.StrategyProperties;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Proves source contracts by expanding method bodies, without SMT or contract application. */
public final class KeyProduction {
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]).toAbsolutePath();
        var choices = new HashMap<>(ProofSettings.DEFAULT_SETTINGS.getChoiceSettings().getDefaultChoices());
        choices.put("intRules", "intRules:javaSemantics");
        choices.put("runtimeExceptions", "runtimeExceptions:allow");
        choices.put("finalFields", "finalFields:onHeap");
        ProofSettings.DEFAULT_SETTINGS.getChoiceSettings().setDefaultChoices(choices);
        try (var env = KeYEnvironment.load(root.resolve("source"), List.of(), null, null)) {
            System.out.println("KEY_VERSION=" + de.uka.ilkd.key.util.KeYConstants.VERSION
                    + " INTERNAL=" + de.uka.ilkd.key.util.KeYConstants.INTERNAL_VERSION);
            var contracts = env.getProofContracts();
            contracts.removeIf(c -> !(c.getName().contains("RevRegEntryVersion::next(")
                    || c.getName().contains("RevRegEntryRecord::")
                    || c.getName().contains("RevRegEntryService::applyAccumulatorUpdate(")));
            contracts.sort(Comparator.comparing(c -> c.getName()));
            System.out.println("CONTRACTS=" + contracts.size());
            int i = 0;
            boolean all = true;
            for (var c : contracts) {
                System.out.println("CONTRACT " + i + " " + c.getName() + "\n" + c.getPlainText(env.getServices()));
                var proof = env.createProof(c.createProofObl(env.getInitConfig(), c));
                proof.getSettings().getStrategySettings().setMaxSteps(100000);
                proof.getSettings().getStrategySettings().setTimeout(60000);
                var props = proof.getSettings().getStrategySettings().getActiveStrategyProperties();
                props.setProperty(StrategyProperties.METHOD_OPTIONS_KEY, StrategyProperties.METHOD_EXPAND);
                props.setProperty(StrategyProperties.NON_LIN_ARITH_OPTIONS_KEY, StrategyProperties.NON_LIN_ARITH_DEF_OPS);
                proof.getSettings().getStrategySettings().setActiveStrategyProperties(props);
                proof.setActiveStrategy(new de.uka.ilkd.key.strategy.ModularJavaDLStrategyFactory().create(proof, props));
                env.getProofControl().startAndWaitForAutoMode(proof);
                System.out.println("RESULT " + i + " CLOSED=" + proof.closed() + " OPEN_GOALS="
                        + proof.openGoals().size() + " NODES=" + proof.countNodes());
                for (var goal : proof.openGoals()) System.out.println("OPEN_SEQUENT " + goal.sequent());
                ProofSaver.saveToFile(root.resolve("proofs/contract-" + i + ".proof"), proof);
                all &= proof.closed();
                i++;
                proof.dispose();
            }
            if (contracts.size() != Integer.parseInt(args[1]) || !all) System.exit(2);
        }
    }
}
