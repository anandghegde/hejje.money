/**
 * Simulation module (plan Phase 7): the SIM instance's time. {@link money.hejje.common.time.SimClock} is the clock and
 * {@link money.hejje.sim.SimScheduler} runs the {@code @Scheduled} jobs on simulation time, each as the
 * {@link money.hejje.sim.SimJobs} registry decides. Docs: docs/simulation.md.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.sim;
