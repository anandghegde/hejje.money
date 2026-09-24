package money.hejje.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Every {@code @Scheduled} method of the server has a SIM decision (plan M7.1). When this fails, add the new method to
 * {@link SimJobs#standard()} with RUN (it may run on simulation time) or SKIP (it talks to the outside world or to wall
 * time) and the reason.
 */
class SimJobsCoverageTest {

    @Test
    void everyScheduledMethodIsInTheRegistry() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName()));
        Set<String> found = new TreeSet<>();
        for (BeanDefinition d : scanner.findCandidateComponents("money.hejje")) {
            Class<?> type = ClassUtils.forName(d.getBeanClassName(), getClass().getClassLoader());
            if (type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("/test/")) {
                continue; // fixtures of the scheduler's own tests
            }
            ReflectionUtils.doWithMethods(type, m -> found.add(type.getSimpleName() + "#" + m.getName()), m -> m.isAnnotationPresent(Scheduled.class));
        }
        assertThat(found).hasSize(33);
        Set<String> registered = new TreeSet<>(SimJobs.standard().entries().keySet());
        registered.removeIf(k -> k.startsWith("Moments#")); // Spring Modulith's own jobs, checked by SimModeIT
        assertThat(registered).isEqualTo(found);
    }
}
