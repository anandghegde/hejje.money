package money.hejje.backtest.experiments;

/** The small TCS opening-range strategy the experiment tests share. */
public final class ExperimentITYaml {

    private ExperimentITYaml() {
    }

    public static String yaml(String name) {
        return ExperimentIT.yaml(name);
    }
}
