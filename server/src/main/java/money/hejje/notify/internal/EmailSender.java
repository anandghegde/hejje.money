package money.hejje.notify.internal;

import money.hejje.notify.Notification;
import money.hejje.notify.NotificationType;
import money.hejje.notify.NotifyProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Email over SMTP ({@code spring.mail.*}); off unless {@code hejje.notify.email.enabled} with a from and to address. */
@Component
class EmailSender implements money.hejje.notify.Channels.Sender {

    private final ObjectProvider<JavaMailSender> mail;
    private final NotifyProperties properties;

    EmailSender(ObjectProvider<JavaMailSender> mail, NotifyProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    @Override
    public NotificationType.Channel channel() {
        return NotificationType.Channel.EMAIL;
    }

    @Override
    public boolean configured() {
        return properties.email().enabled() && properties.email().from() != null && !properties.email().from().isBlank()
                && !properties.email().to().isEmpty() && mail.getIfAvailable() != null;
    }

    @Override
    public String status() {
        if (!properties.email().enabled()) {
            return "disabled (hejje.notify.email.enabled)";
        }
        return configured() ? "configured (" + properties.email().to().size() + " recipient(s))" : "missing spring.mail.host, from or to";
    }

    @Override
    public int perMinute() {
        return properties.email().perMinute();
    }

    @Override
    public void sendDigest(java.util.List<Notification> held, java.time.Instant at) throws Exception {
        send(Digest.of(held, at));
    }

    @Override
    public void send(Notification n) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.email().from());
        message.setTo(properties.email().to().toArray(String[]::new));
        message.setSubject("[Hejje " + n.severity() + "] " + n.title());
        message.setText(n.body());
        mail.getObject().send(message);
    }
}
